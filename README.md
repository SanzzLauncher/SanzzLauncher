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
| `android/` | 6 file Kotlin siap salin ke proyek Android |
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
