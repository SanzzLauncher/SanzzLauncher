package com.sanzz.client.data

import android.content.Context
import android.util.Log
import com.sanzz.client.data.download.AssetApi
import com.sanzz.client.data.download.AssetDownloader
import com.sanzz.client.data.download.DownloadEvent
import com.sanzz.client.data.download.DownloadProgressService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pengganti jalur "install" di GameInstaller lama.
 *
 * GameInstaller lama cuma punya:
 *   extractBundledAssets()  → 27 MB / 213 file dari dalam APK  (selesai 2-4 detik)
 *   importArchive(uri)      → ekstrak zip pilihan user
 * Dua-duanya dipertahankan — yang ditambah di sini adalah jalur KETIGA yang hilang:
 *   installFromNetwork()    → 295 file / 1,34 GB dari CDN, per-file, bisa resume
 */
object GameInstallerV2 {

    private const val TAG = "GameInstallerV2"

    /**
     * ============================================================================
     *  PERBAIKAN BUG #2 — 2 dari 5 path ini TIDAK ADA di manifest Alyn.
     * ============================================================================
     *  SALAH (kode lama)          BENAR (sesuai manifest)         ukuran
     *  -------------------------  ------------------------------  -----------
     *  models/gta3.img        →   texdb/gta3.img                  270,39 MB
     *  texdb/gta3.txt         →   texdb/gta3/gta3.txt               0,76 MB
     *
     *  Folder models/ di manifest isinya cuma: coll/peds.col, coll/vehicles.col,
     *  coll/weapons.col, effects.fxp (total 0,6 MB). Tidak ada gta3.img di sana.
     *
     *  Selama 2 path ini belum diganti, isReady/fullDataReady akan SELALU false,
     *  apa pun yang kamu download. UI nyangkut di "Perlu Setup" selamanya.
     * ============================================================================
     */
    val REQUIRED_FILES = listOf(
        "data/fonts.dat",            // 3.576 B      — sudah dibundel di APK
        "data/ShopUVs.dat",          // 30.509 B     — sudah dibundel di APK
        "data/script/mainV1.scm",    // 3.169.739 B  — sudah dibundel di APK
        "texdb/gta3.img",            // 283.521.024 B — HARUS dari download
        "texdb/gta3/gta3.txt",       // 796.913 B     — HARUS dari download
        "textures/game.img",         // 283.521.024 B — sentinel tambahan yang bagus
    )

    /** Total manifest Alyn. Pakai ini sebagai gate, bukan 1 MiB seperti kode lama. */
    const val MIN_READY_BYTES = 1_300L * 1024 * 1024   // ~1300 MB dari 1376,2 MB

    private val api = AssetApi(baseUrl = ASSET_BASE_URL)

    /**
     * Mirror untuk AssetDownloader. GitHub Release URL bentuknya tetap:
     *   https://github.com/USER/REPO/releases/download/TAG/NAME
     * jadi mirror = repo kedua dengan asset bernama sama.
     *
     * ISI INI. Repo publik berisi asset berhak cipta Rockstar bisa kena DMCA
     * kapan saja; tanpa mirror kedua, semua client mati serentak.
     */
    private val mirrorRewrites = listOf(
        // "USER/REPO" utama -> cadangan. Tambah sebanyak yang kamu punya.
        // "$GH_USER/$GH_REPO" to "user-kedua/repo-cadangan",
    )

    private fun mirrorsFor(f: com.sanzz.client.data.download.RemoteFile): List<String> =
        listOf(f.url) + mirrorRewrites.map { (from, to) -> f.url.replace(from, to) }

    // ======================================================================
    //  KONFIGURASI BACKEND — jalur GRATIS (GitHub Releases + jsDelivr)
    // ======================================================================
    //  Ganti USER dan REPO punyamu. Tidak perlu server, tidak perlu kartu.
    //
    //  • manifest.json & config.json hidup di branch main repo  → disajikan jsDelivr
    //  • 295 file asset hidup di GitHub Release (tag)           → diunduh langsung
    //
    //  Kalau nanti pindah ke R2/CDN sendiri, cukup ganti 2 konstanta ini.
    //  APK tidak perlu diubah lagi selamanya — itu gunanya config.json.
    // ======================================================================
    private const val GH_USER = "SanzzLauncher"
    private const val GH_REPO = "SanzzLauncher"

    /** jsDelivr = CDN gratis untuk file kecil di repo. */
    private const val JSDELIVR = "https://cdn.jsdelivr.net/gh/$GH_USER/$GH_REPO@main"
    /** Cadangan kalau jsDelivr sedang bermasalah. */
    private const val RAW_GH = "https://raw.githubusercontent.com/$GH_USER/$GH_REPO/main"

    private const val CONFIG_URL = "$JSDELIVR/config.json"
    private const val ASSET_BASE_URL = "$JSDELIVR"

    // ------------------------------------------------------------------
    //  Jalur 1 — base asset dari dalam APK (cepat, tetap dipakai)
    // ------------------------------------------------------------------

    /**
     * Tetap panggil GameInstaller.extractBundledAssets() lebih dulu: 27 MB itu berisi
     * script SA-MP (mainV1.scm), font, Text/, scache*.txt, monetloader — semua yang
     * tidak perlu di-download ulang tiap kali. Setelah itu baru jalur network.
     */
    suspend fun prepareBundled(
        context: Context,
        onProgress: (Float, String) -> Unit,
    ) = GameInstaller.extractBundledAssets(context, onProgress)

    // ------------------------------------------------------------------
    //  Jalur 2 — DOWNLOAD 1,34 GB (yang selama ini tidak ada)
    // ------------------------------------------------------------------

    /**
     * Mengembalikan Flow event supaya UI Compose bisa collect.
     * Sekaligus menghidupkan foreground service + notifikasi progress.
     *
     * Pemakaian di ViewModel/MainActivity:
     *
     *   installJob = scope.launch {
     *       GameInstallerV2.installFromNetwork(context)
     *           .collect { ev -> onDownloadEvent(ev) }
     *   }
     */
    fun installFromNetwork(
        context: Context,
        concurrency: Int = 3,
        mirrors: (com.sanzz.client.data.download.RemoteFile) -> List<String> = ::mirrorsFor,
    ): Flow<DownloadEvent> {
        val app = context.applicationContext
        val gameDir = GameInstaller.getGameDir(app)

        return kotlinx.coroutines.flow.flow {
            // ---- tahap 1: bootstrap config ----
            val cfg = runCatching { api.fetchUpdate(CONFIG_URL) }.getOrNull()
            if (cfg != null && !cfg.online) {
                Log.w(TAG, "Server menyatakan offline (status=false)")
            }

            // ---- tahap 2: manifest ----
            val manifestUrl = cfg?.manifestUrl?.takeIf { it.isNotBlank() } ?: "$JSDELIVR/manifest.json"
            val manifest = runCatching { api.fetchManifest(manifestUrl) }
                .recoverCatching { api.fetchManifest("$RAW_GH/manifest.json") }   // fallback
                .getOrThrow()
            Log.i(TAG, "Manifest: ${manifest.size} file, " +
                    "${manifest.sumOf { it.size } / 1024 / 1024} MB")

            // ---- foreground service WAJIB hidup sebelum byte pertama ----
            DownloadProgressService.start(app, "Mengunduh data game")

            // ---- tahap 3-4: diff lokal + download ----
            val downloader = AssetDownloader(
                gameDir = gameDir,
                concurrency = concurrency,
                mirrors = mirrors,
                // GitHub membatasi 2 GB per asset; tidak ada file kita yang sebesar itu,
                // tapi 3 koneksi paralel sudah cukup — lebih tinggi malah kena throttle.
            )
            var lastNotifAt = 0L
            emitAnd(downloader.stream(manifest)) { ev ->
                // Teruskan ke notifikasi, tapi throttle: max 1x/detik (notification spam = ANR)
                if (ev is DownloadEvent.Progress) {
                    val now = System.currentTimeMillis()
                    if (now - lastNotifAt >= 1000L) {
                        lastNotifAt = now
                        DownloadProgressService.update(
                            app,
                            progress = (ev.fraction * 100).toInt(),
                            subText = "${mb(ev.doneBytes)} / ${mb(ev.totalBytes)} MB" +
                                    (if (ev.bytesPerSec > 1024) "  ·  ${mbps(ev.bytesPerSec)} MB/s" else ""),
                            detail = if (ev.etaSeconds >= 0) "Sisa ±${eta(ev.etaSeconds)}" else null,
                        )
                    }
                }
                if (ev is DownloadEvent.Finished) DownloadProgressService.stop(app)
            }
        }
    }

    /**
     * Versi fire-and-forget untuk dipanggil dari tombol, tanpa collect manual.
     * `onEvent` dipanggil di Main dispatcher → aman untuk update Compose state.
     */
    fun launchInstall(
        scope: CoroutineScope,
        context: Context,
        onEvent: (DownloadEvent) -> Unit,
        onDone: (Boolean) -> Unit = {},
    ): Job = scope.launch {
        val ok = runCatching {
            installFromNetwork(context).collect { ev ->
                withContext(Dispatchers.Main) { onEvent(ev) }
                if (ev is DownloadEvent.Finished) onDone(ev.success)
            }
        }
        if (ok.isFailure) {
            Log.e(TAG, "Install gagal", ok.exceptionOrNull())
            DownloadProgressService.stop(context.applicationContext)
            onDone(false)
        }
    }

    // ------------------------------------------------------------------
    //  Jalur 3 — verifikasi yang BENAR (manifest-driven, bukan 5 path hardcode)
    // ------------------------------------------------------------------

    data class IntegrityReport(
        val isReady: Boolean,
        val bundledAssetsReady: Boolean,
        val fullDataReady: Boolean,
        val missingFiles: List<String>,
        val wrongSizeFiles: List<String>,
        val gameDirectoryPath: String,
        val totalSizeMB: Double,
        val presentBytes: Long,
        val expectedBytes: Long,
    )

    /**
     * Kalau manifest tersedia → cek SEMUA file + ukuran (paling akurat, offline-friendly
     * setelah manifest di-cache). Kalau tidak → fallback ke REQUIRED_FILES.
     */
    suspend fun checkIntegrity(
        context: Context,
        manifest: List<com.sanzz.client.data.download.RemoteFile>? = null,
    ): IntegrityReport = withContext(Dispatchers.IO) {
        val gameDir = GameInstaller.getGameDir(context)
        gameDir.mkdirs()

        val list = manifest ?: runCatching { api.fetchManifest() }.getOrNull()

        val missing = ArrayList<String>()
        val wrongSize = ArrayList<String>()
        var presentBytes = 0L
        var expectedBytes = 0L

        if (list != null) {
            for (f in list) {
                expectedBytes += f.size
                val local = File(gameDir, f.path)
                when {
                    !local.isFile -> missing += f.path
                    local.length() != f.size -> {
                        wrongSize += f.path
                        presentBytes += local.length()
                    }
                    else -> presentBytes += f.size
                }
            }
        } else {
            // Offline & manifest tidak ter-cache → cek path kritis saja
            for (p in REQUIRED_FILES) {
                val local = File(gameDir, p)
                if (!local.isFile) missing += p else presentBytes += local.length()
            }
        }

        val dirBytes = dirSize(gameDir)
        val bundledOk = listOf("data/fonts.dat", "data/ShopUVs.dat", "data/script/mainV1.scm")
            .all { File(gameDir, it).isFile }

        val fullOk = missing.isEmpty() && wrongSize.isEmpty() &&
                (if (list != null) true else dirBytes >= MIN_READY_BYTES)

        IntegrityReport(
            isReady = fullOk && bundledOk,
            bundledAssetsReady = bundledOk,
            fullDataReady = fullOk,
            missingFiles = missing,
            wrongSizeFiles = wrongSize,
            gameDirectoryPath = gameDir.absolutePath,
            totalSizeMB = dirBytes / 1048576.0,
            presentBytes = presentBytes,
            expectedBytes = expectedBytes,
        )
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkBottomUp().forEach { f -> if (f.isFile && !f.name.endsWith(".part")) total += f.length() }
        return total
    }

    // ------------------------------------------------------------------
    //  Format helper
    // ------------------------------------------------------------------

    private fun mb(bytes: Long): Long = bytes / 1024 / 1024
    private fun mbps(bytesPerSec: Double): String =
        String.format(java.util.Locale.US, "%.1f", bytesPerSec / 1024 / 1024)

    private fun eta(sec: Long): String = when {
        sec < 60 -> "${sec}d"
        sec < 3600 -> "${sec / 60}m ${sec % 60}d"
        else -> "${sec / 3600}j ${(sec % 3600) / 60}m"
    }
}

/**
 * Helper kecil supaya flow di atas terbaca linear.
 * (Kalau kamu suka eksplisit, ganti dengan `downloader.stream(manifest).collect { emit(it); side(it) }`.)
 */
private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAnd(
    flow: Flow<T>,
    side: suspend (T) -> Unit,
) {
    flow.collect { v ->
        emit(v)
        side(v)
    }
}
