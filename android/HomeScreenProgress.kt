package com.sanzz.client.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sanzz.client.data.download.DownloadEvent
import java.util.Locale

/**
 * PERBAIKAN BUG #4.
 *
 * Kode lama: `installProgress` = MutableFloatState ditulis langsung dari callback IO,
 * lalu di-render `LinearProgressIndicator` polos. Tidak ada `animateFloatAsState`
 * sama sekali di seluruh HomeScreenKt → bar-nya loncat, bukan bergerak.
 *
 * Ditambah: kerjanya cuma 27 MB lokal, jadi 0→100% terjadi dalam ~2 detik.
 * Dua hal ini digabung = "langsung kelar, nggak ada animasi".
 *
 * Composable di bawah:
 *   • animateFloatAsState(tween 300ms)  → bar bergerak mulus walau event datang 5 Hz
 *   • indeterminate saat fase Preparing  → tidak ada "bar 0%" yang menyesatkan
 *   • MB/s + ETA + x/y file              → user tahu ini beneran 1,34 GB
 */
@Composable
fun AssetDownloadCard(
    event: DownloadEvent?,
    isWorking: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = when (event) {
        is DownloadEvent.Progress -> event.fraction
        is DownloadEvent.Finished -> if (event.success) 1f else 0f
        else -> 0f
    }

    // KUNCI ANIMASINYA DI SINI.
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300),
        label = "assetProgress",
    )

    val indeterminate = isWorking && event !is DownloadEvent.Progress

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {

            Text(
                text = when (event) {
                    null -> if (isWorking) "Mempersiapkan unduhan…" else "Data game belum terpasang"
                    is DownloadEvent.Prepared ->
                        "Menyiapkan ${event.pendingFiles} file (${mb(event.totalBytes)} MB)…"
                    is DownloadEvent.Progress -> "Mengunduh data game…"
                    is DownloadEvent.FileDone -> "Mengunduh data game…"
                    is DownloadEvent.FileFailed ->
                        "Gagal: ${event.path.shortPath()} (sisa ${event.attemptsLeft} percobaan)"
                    is DownloadEvent.Finished ->
                        if (event.success) "Data game lengkap ✅"
                        else "Selesai dengan ${event.failed.size} file gagal"
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))

            if (indeterminate) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val ev = event
                Text(
                    text = if (ev is DownloadEvent.Progress) {
                        buildString {
                            append("${mb(ev.doneBytes)} / ${mb(ev.totalBytes)} MB")
                            append("   ·   ${ev.filesDone}/${ev.filesTotal} file")
                            if (ev.bytesPerSec > 1024) {
                                append("   ·   ")
                                append(String.format(Locale.US, "%.1f", ev.bytesPerSec / 1048576.0))
                                append(" MB/s")
                            }
                            if (ev.etaSeconds >= 0) append("   ·   sisa ±${eta(ev.etaSeconds)}")
                        }
                    } else if (ev is DownloadEvent.Prepared) {
                        "${mb(ev.alreadyBytes)} MB sudah ada di perangkat"
                    } else if (ev is DownloadEvent.Finished) {
                        "${mb(ev.bytesWritten)} MB diunduh dalam ${ev.elapsedMs / 1000}s"
                    } else "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                )
            }

            // Nama file yang sedang berjalan — bukti visual bahwa ini beneran mengunduh
            if (event is DownloadEvent.Progress && event.activeNames.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = event.activeNames.joinToString("  ·  ") { it.shortPath() },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (event is DownloadEvent.Finished && !event.success) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "File gagal: ${event.failed.take(4).joinToString { it.shortPath() }}" +
                            if (event.failed.size > 4) " +${event.failed.size - 4} lainnya" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isWorking) {
                    OutlinedButton(onClick = onCancel) { Text("Batalkan") }
                } else {
                    OutlinedButton(onClick = onRetry) { Text("Unduh / Lanjutkan") }
                }
            }
        }
    }
}

private fun mb(bytes: Long): Long = bytes / 1048576L

private fun String.shortPath(): String =
    if (length <= 34) this else "…" + takeLast(33)

private fun eta(sec: Long): String = when {
    sec < 60 -> "${sec}d"
    sec < 3600 -> "${sec / 60}m ${sec % 60}d"
    else -> "${sec / 3600}j ${(sec % 3600) / 60}m"
}

/*
 * ============================================================================
 *  CARA PASANG DI MainActivity (menggantikan onInstallAssets yang sekarang)
 * ============================================================================
 *
 *  // state
 *  var downloadEvent by remember { mutableStateOf<DownloadEvent?>(null) }
 *  var isWorking      by remember { mutableStateOf(false) }
 *  var installJob     by remember { mutableStateOf<Job?>(null) }
 *  val scope = rememberCoroutineScope()
 *
 *  AssetDownloadCard(
 *      event     = downloadEvent,
 *      isWorking = isWorking,
 *      onCancel  = { installJob?.cancel(); isWorking = false
 *                    DownloadProgressService.stop(context) },
 *      onRetry   = {
 *          isWorking = true
 *          installJob = GameInstallerV2.launchInstall(scope, context,
 *              onEvent = { downloadEvent = it },
 *              onDone  = { ok ->
 *                  isWorking = false
 *                  snackbar.showText(if (ok) "Data game lengkap!" else "Sebagian file gagal.")
 *                  scope.launch { integrityStatus = GameInstallerV2.checkIntegrity(context) }
 *              })
 *      },
 *  )
 *
 *  CATATAN PENTING:
 *  1. Jangan tulis `downloadEvent` dari dispatcher IO. `launchInstall` sudah
 *     memindahkan callback ke Main — itu sebabnya versi lama-mu bisa terasa
 *     patah-patah / tidak ter-update sama sekali.
 *  2. Android 13+ butuh runtime permission POST_NOTIFICATIONS sebelum
 *     DownloadProgressService.start(). Minta sekali di awal.
 *  3. Tombol "Unduh / Lanjutkan" aman dipencet berulang: AssetDownloader
 *     me-resume dari `.part`, jadi tidak mengulang dari nol.
 * ============================================================================
 */
