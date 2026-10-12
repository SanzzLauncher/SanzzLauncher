package com.sanzz.client.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sanzz.client.data.GameFileImporter
import com.sanzz.client.data.ImportPhase
import com.sanzz.client.data.ValidationReport
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Layar IMPOR data game — jalur utama, tanpa hosting apa pun.
 *
 * Alurnya:
 *   1. Scan device → tampilkan lokasi GTA:SA yang terdeteksi + persen kelengkapan
 *   2. User pilih: "Pakai folder ini" / "Pilih folder lain" / "Pilih arsip .zip"
 *   3. Salin ke folder game dengan progress berbasis BYTE
 *   4. Validasi 295 file → tampilkan persis apa yang masih kurang
 *
 * Pasang di MainActivity sebagai tab atau sebagai tujuan tombol "Perlu Setup".
 */
@Composable
fun ImportGameFilesScreen(
    modifier: Modifier = Modifier,
    onCompleted: (ValidationReport) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val spec = remember { GameFileImporter.loadSpec(context) }
    var sources by remember { mutableStateOf<List<com.sanzz.client.data.DetectedSource>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf<ImportPhase?>(null) }
    var report by remember { mutableStateOf<ValidationReport?>(null) }
    var busy by remember { mutableStateOf(false) }

    // ---- deteksi awal ----
    LaunchedEffect(Unit) {
        GameFileImporter.init(context)
        sources = GameFileImporter.detectSources(context, spec)
        report = GameFileImporter.validate(context, spec)
        scanning = false
    }

    // ---- picker folder (SAF tree) : user tidak perlu meng-zip apa pun ----
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Izin persisten supaya bisa dibaca ulang lain kali (resume impor)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        busy = true
        scope.launch {
            GameFileImporter.importFromTree(context, uri, spec).collect { p ->
                phase = p
                if (p is ImportPhase.Done) { report = p.report; busy = false; onCompleted(p.report) }
                if (p is ImportPhase.Failed) { busy = false }
            }
        }
    }

    // ---- picker arsip (.zip) : jalur lama, tetap didukung ----
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            GameFileImporter.importFromArchive(context, uri, spec).collect { p ->
                phase = p
                if (p is ImportPhase.Done) { report = p.report; busy = false; onCompleted(p.report) }
                if (p is ImportPhase.Failed) { busy = false }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Text("Data game", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Sanzz Client tidak menyertakan data GTA: San Andreas. " +
                    "Ambil dari install GTA:SA yang kamu punya — sekali saja, " +
                    "sekitar 1,34 GB.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ================= STATUS =================
        report?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = if (r.complete) "Lengkap ✅" else "Belum lengkap",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val frac by animateFloatAsState(
                        targetValue = r.percentBytes,
                        animationSpec = tween(400),
                        label = "validateFrac",
                    )
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${r.matched}/${r.expectedFiles} file · " +
                                "${mb(r.matchedBytes)} / ${mb(r.expectedBytes)} MB " +
                                "(${pct(r.percentBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )

                    if (!r.complete && r.missing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Kurang per grup:", style = MaterialTheme.typography.labelMedium)
                        r.missingByGroup().take(8).forEach { (g, b) ->
                            Text(
                                text = "   ${mb(b).toString().padStart(7)} MB   $g/",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "File terbesar yang kurang: " +
                                    r.missing.take(3).joinToString { it.path.substringAfterLast('/') },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (r.wrongSize.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "⚠ ${r.wrongSize.size} file ukurannya beda (korup atau versi lain) — " +
                                    "akan disalin ulang saat impor.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ================= PROSES IMPOR =================
        phase?.let { p -> ImportProgressCard(p) }

        // ================= SUMBER TERDETEKSI =================
        if (scanning) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mencari data GTA:SA di perangkat…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        } else if (sources.isNotEmpty()) {
            Text("Terdeteksi di perangkat", style = MaterialTheme.typography.titleSmall)
            sources.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${s.matchedFiles} file cocok · ${mb(s.matchedBytes)} MB (${pct(s.percentBytes)})",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.rootFile?.absolutePath ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!s.accessible) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Android membatasi akses langsung ke folder ini. " +
                                        "Pakai \"Pilih folder\" dan arahkan ke sana.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!busy) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { folderPicker.launch(null) }) {
                                Text("Impor lewat picker folder")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Android tidak mengizinkan app menyalin langsung dari " +
                                        "Android/data/ package lain, walau folder-nya terbaca. " +
                                        "Picker di bawah selalu jalan.",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        } else if (!busy) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Tidak menemukan data GTA:SA otomatis.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Di Android 11+ akses ke Android/data/ dibatasi. " +
                                "Pakai tombol di bawah dan arahkan ke folder yang berisi " +
                                "texdb/, textures/, audio/, anim/, data/, models/.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ================= AKSI =================
        if (!busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) { Text("Pilih folder") }
                OutlinedButton(onClick = {
                    archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text("Pilih .zip") }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        report = GameFileImporter.validate(context, spec)
                        phase = null
                    }
                },
            ) { Text("Periksa ulang") }
        }

        if (spec.isEmpty()) {
            Text(
                text = "⚠ game-files-spec.json tidak ditemukan di assets. " +
                        "Salin backend/game-files-spec.json ke app/src/main/assets/ " +
                        "supaya validasi 295 file jalan (sekarang fallback ke 7 path kritis).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ImportProgressCard(p: ImportPhase) {
    val (title, frac, detail) = when (p) {
        is ImportPhase.Scanning -> Triple("Memindai ${p.source}…", null, "")
        is ImportPhase.Copying -> Triple(
            "Menyalin data game…",
            p.fraction,
            "${p.filesDone}/${p.filesTotal} file · ${mb(p.bytesDone)} / ${mb(p.bytesTotal)} MB" +
                    if (p.skippedFiles > 0) "  ·  ${p.skippedFiles} dilewati (sudah ada)" else "",
        )
        is ImportPhase.Validating -> Triple("Memverifikasi…", null, "")
        is ImportPhase.Done -> Triple(
            if (p.report.complete) "Selesai — data lengkap ✅" else "Selesai — masih ada yang kurang",
            p.report.percentBytes,
            "${p.report.matched}/${p.report.expectedFiles} file · ${p.elapsedMs / 1000}s",
        )
        is ImportPhase.Failed -> Triple("Gagal", 0f, p.message)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (frac == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                // INI yang bikin bar bergerak, bukan loncat
                val a by animateFloatAsState(frac, tween(300), label = "importFrac")
                LinearProgressIndicator(
                    progress = { a },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                )
            }
            if (p is ImportPhase.Copying) {
                Text(
                    text = p.currentName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun mb(bytes: Long): Long = bytes / 1048576L
private fun pct(f: Float): String = String.format(Locale.US, "%.1f%%", f * 100f)
