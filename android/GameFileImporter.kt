package com.sanzz.client.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Jalur IMPOR: user menyediakan data game dari install GTA:SA miliknya sendiri.
 *
 * Kenapa jalur ini yang utama, bukan download otomatis:
 *   • Tidak ada 1,34 GB yang harus kamu host → biaya $0 selamanya
 *   • Tidak ada paparan hak cipta untukmu sebagai pengembang launcher
 *   • Tidak ada backend, tidak ada CDN, tidak ada risiko DMCA, tidak butuh mirror
 *   • Sudah didukung kodemu: importArchive() + archivePickerLauncher
 *
 * Yang ditambahkan di sini di atas kode lama:
 *   1. Import dari FOLDER (SAF tree), bukan cuma zip — user tidak perlu meng-zip dulu
 *   2. Deteksi otomatis lokasi GTA:SA di device
 *   3. Validasi terhadap spec 295 file (backend/game-files-spec.json)
 *   4. Progress berbasis BYTE, bukan jumlah file
 *   5. Proteksi Zip Slip (path traversal)
 *   6. Resume: file yang sudah benar ukurannya di-skip
 */

/** Satu entri spec. Isinya cuma path + ukuran — metadata, bukan asset. */
data class SpecEntry(val path: String, val size: Long)

/** Lokasi kandidat yang terdeteksi di device. */
data class DetectedSource(
    val label: String,
    val rootFile: File?,          // null kalau hanya bisa diakses lewat SAF
    val matchedFiles: Int,
    val matchedBytes: Long,
    val totalSpecBytes: Long,
    val accessible: Boolean,
) {
    val percentBytes: Float
        get() = if (totalSpecBytes <= 0) 0f
        else (matchedBytes.toFloat() / totalSpecBytes).coerceIn(0f, 1f)
}

sealed interface ImportPhase {
    data class Scanning(val source: String) : ImportPhase
    data class Copying(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentName: String,
        val skippedFiles: Int,
    ) : ImportPhase {
        val fraction: Float
            get() = if (bytesTotal <= 0) 0f
            else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }
    data class Validating(val report: ValidationReport) : ImportPhase
    data class Done(val report: ValidationReport, val elapsedMs: Long) : ImportPhase
    data class Failed(val message: String, val cause: String?) : ImportPhase
}

data class ValidationReport(
    val complete: Boolean,
    val matched: Int,
    val matchedBytes: Long,
    val missing: List<SpecEntry>,
    val wrongSize: List<Pair<SpecEntry, Long>>,
    val expectedFiles: Int,
    val expectedBytes: Long,
) {
    val percentBytes: Float
        get() = if (expectedBytes <= 0) 0f
        else (matchedBytes.toFloat() / expectedBytes).coerceIn(0f, 1f)

    /** Ringkasan per grup, buat ditampilkan ke user: "textures/ kurang 656 MB". */
    fun missingByGroup(): List<Pair<String, Long>> =
        missing.groupBy { it.path.substringBefore('/') }
            .map { (g, list) -> g to list.sumOf { it.size } }
            .sortedByDescending { it.second }
}

object GameFileImporter {

    private const val TAG = "GameFileImporter"
    private const val SPEC_ASSET = "game-files-spec.json"
    private const val BUFFER = 64 * 1024

    // ------------------------------------------------------------------
    //  Spec
    // ------------------------------------------------------------------

    /**
     * Baca spec dari assets. Salin `backend/game-files-spec.json` ke
     * `app/src/main/assets/game-files-spec.json`.
     *
     * Isinya HANYA path + ukuran byte (295 entri, ~27 KB) — metadata,
     * bukan asset. Aman dibundel ke APK.
     */
    fun loadSpec(context: Context): List<SpecEntry> {
        return try {
            val json = context.assets.open(SPEC_ASSET).bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("files")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path").ifBlank { return@mapNotNull null }
                val size = o.optString("size").toLongOrNull() ?: o.optLong("size", -1L)
                if (size < 0) null else SpecEntry(path, size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal baca $SPEC_ASSET — sudah disalin ke app/src/main/assets/?", e)
            emptyList()
        }
    }

    /**
     * Fallback kalau spec tidak dibundel: 7 path kritis.
     * Ini yang diperbaiki dari kode lama — `models/gta3.img` dan `texdb/gta3.txt`
     * TIDAK ADA di layout GTASA mobile. Yang benar di bawah.
     */
    val CRITICAL_PATHS = listOf(
        SpecEntry("texdb/gta3.img", 283_521_024),
        SpecEntry("texdb/gta3/gta3.txt", 796_913),
        SpecEntry("textures/game.img", 283_521_024),
        SpecEntry("textures/game.bin", 233_769_877),
        SpecEntry("data/script/mainV1.scm", 3_169_739),
        SpecEntry("data/fonts.dat", 3_576),
        SpecEntry("data/ShopUVs.dat", 30_509),
    )

    // ------------------------------------------------------------------
    //  Deteksi sumber di device
    // ------------------------------------------------------------------

    /**
     * Kandidat lokasi data GTA:SA. Urut dari yang paling mungkin.
     *
     * Catatan jujur: di Android 11+ (API 30), akses langsung ke `Android/data/`
     * package lain dibatasi untuk app biasa. Manifest-mu sudah declare
     * MANAGE_EXTERNAL_STORAGE — kalau user memberi "All files access",
     * `File.canRead()` biasanya jadi true. Kalau tidak, `accessible=false`
     * dan UI harus mengarahkan user ke picker folder (SAF), yang selalu jalan.
     */
    private fun candidateRoots(): List<Pair<String, File>> {
        val pkgs = listOf(
            "com.rockstargames.gtasa",     // GTA:SA Android (Rockstar)
            "com.rockstar.gtasa",
        )
        val out = ArrayList<Pair<String, File>>()

        val ext = Environment.getExternalStorageDirectory()
        for (p in pkgs) {
            out += "GTA:SA — $p (files)" to File(ext, "Android/data/$p/files")
            out += "GTA:SA — $p (obb)" to File(ext, "Android/obb/$p")
        }
        // Storage sekunder / SD card
        runCatching {
            val dirs = context_getExternalDirs()
            for (d in dirs) {
                if (d == null) continue
                val base = File(d.absolutePath.substringBefore("/Android"))
                for (p in pkgs) out += "GTA:SA — ${base.name}/$p" to File(base, "Android/data/$p/files")
            }
        }
        // Lokasi umum kalau user sudah menyalin manual
        out += "Folder unduhan" to File(ext, "Download/gta3")
        out += "Folder GTA (root sdcard)" to File(ext, "gta")
        out += "Folder SanzzClient" to File(ext, "SanzzClient")

        return out
    }

    /** Dipanggil dari dalam object; context disimpan saat init. */
    private var appContext: Context? = null
    private fun context_getExternalDirs(): Array<File?> =
        appContext?.getExternalFilesDirs(null) ?: emptyArray()

    fun init(context: Context) { appContext = context.applicationContext }

    /**
     * Scan semua kandidat dan laporkan mana yang paling lengkap.
     * Murah: cuma stat file, tidak menyalin apa pun.
     */
    suspend fun detectSources(context: Context, spec: List<SpecEntry>): List<DetectedSource> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            init(context)
            val gameDir = GameInstaller.getGameDir(context)
            val roots = candidateRoots() + listOf("Folder game client" to gameDir)
            val totalSpec = spec.sumOf { it.size }

            roots.mapNotNull { (label, root) ->
                if (!root.exists()) return@mapNotNull null
                var matched = 0
                var matchedBytes = 0L
                for (e in spec) {
                    val f = File(root, e.path)
                    if (f.isFile && f.length() == e.size) { matched++; matchedBytes += e.size }
                }
                // Juga coba struktur satu level di bawah (sering ada folder pembungkus)
                if (matched == 0 && root.isDirectory) {
                    root.listFiles()?.take(12)?.forEach { sub ->
                        if (!sub.isDirectory) return@forEach
                        for (e in spec) {
                            val f = File(sub, e.path)
                            if (f.isFile && f.length() == e.size) { matched++; matchedBytes += e.size }
                        }
                    }
                }
                if (matched == 0) return@mapNotNull null
                DetectedSource(
                    label = label,
                    rootFile = root,
                    matchedFiles = matched,
                    matchedBytes = matchedBytes,
                    totalSpecBytes = totalSpec,
                    accessible = runCatching { root.canRead() }.getOrDefault(false),
                )
            }.sortedByDescending { it.matchedBytes }
        }

    // ------------------------------------------------------------------
    //  Import dari FOLDER (SAF tree) — tidak perlu zip
    // ------------------------------------------------------------------

    /**
     * Import dari URI folder hasil `ACTION_OPEN_DOCUMENT_TREE`.
     * Ini jalur paling ramah user: mereka tinggal arahkan ke folder GTA:SA,
     * tidak perlu meng-zip 1,34 GB dulu.
     */
    fun importFromTree(
        context: Context,
        treeUri: Uri,
        spec: List<SpecEntry>,
    ): Flow<ImportPhase> = flow {
        val started = System.currentTimeMillis()
        val gameDir = GameInstaller.getGameDir(context)
        gameDir.mkdirs()

        emit(ImportPhase.Scanning("folder"))

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Tidak bisa membaca folder yang dipilih")

        // ---- scan: kumpulkan (relPath, DocumentFile, size) ----
        val wanted = spec.associateBy { it.path }
        val found = ArrayList<Triple<String, DocumentFile, Long>>()
        scanTree(context, root, "", wanted, found)

        val bytesTotal = found.sumOf { it.third }
        var bytesDone = 0L
        var filesDone = 0
        var skipped = 0

        for ((rel, doc, size) in found) {
            currentCoroutineContext().ensureActive()

            val target = safeResolve(gameDir, rel) ?: run {
                Log.w(TAG, "Lewati path mencurigakan: $rel"); continue
            }
            // Resume: sudah ada dengan ukuran benar → skip
            if (target.isFile && target.length() == size) {
                bytesDone += size; filesDone++; skipped++
                emit(copyPhase(filesDone, found.size, bytesDone, bytesTotal, rel, skipped))
                continue
            }

            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(BUFFER)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            bytesDone += n
                            if (written % (2 * 1024 * 1024) < BUFFER) {
                                emit(copyPhase(filesDone, found.size, bytesDone, bytesTotal, rel, skipped))
                            }
                        }
                        out.flush()
                    }
                } ?: throw IOException("Tidak bisa membuka $rel")

                if (part.length() != size) {
                    part.delete()
                    throw IOException("Ukuran tidak cocok untuk $rel: ${part.length()} != $size")
                }
                if (target.isFile) target.delete()
                if (!part.renameTo(target)) {
                    part.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o, BUFFER) } }
                    part.delete()
                }
                filesDone++
                emit(copyPhase(filesDone, found.size, bytesDone, bytesTotal, rel, skipped))
            } catch (ce: CancellationException) {
                part.delete(); throw ce
            } catch (e: Exception) {
                part.delete()
                Log.e(TAG, "Gagal salin $rel", e)
                // Lanjut ke file lain; laporan validasi di akhir yang menentukan
            }
        }

        // validate() itu walk 295 file — jangan dipanggil dua kali
        val report = validate(context, spec)
        emit(ImportPhase.Validating(report))
        emit(ImportPhase.Done(report, System.currentTimeMillis() - started))
    }.flowOn(Dispatchers.IO)

    private fun copyPhase(
        filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long,
        current: String, skipped: Int,
    ) = ImportPhase.Copying(filesDone, filesTotal, bytesDone, bytesTotal, current, skipped)

    /** Walk rekursif lewat SAF. Hanya ambil file yang ada di spec. */
    private fun scanTree(
        context: Context,
        dir: DocumentFile,
        prefix: String,
        wanted: Map<String, SpecEntry>,
        out: MutableList<Triple<String, DocumentFile, Long>>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                scanTree(context, child, rel, wanted, out)
            } else if (child.isFile) {
                val entry = wanted[rel] ?: continue
                val len = runCatching { child.length() }.getOrDefault(-1L)
                // Terima kalau ukuran cocok, ATAU kalau ukuran tidak terbaca (-1)
                if (len == entry.size || len < 0) out += Triple(rel, child, if (len < 0) entry.size else len)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Import dari ZIP/APK/OBB (jalur lama, diperbaiki)
    // ------------------------------------------------------------------

    /**
     * Sama seperti importArchive() lama, tapi:
     *   • progress berbasis BYTE (bukan jumlah entri)
     *   • proteksi Zip Slip
     *   • skip entri yang sudah benar ukurannya
     */
    fun importFromArchive(
        context: Context,
        archiveUri: Uri,
        spec: List<SpecEntry>,
    ): Flow<ImportPhase> = flow {
        val started = System.currentTimeMillis()
        val gameDir = GameInstaller.getGameDir(context)
        gameDir.mkdirs()
        val canonicalRoot = gameDir.canonicalPath

        emit(ImportPhase.Scanning("arsip"))

        // Ukuran total arsip untuk basis progress (kalau terbaca)
        val archiveLen = runCatching {
            DocumentFile.fromSingleUri(context, archiveUri)?.length() ?: -1L
        }.getOrDefault(-1L)
        val bytesTotal = if (archiveLen > 0) archiveLen else spec.sumOf { it.size }

        var bytesDone = 0L
        var filesDone = 0
        var skipped = 0
        var entries = 0

        context.contentResolver.openInputStream(archiveUri)?.use { raw ->
            ZipInputStream(raw.bufferedInput()).use { zis ->
                val buf = ByteArray(BUFFER)
                var e = zis.nextEntry
                while (e != null) {
                    currentCoroutineContext().ensureActive()
                    if (!e.isDirectory) {
                        entries++
                        val rel = e.name.removePrefix("./").replace('\\', '/')
                        val target = File(gameDir, rel)

                        // ---- PROTEKSI ZIP SLIP ----
                        // Entri "../../etc/something" harus ditolak, kalau tidak
                        // arsip jahat bisa menulis di luar folder game.
                        if (!target.canonicalPath.startsWith(canonicalRoot + File.separator)) {
                            Log.w(TAG, "Zip Slip ditolak: ${e.name}")
                            e = zis.nextEntry; continue
                        }

                        if (target.isFile && e.size > 0 && target.length() == e.size) {
                            bytesDone += e.size; filesDone++; skipped++
                            e = zis.nextEntry; continue
                        }

                        target.parentFile?.mkdirs()
                        var written = 0L
                        target.outputStream().use { out ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zis.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n; bytesDone += n
                            }
                            out.flush()
                        }
                        filesDone++
                        emit(copyPhase(filesDone, entries, bytesDone, bytesTotal, rel, skipped))
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } ?: throw IOException("Tidak bisa membuka arsip")

        val report = validate(context, spec)
        emit(ImportPhase.Validating(report))
        emit(ImportPhase.Done(report, System.currentTimeMillis() - started))
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    //  Validasi
    // ------------------------------------------------------------------

    /**
     * Bandingkan SEMUA entri spec dengan isi folder game — bukan 5 path hardcode
     * seperti checkGameIntegrity() lama.
     */
    suspend fun validate(context: Context, specOrNull: List<SpecEntry>? = null): ValidationReport =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val spec = specOrNull ?: loadSpec(context).ifEmpty { CRITICAL_PATHS }
            val gameDir = GameInstaller.getGameDir(context)
            val missing = ArrayList<SpecEntry>()
            val wrong = ArrayList<Pair<SpecEntry, Long>>()
            var matched = 0
            var matchedBytes = 0L

            for (e in spec) {
                val f = File(gameDir, e.path)
                when {
                    !f.isFile -> missing += e
                    f.length() != e.size -> wrong += e to f.length()
                    else -> { matched++; matchedBytes += e.size }
                }
            }
            ValidationReport(
                complete = missing.isEmpty() && wrong.isEmpty(),
                matched = matched,
                matchedBytes = matchedBytes,
                missing = missing.sortedByDescending { it.size },
                wrongSize = wrong,
                expectedFiles = spec.size,
                expectedBytes = spec.sumOf { it.size },
            )
        }

    // ------------------------------------------------------------------
    //  Util
    // ------------------------------------------------------------------

    /** Selesaikan path relatif dengan aman; null kalau keluar dari root. */
    private fun safeResolve(root: File, rel: String): File? {
        val cleaned = rel.replace('\\', '/').removePrefix("./")
        val f = File(root, cleaned)
        return if (f.canonicalPath.startsWith(root.canonicalPath + File.separator)) f else null
    }

    private fun InputStream.bufferedInput(): InputStream =
        if (this is BufferedInputStream) this else BufferedInputStream(this, BUFFER)
}
