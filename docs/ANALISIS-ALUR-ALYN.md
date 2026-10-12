# Bedah Alur Download Asset — Alyn SA-MP Mobile vs SanzzClient v1.0.0

> Hasil reverse-engineering 2 APK + API produksi Alyn, 12 Okt 2026.
> Sumber: `SanzzClient-v1.0.0-arm64.apk` (39,5 MB) & `alyn_sampmobile.apk` (67,0 MB) dari `https://alynsampmobile.pro/api/game/alyn_sampmobile.apk`

---

## 0. Kesimpulan 30 detik

**Client-mu tidak download apa-apa.** Tidak ada satu baris pun kode HTTP asset di seluruh dex SanzzClient.
Satu-satunya URL di 41 MB dex itu cuma `https://alynsampmobile.pro/api/servers`.

Yang kamu lihat sebagai "download" itu adalah `GameInstaller.extractBundledAssets()` —
menyalin **27 MB / 213 file** yang sudah ada **di dalam APK-mu sendiri** ke folder game.
Makanya selesai 2–4 detik. Bukan bug animasi — **memang tidak ada 1,34 GB yang di-download.**

Alyn download **295 file / 1.376,2 MB (1,344 GB)** per-file dari CDN, satu per satu, dengan resume.

---

## 1. Alur lengkap Alyn (yang harus kamu tiru)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. BOOT                                                                 │
│    Flutter (libapp.so) → features/status/status_service.dart            │
│    GET {webBase}/api/update                                             │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
   {"status":true,"game_version":"20.7.8",
    "game_url":".../api/game/alyn_sampmobile-20.7.8-c11386bd649a.apk",
    "game_files":"https://alynsampmobile.pro/api/game-files",
    "changelog":..., "servers":..., "banned":...,
    "full_list":".../api/game-files-list?type=full",
    "lite_list":"...?type=lite", "samp_list":"...?type=samp", "new_list":"...?type=new"}
                                │
┌───────────────────────────────▼─────────────────────────────────────────┐
│ 2. MANIFEST                                                             │
│    GET /api/game-files                                                  │
│    → {"files":[ {name, size, path, url}, ... x295 ]}                    │
│      size = STRING byte. url = https://…/game-files/<path>              │
│      TOTAL 1.376,2 MB = 1,344 GB                                        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. DIFF LOKAL                                                           │
│    total = Σ size  →  diketahui SEBELUM download mulai                  │
│    skip file yang sudah ada dengan length == size                       │
│    sisanya = antrian                                                    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. DOWNLOAD PER-FILE (Dart: class `Downloader`, enum `DownloadPhase`)   │
│    • tulis ke <path>.part                                               │
│    • header Range: bytes=<len(part)>-   → resume (server balas 206)     │
│    • validasi: panjang akhir WAJIB == size manifest                     │
│    • rename .part → path final (atomic)                                 │
│    • retry, fallback mirror                                             │
└──────────────┬──────────────────────────────────┬───────────────────────┘
               ▼                                  ▼
┌──────────────────────────────┐  ┌───────────────────────────────────────┐
│ 5a. UI Flutter               │  │ 5b. FOREGROUND SERVICE (native)       │
│   LinearProgressIndicator    │  │  ro.alynsampmobile.download.START     │
│   CircularProgressIndicator   │  │  ro.alynsampmobile.download.UPDATE    │
│   "%d files to download"     │  │  ro.alynsampmobile.download.STOP      │
│   "%d files could not be     │  │  extras: "progress"(Int), "title"(Str)│
│      downloaded. Try again." │  │  → notifikasi berjalan + app tidak    │
│                              │  │    mati saat di-background            │
└──────────────────────────────┘  └───────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 6. VERIFIKASI                                                           │
│    "Game files are incomplete. Download them before playing."           │
│    "The game folder does not exist yet. Download the game files first." │
│    Kalau gagal total → buka $webBase/download (WebView/browser)         │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 7. TERPISAH: SA-MP 0.3.DL CUSTOM MODEL (saat connect ke server)         │
│    ro.alynsampmobile.game.GameModelDownloader (Java, dipanggil native)  │
│    JNI: nativeCustomModelDownloadProgress / …DownloadFinished           │
└─────────────────────────────────────────────────────────────────────────┘
```

### Isi manifest Alyn (angka nyata)

| Grup | Ukuran |
|---|---|
| `textures/` | 655,9 MB |
| `texdb/` | 607,0 MB |
| `audio/` | 62,6 MB |
| `anim/` | 27,1 MB |
| `data/` | 19,6 MB |
| `SAMP/` | 2,9 MB |
| `models/` | 0,6 MB |
| `fonts/` | 0,4 MB |
| `stream.ini` | 197 B |
| **TOTAL** | **295 file · 1.376,2 MB · 1,344 GB** |

File terbesar: `texdb/gta3.img` 270,39 MB · `textures/game.img` 270,39 MB ·
`textures/game.bin` 222,94 MB · `texdb/gta3/gta3.dxt.dat` 176,73 MB ·
`texdb/gta_int.img` & `textures/interiors.img` 42,76 MB · `texdb/samp.img` & `textures/multiplayer.img` 34,25 MB

Median 13,9 KB, rata-rata 4,67 MB → **distribusi timpang**: banyak file kecil + sedikit file raksasa.
Ini penting: progress **wajib berbasis byte**, bukan berbasis jumlah file, atau bar-nya bakal loncat-loncat.

### Perilaku CDN-nya (sudah aku uji langsung)

```
GET https://alynsampmobile.pro/game-files/texdb/gta3.img
HTTP/2 200 · content-length: 283521024 · accept-ranges: bytes
server: cloudflare · cf-cache-status: HIT · cache-control: public, max-age=120
x-served-by: alynsampmobile.pro

Range: bytes=100000000-100000999  → HTTP 206, 1000 bytes  ✅ RESUME JALAN
```

Jadi: **bukan Cloudflare R2 / object storage**, tapi origin sendiri di belakang Cloudflare cache.
`accept-ranges: bytes` aktif → resume per-file bisa diandalkan.

### Endpoint lain yang ketemu di Dart AOT

`/api/status` (400 tanpa signature — pakai header `x-alyn-ts`, `x-alyn-nonce`, `x-alyn-sig`),
`/api/client/challenge` (405 di GET → POST), `/api/client/attest`, `/api/launcher/me`,
`/api/launcher/auth/code`, `/api/testers/check`, `/api/banned`, `/api/changelog`,
`/api/online-users` (521 user saat aku cek), `/api/servers`,
`/api/game-files-list?type=full|lite|samp|new` (404 untuk publik — kemungkinan butuh signature).

---

## 2. Referensi terbaik di dalam APK-mu sendiri: `GameModelDownloader`

Kelas `ro.alynsampmobile.game.GameModelDownloader` **sudah ada di APK SanzzClient-mu** (kamu ikut
membundel package `ro.alynsampmobile.game`). Ini downloader HTTP yang benar, dan polanya persis
yang kamu butuhkan. String literal yang aku ekstrak dari smali:

```
"HEAD"  "GET"  "Location"  " with no Location header"  "Refusing non-http url"
".part"
"Download exceeded declared size, aborting"
"Download length did not match declaration"
"Download got HTTP "  "Download returned HTTP "  "Download failed (attempt "
" (attempt "   "/5)"                      ← max 5 percobaan
"Could not create cache directory"
"Could not move downloaded file into place"
"Could not delete "
"cdn.jsdelivr.net"  "/gh/"  "raw.githubusercontent.com"
"Retrying GitHub-backed jsDelivr asset through origin"   ← mirror fallback
"AlynModelDownload" (User-Agent)   "SAMP/0.3"   "Accept: */*"
"nativeCustomModelDownloadFinished not linked"
```

Method-nya:
```java
void   download(String url, String path, int a, int b, int c, int d)
Outcome attemptTransfer(String url, String path, int,int,int,int)
Outcome receiveResponse(HttpURLConnection, String path, int,int,int)
HttpURLConnection open(URL)
static URL     githubRawFallback(URL)
static boolean isRetryableStatus(int)
static void    progress(int, int, long)     → JNI ke native
static void    finish(int, int, boolean)    → JNI ke native
static void    drainQuietly(HttpURLConnection)
static void    deleteQuietly(File)
enum Outcome { SUCCESS, RETRY, PERMANENT }
```

**Catat 5 prinsip yang dia pakai** — ini yang hilang di kode kamu:

1. **HEAD dulu** untuk ambil ukuran deklarasi, baru GET.
2. **`.part` lalu rename** — file final tidak pernah setengah jadi.
3. **Validasi panjang** — kalau `bytes read != declared size`, dianggap gagal, bukan sukses.
4. **Bedakan RETRY vs PERMANENT** — 5xx/429 diulang, 404/403 tidak.
5. **Mirror fallback** — kalau CDN utama gagal, coba origin lain.

---

## 3. Kenapa punyamu "langsung kelar" — 5 bukti dari smali

### Bug 1 — Tidak ada downloader sama sekali ⛔ (ini penyebab utamanya)

`com.sanzz.client.data.GameInstaller` cuma punya 6 method:

```
getGameDir(Context) File
extractBundledAssets(Context, Function2, Continuation)   ← salin dari asset APK (27 MB)
importArchive(Context, Uri, Function2, Continuation)     ← ekstrak zip pilihan user (SAF)
checkGameIntegrity(Context, Continuation)
clearBinCaches(Context, Continuation)
calculateDirSize(File) long     collectAssetsRecursively(AssetManager, String, List) void
```

Tidak ada `download`, tidak ada URL asset, tidak ada `HttpURLConnection`/OkHttp untuk asset.
OkHttp di APK-mu cuma dipakai `ServerRepository` (`/api/servers`) dan `SampQueryClient`.

### Bug 2 — `checkGameIntegrity` minta 2 file yang TIDAK PERNAH ADA ⛔⛔

Path yang dicek (dari `GameInstaller$checkGameIntegrity$2.smali`):

| Dicek SanzzClient | Ada di manifest Alyn? | Yang benar |
|---|---|---|
| `data/fonts.dat` | ✅ 3.576 B | — |
| `data/ShopUVs.dat` | ✅ 30.509 B | — |
| `data/script/mainV1.scm` | ✅ 3.169.739 B | — |
| `models/gta3.img` | ❌ **TIDAK ADA** | **`texdb/gta3.img`** (270,39 MB) |
| `texdb/gta3.txt` | ❌ **TIDAK ADA** | **`texdb/gta3/gta3.txt`** (0,76 MB) |

Folder `models/` di manifest isinya cuma `coll/peds.col`, `coll/vehicles.col`, `coll/weapons.col`,
`effects.fxp` (total 0,6 MB). `gta3.img` adanya di `texdb/`.

**Akibat:** `isReady` / `fullDataReady` akan **selalu `false` selamanya**, mau download apa pun.
UI-mu bakal nyangkut di "Perlu Setup" terus.

### Bug 3 — `totalSizeMB` dihitung tapi tidak dipakai sebagai gate

```smali
invoke-static {v0, p1}, GameInstaller;->access$calculateDirSize(GameInstaller;File;)J
const-wide/high16 v3, 0x4130000000000000L    # 1048576.0  (= 1 MiB)
div-double/2addr v0, v3                       # totalSizeMB = bytes / 1048576
```

`totalSizeMB` cuma jadi field informasi di `GameIntegrityStatus`. Tidak ada ambang batas
(minimal ~1300 MB) yang memaksa user download. Jadi "Asset bawaan berhasil diekstrak!"
muncul padahal data baru 27 MB.

`GameIntegrityStatus(isReady, bundledAssetsReady, fullDataReady, missingFiles, gameDirectoryPath, totalSizeMB)`
→ `bundledAssetsReady` (27 MB) vs `fullDataReady` (1,34 GB) sudah dipisah, tapi tidak ada
jalur untuk mencapai `fullDataReady` selain `importArchive` manual.

### Bug 4 — Progress berbasis FILE, bukan BYTE → bar loncat 0→100%

Signature callback-mu: `onProgress: (Float, String) -> Unit`

```smali
# per file:  "Menginstal " + nama + " (" + i + ...
invoke-interface {v0, v6, v8}, Function2;->invoke(Object,Object)Object

# penutup:
const/high16 v3, 0x3f800000    # 1.0f
const-string v5, "Instalasi file game selesai!"
invoke-interface {v0, v3, v5}, Function2;->invoke(Object,Object)Object
```

213 file lokal disalin dari APK → beberapa detik → `Float` naik 0.0 → 1.0 dalam
puluhan frame. Di `HomeScreenKt` cuma ada **satu** `LinearProgressIndicator` dan
**tidak ada `animateFloatAsState`** sama sekali. Jadi bukan "animasinya belum ada" —
memang tidak pernah ada interpolasi, dan durasi kerjanya terlalu pendek untuk terlihat.

State-nya: `installProgress` = `MutableFloatState`, `installStatusText`, `isInstalling`.

### Bug 5 — Permission sudah siap, tapi tidak ada `<service>` di manifest

Permission yang **sudah** kamu declare:
```
INTERNET · ACCESS_NETWORK_STATE · ACCESS_WIFI_STATE · WAKE_LOCK
FOREGROUND_SERVICE · FOREGROUND_SERVICE_DATA_SYNC · POST_NOTIFICATIONS
MANAGE_EXTERNAL_STORAGE · READ/WRITE_EXTERNAL_STORAGE · RECORD_AUDIO · VIBRATE · DUMP
```

Komponen yang terdaftar: `com.sanzz.client.MainActivity` (MAIN/LAUNCHER),
`ro.alynsampmobile.game.Game`, provider `ro.alynsampmobile.launcher.fileprovider`,
`androidx-startup`. **Tidak ada `<service>`.**

Artinya: `FOREGROUND_SERVICE_DATA_SYNC` sudah di-declare tapi nganggur.
Begitu kamu tambah download 1,34 GB, tanpa foreground service prosesnya **dibunuh Android**
dalam ~1 menit setelah app di-background (Doze/App Standby). Alyn punya `DownloadService`
persis untuk ini.

**Bonus:** `applicationId` kamu masih `ro.alynsampmobile.launcher`
(terlihat dari `ro.alynsampmobile.launcher.fileprovider` &
`ro.alynsampmobile.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
Kalau mau rilis atas nama sendiri, ini perlu dibereskan — termasuk signature key-nya.

---

## 4. Tabel perbandingan

| Aspek | Alyn (v20.7.8) | SanzzClient v1.0.0 |
|---|---|---|
| Stack UI launcher | Flutter (Dart AOT `libapp.so`) | Jetpack Compose (Kotlin) |
| Ukuran APK | 67,0 MB | 39,5 MB |
| Asset di dalam APK | 27 MB (213 file) | 27 MB (213 file) — sama |
| Sumber 1,34 GB | CDN per-file (`/game-files/<path>`) | **tidak ada** |
| Manifest | `GET /api/game-files` (295 entri) | **tidak ada** |
| Bootstrap config | `GET /api/update` | **tidak ada** |
| Basis progress | byte (Σ `size` dari manifest) | jumlah file lokal |
| Resume | `Range` + `.part` | tidak ada |
| Retry / mirror | 5x + jsDelivr↔GitHub fallback | tidak ada |
| Foreground service | `DownloadService` (START/UPDATE/STOP) | **tidak ada** |
| Cek integritas | seluruh manifest + pesan "incomplete" | 5 path hardcode, 2 di antaranya salah |
| Fallback gagal | buka `$webBase/download` | snackbar error saja |
| Import manual | ada (`AlynBridge`: `_display_name`, `_size`) | ada (`archivePickerLauncher`) |

---

## 5. Solusi — kode siap pakai

Ada di folder `kotlin/`, tinggal salin ke `app/src/main/java/com/sanzz/client/`:

| File | Isi |
|---|---|
| `AssetApi.kt` | Data class manifest + `GET /api/update` + `GET /api/game-files` (org.json, tanpa dependency baru) |
| `AssetDownloader.kt` | **Inti perbaikan.** Download per-file, resume `Range`, `.part`→rename, retry 5x + backoff, mirror fallback, progress **byte-based** via `Flow<DownloadEvent>`, throttled 5 Hz |
| `DownloadProgressService.kt` | Foreground service + notifikasi progress (tiruan `DownloadService` Alyn, action di namespace-mu sendiri) |
| `GameInstallerV2.kt` | `installFromNetwork()` + `checkGameIntegrity()` yang benar (semua 295 file, gate 1300 MB, path `texdb/gta3.img` yang betul) |
| `HomeScreenProgress.kt` | Composable progress dengan `animateFloatAsState`, MB/s, ETA, jumlah file, tombol Pause/Resume |
| `AndroidManifest.snippet.xml` | `<service>` + `foregroundServiceType="dataSync"` |

`referensi/manifest-schema.sample.json` = contoh skema manifest persis punya Alyn.

### Urutan pengerjaan yang aku sarankan

1. **Benerin dulu Bug 2** (2 path salah). 5 menit, tanpa ini apa pun yang kamu bangun tetap "Perlu Setup".
2. Bikin backend manifest-mu sendiri: 1 endpoint config + 1 endpoint manifest + static file host.
   Skemanya copy persis dari `referensi/manifest-schema.sample.json`.
3. Masukin `AssetApi.kt` + `AssetDownloader.kt`. Tes dengan manifest kecil dulu (3–5 file) biar cepat iterasi.
4. Masukin `DownloadProgressService.kt` + snippet manifest. **Wajib** sebelum uji 1,34 GB sungguhan.
5. Ganti UI progress ke `HomeScreenProgress.kt`.
6. Terakhir: tambah `sha256` ke manifest-mu sendiri (Alyn tidak punya — ini kelemahan mereka, jangan ditiru).

---

## 6. Dua catatan yang perlu kamu putuskan sendiri

**Soal sumber asset.** Yang 1,34 GB itu isi `textures/`, `texdb/`, `audio/`, `anim/`, `models/` —
asset GTA: San Andreas, hak cipta Rockstar/Take-Two. Alyn meng-host-nya sendiri di CDN mereka.
Kode yang aku tulis pakai `baseUrl` yang configurable, jadi **arahkan ke host-mu sendiri**,
jangan hotlink `alynsampmobile.pro/game-files/...` — selain numpang bandwidth orang,
itu bikin build-mu bergantung total pada server pihak ketiga yang bisa mati kapan saja.
Model yang paling aman secara hukum tetap: user mengimpor data GTA:SA milik mereka sendiri
(jalur `importArchive` yang sudah kamu punya itu justru aset paling berharga di kodemu — pertahankan).

**Soal `applicationId`.** Masih `ro.alynsampmobile.launcher`. Kalau SanzzClient mau berdiri sendiri,
ganti ke `com.sanzz.client` + signing key sendiri, lalu cek ulang semua `FileProvider` authority
dan intent action (`ro.alynsampmobile.download.*` → namespace-mu).
