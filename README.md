# SanzzLauncher — Backend Asset & Downloader

Infrastruktur download asset untuk **Sanzz Client** (launcher SA-MP Mobile).
Jalur **gratis total**: GitHub Releases + jsDelivr. Tanpa server, tanpa kartu kredit.

```
Biaya          $0
Kartu kredit   tidak perlu
Server/VPS     tidak perlu
Database       tidak perlu
```

---

## Dua jalur, dan jalur mana yang sebaiknya utama

| | **A. Impor oleh user** | B. Download otomatis |
|---|---|---|
| Kode | `android/GameFileImporter.kt`, `ImportGameFilesScreen.kt` | `android/AssetDownloader.kt` dkk. |
| Kamu host 1,34 GB | **tidak** | ya |
| Biaya | **$0 selamanya** | $0 s/d ±28rb install/bln, lalu naik |
| Risiko hak cipta untukmu | **tidak ada** | kamu jadi distributor |
| Butuh backend | **tidak** | ya (repo ini) |
| Butuh mirror | **tidak** | ya, wajib |
| Pengalaman user | pilih folder sekali, ±3 menit | tekan sekali, tunggu |

**Jalur A yang sebaiknya jadi utama.** Datanya diambil dari install GTA:SA yang
dimiliki user sendiri — kamu tidak menyimpan, meng-host, atau mendistribusikan
asset berhak cipta sama sekali. Jalur B tetap tersedia sebagai kenyamanan tambahan
kalau nanti kamu memutuskan meng-host asset-nya sendiri.

Keduanya memakai spec yang sama (`backend/game-files-spec.json`) untuk validasi.

---

## Isi repo

| Path | Isi |
|---|---|
| `backend/run-all.sh` | **Satu perintah:** verifikasi → generate → upload → push → uji |
| `backend/verify-game-files.mjs` | Cek folder asset vs spec 295 file sebelum upload |
| `backend/game-files-spec.json` | Daftar 295 path + ukuran yang dibutuhkan (bukan assetnya) |
| `backend/generate-github-manifest.mjs` | Scan `game-files/` → `manifest.json` (+ sha256) & `config.json` |
| `backend/github-release-upload.sh` | Upload 295 asset ke Release, paralel, **idempotent** |
| `backend/docs/SETUP-BACKEND-GRATIS.md` | **← MULAI DARI SINI.** Runbook 10 tahap |
| `backend/docs/OPSI-GRATIS.md` | Perbandingan opsi hosting gratis + hasil uji nyata |
| `backend/docs/BACKEND-KEBUTUHAN.md` | Kebutuhan teknis + hitungan biaya kalau nanti scale |
| `backend/optional-r2/` | Jalur Cloudflare R2, dipakai kalau nanti sudah punya kartu |
| `android/GameFileImporter.kt` | **Jalur A**: impor dari folder/zip + deteksi + validasi + Zip Slip guard |
| `android/ImportGameFilesScreen.kt` | **Jalur A**: layar Compose untuk impor |
| `android/` (6 lainnya) | **Jalur B**: downloader otomatis + foreground service + UI progress |
| `docs/ANALISIS-ALUR-ALYN.md` | Hasil bedah alur download Alyn SA-MP Mobile |
| `game-files/` | **Kosong.** Tempat menaruh asset 1,34 GB (tidak masuk git) |
| `manifest.json` | Diisi oleh generator — 295 entri |
| `config.json` | Bootstrap config + kill switch |

---

## Mulai

**Satu perintah** (setelah `game-files/` terisi):

```bash
cd backend
./run-all.sh ../game-files v1.0.0 8
```

Itu menjalankan: preflight → **verifikasi kelengkapan** → generate manifest →
upload Release → push → uji dari internet (Range 206, jsDelivr, fallback).
Berhenti di tahap mana pun yang gagal, aman dijalankan ulang.

Prasyarat: `git`, `gh` CLI (`gh auth login`), Node 18+, `curl`.

### Kalau mau per tahap

```bash
cd backend
node verify-game-files.mjs ../game-files                                  # ← cek dulu, murah
node generate-github-manifest.mjs ../game-files SanzzLauncher SanzzLauncher v1.0.0 ../manifest.json
mv ../manifest-config.json ../config.json
./github-release-upload.sh SanzzLauncher SanzzLauncher v1.0.0 ../game-files 8
cd .. && git add manifest.json config.json changelog.txt
git commit -m "asset v1.0.0" && git push
```

Panduan lengkap + verifikasi + troubleshooting: **`backend/docs/SETUP-BACKEND-GRATIS.md`**

---

## Jalur A — impor oleh user (tanpa hosting)

### 1. Salin spec ke assets APK

```bash
cp backend/game-files-spec.json <proyek-android>/app/src/main/assets/game-files-spec.json
```

Isinya **hanya 295 path + ukuran byte** (~27 KB) — metadata, bukan asset.
Tanpa file ini, validasi fallback ke 7 path kritis saja.

### 2. Salin 2 file ke proyek

| File | Package |
|---|---|
| `android/GameFileImporter.kt` | `com.sanzz.client.data` |
| `android/ImportGameFilesScreen.kt` | `com.sanzz.client.ui.screens` |

Butuh dependency `androidx.documentfile:documentfile` (untuk SAF tree).

### 3. Pasang layarnya

```kotlin
ImportGameFilesScreen(
    onCompleted = { report ->
        integrityStatus = /* perbarui state dari report */
        snackbar.showText(
            if (report.complete) "Data game lengkap!"
            else "Masih kurang ${report.missing.size} file"
        )
    },
)
```

### Yang dilakukan layar itu

1. **Scan device** — cari lokasi GTA:SA (`Android/data/com.rockstargames.gtasa/files`,
   `Android/obb/…`, storage sekunder, folder umum), lalu laporkan berapa persen
   yang cocok dengan spec.
2. **Pilih folder** lewat SAF (`ACTION_OPEN_DOCUMENT_TREE`) — user tidak perlu
   meng-zip 1,34 GB dulu. Izin persisten diambil supaya impor bisa di-resume.
3. **Pilih .zip** — jalur `importArchive()` lama, tetap didukung.
4. **Salin dengan progress berbasis byte**, skip file yang sudah benar ukurannya,
   tulis lewat `.part` lalu rename.
5. **Validasi 295 file** → tampilkan persis grup mana yang masih kurang dan berapa MB.

Perbaikan keamanan di jalur zip: **proteksi Zip Slip**. Entri bernama
`../../something` ditolak, kalau tidak arsip jahat bisa menulis di luar folder game.
`importArchive()` yang lama tidak punya ini.

### Batasan yang perlu kamu tahu

Android 11+ membatasi akses langsung ke `Android/data/` package lain, jadi
deteksi otomatis bisa melaporkan `accessible = false` walaupun folder-nya ada.
Itu sebabnya picker SAF tetap jadi jalur yang selalu jalan — jangan dihapus.

---

## Cek kelengkapan asset dulu, sebelum upload 1,34 GB

```bash
node backend/verify-game-files.mjs ./game-files
node backend/verify-game-files.mjs ./game-files --list    # tampilkan semua yang kurang
node backend/verify-game-files.mjs ./game-files --json     # untuk scripting
```

Membandingkan foldermu dengan **`backend/game-files-spec.json`** — daftar 295 path
+ ukuran byte yang dibutuhkan client. File spec itu **hanya nama dan ukuran**, tidak
berisi asset apa pun, jadi aman di-commit.

Yang dilaporkan: file yang kurang (diurutkan dari terbesar), file yang ukurannya beda,
file ekstra, ringkasan per grup, progress bar byte, dan **deteksi otomatis dua path
yang paling sering salah** (`models/gta3.img` dan `texdb/gta3.txt`).

Exit code `0` = siap upload, `1` = belum lengkap. `run-all.sh` memakai ini sebagai gerbang.

---

## Yang sudah di-hardcode untuk repo ini

`android/GameInstallerV2.kt` sudah diisi, tidak perlu diubah lagi:

```kotlin
private const val GH_USER = "SanzzLauncher"
private const val GH_REPO = "SanzzLauncher"
// → config  : https://cdn.jsdelivr.net/gh/SanzzLauncher/SanzzLauncher@main/config.json
// → manifest: https://cdn.jsdelivr.net/gh/SanzzLauncher/SanzzLauncher@main/manifest.json
// → fallback: https://raw.githubusercontent.com/SanzzLauncher/SanzzLauncher/main/manifest.json
```

---

## ⚠️ Asset game tidak termasuk

Repo ini berisi **kode dan tooling**. Folder `game-files/` sengaja kosong dan
di-gitignore — isinya ±1,34 GB (295 file) asset GTA: San Andreas, hak cipta
Rockstar Games / Take-Two Interactive.

Kamu harus menyediakan sendiri file-file itu dari sumber yang kamu punya haknya,
lalu menjalankan langkah 2–5 di atas. Tooling-nya sudah siap dan sudah diuji;
yang belum ada hanya isi foldernya.

Jalur paling aman secara hukum tetap **import manual** (`importArchive()` di
`GameInstaller.kt`): user mengimpor data GTA:SA milik mereka sendiri.

---

## ⚠️ Mirror itu wajib

Repo publik berisi asset berhak cipta bisa kena DMCA kapan saja. Kalau itu terjadi,
**semua client mati serentak**. Bikin repo kedua dan isi:

```kotlin
private val mirrorRewrites = listOf(
    "$GH_USER/$GH_REPO" to "$GH_USER/SanzzLauncher-mirror",
)
```

`AssetDownloader` otomatis pindah ke mirror kalau sumber utama gagal.

---

## Status pengujian

### Jalur A (impor)

| Uji | Hasil |
|---|---|
| Spec 295 entri vs asset APK SanzzClient | ✅ 4 cocok · 6 ukuran beda · 285 kurang |
| Spec vs asset APK Alyn | ✅ 4 cocok · 291 kurang (0,26%) |
| Deteksi jebakan path (`models/gta3.img`) | ✅ terdeteksi + pesan koreksi |
| `run-all.sh` gerbang verifikasi | ✅ berhenti, exit 1 |

Belum diuji: menjalankan layar Compose-nya di device (proyek Android-nya belum
ada di repo ini — file-nya ditulis untuk disalin ke proyek Compose yang sudah ada).

### Jalur B (download otomatis)

Tooling di repo ini sudah diuji, bukan cuma ditulis:

| Uji | Hasil |
|---|---|
| Generator: sha256 vs `sha256sum` independen | ✅ cocok semua |
| Generator: nama file berspasi → URL | ✅ disanitasi, tidak ada karakter ilegal |
| Generator: tabrakan nama (`a b.img` vs `a_b.img`) | ✅ diberi akhiran hash path |
| Upload: idempotent (run ulang tanpa perubahan) | ✅ `skip N · upload 0` |
| Upload: manifest basi (asset diedit) | ✅ berhenti, exit 1, tampilkan perintah regenerate |
| Upload: 1 file berubah setelah regenerate | ✅ upload 1 file itu saja |
| jsDelivr: branch / tag / tanpa ref | ✅ HTTP 200 semua |
| GitHub Release: `accept-ranges` | ✅ `bytes` |
| GitHub Release: Range tengah file 116 MB | ✅ HTTP 206 |
| **Di repo ini**: upload 3 asset via API | ✅ HTTP 201, ukuran cocok semua |
| **Di repo ini**: `Range: bytes=2500000-` | ✅ HTTP 206 + `Content-Range` benar |
| **Di repo ini**: unduh penuh lalu cek sha256 | ✅ cocok dengan manifest |
| **Di repo ini**: potong 2 bagian → gabung | ✅ identik byte-per-byte dengan unduhan utuh |
| **Di repo ini**: jsDelivr menyajikan file repo | ✅ HTTP 200 |
| **Di repo ini**: raw.githubusercontent (fallback) | ✅ HTTP 200 |

### Jebakan yang ketemu dari uji nyata

Asset release GitHub disajikan lewat **Fastly**, dan Fastly menolak *suffix range*:

```
Range: bytes=-500      → HTTP 501 "Unsupported client range" (Error 54113)
Range: bytes=2500000-  → HTTP 206 ✅   ← ini yang dipakai AssetDownloader
```

Sebagai pembanding, server Alyn mendukung keduanya (`bytes=-500` → 206).

Dampaknya sudah ditangani: `AssetDownloader` hanya mengirim bentuk `bytes=N-`, dan
`isRetryableStatus()` kini mengecualikan 501/505 supaya tidak membakar 5 percobaan
retry untuk error yang tidak akan pernah sembuh.

### Temuan dari menjalankan verifier terhadap APK SanzzClient v1.0.0

Asset yang dibundel di dalam APK (27 MB / 213 file) dibandingkan dengan spec 295 file:

```
✓ cocok persis        4 file ·    3,6 MB     ← data/fonts.dat, data/ShopUVs.dat, dll
✗ ukuran beda         6 file ·    0,1 MB     ← SEMUA file .cfg kontrol sentuh
✗ tidak ada         285 file · 1372,5 MB     ← inilah yang harus di-download
! ekstra (bundel)   203 file                 ← Text/*.gxt, Textures/, monetloader/, dexopt/
```

**6 file `.cfg` itu perlu keputusan.** Yang dibundel di APK ukurannya beda dari yang
ada di manifest:

```
data/TouchDefaultPhone3x2.cfg          APK=10977   manifest=10912
data/TouchDefaultPhoneWidescreen.cfg   APK=10991   manifest=10924
data/TouchDefaultTablet4x3.cfg         APK=10992   manifest=10926
data/TouchDefaultTabletWidescreen.cfg  APK=10990   manifest=10923
data/360Default960x720.cfg             APK=10897   manifest=10888
data/360Default1280x720.cfg            APK=10907   manifest=10898
```

Itu layout kontrol sentuh. Karena ukurannya tidak cocok, `AssetDownloader` akan
mengunduh dan **menimpa** versi yang dibundel. Kalau setelah download kontrol terasa
aneh, ini sebabnya. Dua pilihan:

- **Biarkan tertimpa** (perilaku default, sama seperti Alyn) — tidak perlu apa-apa.
- **Pertahankan versi APK**: buang 6 entri itu dari manifest setelah generate:
  ```bash
  node -e "const f='manifest.json',d=require('./'+f),x=new Set(['data/TouchDefaultPhone3x2.cfg','data/TouchDefaultPhoneWidescreen.cfg','data/TouchDefaultTablet4x3.cfg','data/TouchDefaultTabletWidescreen.cfg','data/360Default960x720.cfg','data/360Default1280x720.cfg']);d.files=d.files.filter(e=>!x.has(e.path));d.total_files=d.files.length;d.total_bytes=String(d.files.reduce((a,e)=>a+ +e.size,0));require('fs').writeFileSync(f,JSON.stringify(d))"
  ```

### Yang belum bisa diuji dari sini

- Upload 295 file asset sungguhan — isinya tidak ada di repo ini, lihat bagian
  "Asset game tidak termasuk" di atas.
- Build APK — proyek Android-nya belum ada di repo. File di `android/` dirancang
  untuk disalin ke proyek Compose yang sudah ada.
- `github-release-upload.sh` end-to-end — butuh `gh` CLI. Sudah diuji terhadap stub
  `gh` untuk 4 skenario (lihat `backend/docs/SETUP-BACKEND-GRATIS.md`); jalur API
  yang dipakai `gh` di balik layar sudah diverifikasi langsung di repo ini.
