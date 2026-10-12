package com.sanzz.client.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Skema manifest-nya meniru persis `GET https://alynsampmobile.pro/api/game-files`:
 *
 *   {"files":[{"name":"SAMP.ide","size":"89358","path":"SAMP/SAMP.ide",
 *              "url":"https://…/game-files/SAMP/SAMP.ide"}, …]}
 *
 * Perhatikan: `size` dikirim server Alyn sebagai STRING, bukan angka.
 * `sha256` tidak ada di manifest Alyn — aku tambahin opsional karena itu kelemahan mereka.
 */
data class RemoteFile(
    val name: String,
    val size: Long,
    val path: String,
    val url: String,
    val sha256: String? = null,
)

/** Meniru `GET /api/update` milik Alyn. */
data class UpdateConfig(
    val online: Boolean,
    val gameVersion: String,
    val apkUrl: String?,
    val manifestUrl: String,
    val changelogUrl: String?,
    val serversUrl: String?,
    val raw: JSONObject,
)

/** OkHttp [Call] -> suspend, dengan pembatalan yang benar (bukan execute() blocking). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Jangan di-close di sini; pemanggil yang pakai .use { }
            if (cont.isActive) cont.resume(response)
        }
    })
}

class AssetApi(
    /**
     * GANTI ke host asset-mu sendiri. Jangan hardcode CDN pihak lain di build produksi.
     * Semua path di bawah bersifat relatif terhadap ini.
     */
    private val baseUrl: String = "https://cdn.jsdelivr.net/gh/SanzzLauncher/SanzzLauncher@main",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // PENTING: callTimeout = 0. File 270 MB di jaringan lambat bisa >10 menit.
        // Alyn juga tidak membatasi; mereka andalkan readTimeout per-chunk.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {

    /**
     * Tahap 1 alur Alyn: ambil config bootstrap.
     *
     * [configUrl] boleh diarahkan ke file statis kalau kamu tidak punya server
     * (jalur GitHub Releases), contoh:
     *   https://cdn.jsdelivr.net/gh/USER/REPO@main/config.json
     * Kalau null, dipakai {baseUrl}/api/update seperti Alyn.
     */
    suspend fun fetchUpdate(configUrl: String? = null): UpdateConfig = withContext(Dispatchers.IO) {
        val body = get(configUrl ?: "$baseUrl/api/update")
        val o = JSONObject(body)
        UpdateConfig(
            online = o.optBoolean("status", true),
            gameVersion = o.optString("game_version", ""),
            apkUrl = o.optString("game_url").ifBlank { null },
            manifestUrl = o.optString("game_files").ifBlank { "$baseUrl/api/game-files" },
            changelogUrl = o.optString("changelog").ifBlank { null },
            serversUrl = o.optString("servers").ifBlank { null },
            raw = o,
        )
    }

    /** Tahap 2 alur Alyn: ambil manifest file. `url` boleh absolut atau relatif. */
    suspend fun fetchManifest(url: String? = null): List<RemoteFile> = withContext(Dispatchers.IO) {
        val target = url ?: "$baseUrl/api/game-files"
        val o = JSONObject(get(target))
        val arr = o.optJSONArray("files") ?: throw IOException("Manifest tidak punya field 'files'")
        val out = ArrayList<RemoteFile>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val path = f.optString("path").ifBlank { f.optString("name") }
            if (path.isBlank()) continue
            val size = f.optString("size").toLongOrNull() ?: f.optLong("size", -1L)
            if (size < 0) continue
            val rawUrl = f.optString("url")
            val resolved = when {
                rawUrl.startsWith("https://") || rawUrl.startsWith("http://") -> rawUrl
                rawUrl.isNotBlank() -> baseUrl.trimEnd('/') + "/" + rawUrl.removePrefix("/")
                else -> baseUrl.trimEnd('/') + "/game-files/" + path
            }
            out += RemoteFile(
                name = f.optString("name", path.substringAfterLast('/')),
                size = size,
                path = path,
                url = resolved,
                sha256 = f.optString("sha256").ifBlank { null },
            )
        }
        if (out.isEmpty()) throw IOException("Manifest kosong")
        out
    }

    private suspend fun get(url: String): String {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw IOException("Menolak URL non-http: $url")
        }
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IOException("GET $url -> HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("Body kosong dari $url")
        }
    }

    companion object {
        const val UA = "SanzzClient/1.0 (Android)"
    }
}
