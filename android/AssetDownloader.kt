package com.sanzz.client.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Downloader asset berbasis manifest — tiruan alur Alyn, tapi:
 *  • progress BERBASIS BYTE (bukan jumlah file)
 *  • resume lewat header Range + file `.part`
 *  • validasi panjang akhir == ukuran deklarasi (prinsip GameModelDownloader Alyn)
 *  • retry dengan backoff, bedakan RETRY vs PERMANENT
 *  • mirror fallback
 *  • event progress di-throttle 5 Hz supaya Compose tidak kebanjiran recomposition
 */
sealed interface DownloadEvent {

    /** Selesai scanning lokal. Ini yang bikin total & ETA bisa diketahui di depan. */
    data class Prepared(
        val totalFiles: Int,
        val totalBytes: Long,
        val alreadyBytes: Long,
        val pendingFiles: Int,
        val skippedFiles: Int,
    ) : DownloadEvent

    data class Progress(
        val doneBytes: Long,
        val totalBytes: Long,
        val filesDone: Int,
        val filesTotal: Int,
        val bytesPerSec: Double,
        val etaSeconds: Long,
        val activeNames: List<String>,
        val lastError: String?,
    ) : DownloadEvent {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f
            else (doneBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    data class FileDone(val path: String, val size: Long, val resumed: Boolean) : DownloadEvent

    data class FileFailed(
        val path: String,
        val reason: String,
        val attemptsLeft: Int,
        val permanent: Boolean,
    ) : DownloadEvent

    data class Finished(
        val okFiles: Int,
        val failed: List<String>,
        val bytesWritten: Long,
        val elapsedMs: Long,
    ) : DownloadEvent {
        val success: Boolean get() = failed.isEmpty()
    }
}

class AssetDownloader(
    private val gameDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val maxAttempts: Int = 5,          // sama seperti GameModelDownloader Alyn: max 5 percobaan
    private val concurrency: Int = 3,          // 3 file paralel; naikkan kalau CDN-mu kuat
    private val bufferSize: Int = 64 * 1024,
    /** Kembalikan daftar URL cadangan. Index 0 = url utama dari manifest. */
    private val mirrors: (RemoteFile) -> List<String> = { listOf(it.url) },
) {

    fun stream(manifest: List<RemoteFile>): Flow<DownloadEvent> = channelFlow {
        val startedAt = System.currentTimeMillis()
        val totalBytes = manifest.fold(0L) { acc, f -> acc + f.size }

        // ---------- Tahap 3: diff lokal ----------
        val pending = ArrayList<RemoteFile>(manifest.size)
        var doneBytes = 0L
        var skipped = 0
        for (f in manifest) {
            val target = File(gameDir, f.path)
            val part = partFileFor(target)
            when {
                target.isFile && target.length() == f.size -> {
                    doneBytes += f.size; skipped++
                }
                part.isFile && part.length() in 1 until f.size -> {
                    doneBytes += part.length()   // byte .part ikut dihitung: sudah ter-download
                    pending += f
                }
                else -> {
                    if (part.isFile) part.delete()          // korup / lebih besar dari deklarasi
                    if (target.isFile) target.delete()      // ukuran salah → unduh ulang
                    pending += f
                }
            }
        }

        send(
            DownloadEvent.Prepared(
                totalFiles = manifest.size,
                totalBytes = totalBytes,
                alreadyBytes = doneBytes,
                pendingFiles = pending.size,
                skippedFiles = skipped,
            )
        )

        if (pending.isEmpty()) {
            send(DownloadEvent.Finished(skipped, emptyList(), 0L, System.currentTimeMillis() - startedAt))
            close()
            return@channelFlow
        }

        // ---------- Counter bersama ----------
        val bytes = AtomicLong(doneBytes)
        val filesDone = AtomicInteger(skipped)
        val active = ConcurrentHashMap<String, String>()      // path -> nama tampil
        val errors = ConcurrentHashMap<String, String>()      // path -> pesan error terakhir
        val failed = ConcurrentHashMap.newKeySet<String>()
        val writtenThisRun = AtomicLong(0L)

        // ---------- Ticker progress 5 Hz ----------
        val ticker = launch {
            var prevBytes = bytes.get()
            var prevAt = System.currentTimeMillis()
            var smoothSpeed = 0.0
            while (true) {
                delay(EMIT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val cur = bytes.get()
                val dt = (now - prevAt) / 1000.0
                if (dt > 0) {
                    val inst = (cur - prevBytes) / dt
                    // EMA supaya angka MB/s tidak bergetar liar
                    smoothSpeed = if (smoothSpeed == 0.0) inst else smoothSpeed * 0.6 + inst * 0.4
                }
                prevBytes = cur; prevAt = now
                val remaining = (totalBytes - cur).coerceAtLeast(0L)
                val eta = if (smoothSpeed > 1024) (remaining / smoothSpeed).toLong() else -1L
                trySend(
                    DownloadEvent.Progress(
                        doneBytes = cur,
                        totalBytes = totalBytes,
                        filesDone = filesDone.get(),
                        filesTotal = manifest.size,
                        bytesPerSec = smoothSpeed,
                        etaSeconds = eta,
                        activeNames = active.values.take(3).toList(),
                        lastError = errors.values.lastOrNull(),
                    )
                )
            }
        }

        // ---------- Tahap 4: download per-file ----------
        val sem = Semaphore(concurrency)
        try {
            kotlinx.coroutines.coroutineScope {
                pending.forEach { f ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            downloadOne(f, bytes, filesDone, active, failed, writtenThisRun, errors)
                        }
                    }
                }
            }
        } finally {
            ticker.cancel()
        }

        send(
            DownloadEvent.Finished(
                okFiles = filesDone.get(),
                failed = failed.toList().sorted(),
                bytesWritten = writtenThisRun.get(),
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        )
        close()
    }

    // ------------------------------------------------------------------

    private suspend fun ProducerScope<DownloadEvent>.downloadOne(
        f: RemoteFile,
        bytes: AtomicLong,
        filesDone: AtomicInteger,
        active: ConcurrentHashMap<String, String>,
        failed: MutableSet<String>,
        writtenThisRun: AtomicLong,
        errors: ConcurrentHashMap<String, String>,
    ) {
        val target = File(gameDir, f.path)
        val part = partFileFor(target)
        target.parentFile?.mkdirs() ?: run {
            failed += f.path
            trySend(DownloadEvent.FileFailed(f.path, "Gagal membuat direktori ${target.parent}", 0, true))
            return
        }
        if (target.isFile && target.length() == f.size) {
            filesDone.incrementAndGet(); return
        }
        active[f.path] = f.name
        failed.remove(f.path)

        var attempt = 0
        var resumed = false
        while (attempt < maxAttempts) {
            attempt++
            coroutineContext.ensureActive()
            try {
                resumed = part.isFile && part.length() > 0
                transfer(f, part, target, bytes, writtenThisRun)
                filesDone.incrementAndGet()
                active.remove(f.path)
                errors.remove(f.path)
                trySend(DownloadEvent.FileDone(f.path, f.size, resumed))
                return
            } catch (ce: CancellationException) {
                active.remove(f.path); throw ce
            } catch (pe: PermanentException) {
                active.remove(f.path)
                failed += f.path
                errors[f.path] = pe.message ?: "permanent"
                trySend(DownloadEvent.FileFailed(f.path, pe.message ?: "permanent", 0, true))
                return
            } catch (e: Exception) {
                val left = maxAttempts - attempt
                errors[f.path] = e.message ?: e.javaClass.simpleName
                trySend(DownloadEvent.FileFailed(f.path, e.message ?: e.javaClass.simpleName, left, false))
                if (left > 0) delay(BACKOFF_MS * attempt)
            }
        }
        active.remove(f.path)
        failed += f.path
    }

    /**
     * Satu percobaan transfer. Mengikuti pola GameModelDownloader Alyn:
     * `.part` → validasi panjang → rename ke target.
     */
    private suspend fun transfer(
        f: RemoteFile,
        part: File,
        target: File,
        bytes: AtomicLong,
        writtenThisRun: AtomicLong,
    ) {
        // Aturan akuntansi (dijaga ketat di seluruh fungsi ini):
        //   bytes == Σ size(file target yang sudah lengkap) + Σ panjang file .part yang ada
        // Setiap kali .part dibuang/dipotong, counter WAJIB dikurangi. Kalau tidak,
        // progress bar bisa lewat 100% saat mirror fallback — persis jenis bug yang
        // bikin angka terasa "ngawur".

        // .part sisa manifest lama / korup
        if (part.isFile && part.length() > f.size) dropPart(part, bytes)

        if (part.isFile && part.length() == f.size) { commitPart(part, target); return }

        val urls = mirrors(f).ifEmpty { listOf(f.url) }
        var lastFailure: Exception? = null

        for ((idx, url) in urls.withIndex()) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                lastFailure = PermanentException("Menolak URL non-http: $url"); continue
            }

            // Hanya mirror utama yang boleh resume; mirror lain mulai dari nol.
            val wantResume = idx == 0 && part.isFile && part.length() in 1 until f.size
            if (!wantResume) dropPart(part, bytes)
            val from = if (wantResume) part.length() else 0L

            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", AssetApi.UA)
                    .header("Accept", "*/*")
                    .apply { if (from > 0L) header("Range", "bytes=$from-") }
                    .build()

                client.newCall(req).await().use { resp ->
                    val code = resp.code

                    if (code == 416) {                    // Range ditolak → .part tidak valid
                        dropPart(part, bytes)
                        throw RetryableException("HTTP 416, .part dibuang")
                    }
                    if (!resp.isSuccessful) {
                        if (isRetryableStatus(code)) throw RetryableException("HTTP $code")
                        else throw PermanentException("HTTP $code untuk ${f.path}")
                    }

                    // 206 = server menghormati Range. Selain itu (200) = ulang dari nol.
                    val appending = code == 206 && from > 0L
                    if (!appending) dropPart(part, bytes)

                    val body = resp.body ?: throw RetryableException("Body kosong")
                    val declaredTotal = f.size            // sumber kebenaran = manifest

                    FileOutputStream(part, appending).use { out ->
                        val buf = ByteArray(bufferSize)
                        val src = body.byteStream()
                        var written = if (appending) from else 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            bytes.addAndGet(n.toLong())
                            writtenThisRun.addAndGet(n.toLong())
                            if (written > declaredTotal) {
                                // "Download exceeded declared size, aborting" — pola Alyn
                                throw RetryableException(
                                    "Download melebihi ukuran deklarasi ($written > $declaredTotal)"
                                )
                            }
                        }
                        out.flush()
                    }

                    // "Download length did not match declaration" — pola Alyn
                    val actual = part.length()
                    if (actual != declaredTotal) {
                        throw RetryableException(
                            "Panjang download tidak cocok: $actual != $declaredTotal (${f.path})"
                        )
                    }
                    commitPart(part, target)
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (pe: PermanentException) {
                throw pe
            } catch (e: Exception) {
                lastFailure = e
                // Byte yang sudah masuk .part tetap tercatat di `bytes` — itu benar,
                // karena attempt berikutnya akan resume dari .part yang sama.
            }
        }
        throw lastFailure ?: RetryableException("Semua mirror gagal untuk ${f.path}")
    }

    /** Buang .part dan kembalikan byte-nya ke counter. Idempotent. */
    private fun dropPart(part: File, bytes: AtomicLong) {
        if (part.isFile) {
            val len = part.length()
            if (len > 0) bytes.addAndGet(-len)
            part.delete()
        }
    }

    private fun commitPart(part: File, target: File) {
        if (target.isFile) target.delete()
        val ok = part.renameTo(target)
        if (!ok) {
            // Fallback: copy manual (beberapa filesystem tidak bisa rename lintas mount)
            part.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            part.delete()
            if (!target.isFile) throw IOException("Gagal memindahkan file ke tempatnya: ${target.path}")
        }
    }

    private fun partFileFor(target: File) = File(target.parentFile, target.name + ".part")

    companion object {
        const val EMIT_INTERVAL_MS = 200L   // 5 Hz
        const val BACKOFF_MS = 800L

        /** 5xx + rate limit = boleh diulang. Persis semangat isRetryableStatus() Alyn. */
        fun isRetryableStatus(code: Int): Boolean = when (code) {
            408, 425, 429 -> true
            in 500..599 -> true
            in 520..529 -> true   // Cloudflare
            else -> false
        }
    }
}

private class RetryableException(msg: String) : IOException(msg)
private class PermanentException(msg: String) : IOException(msg)

