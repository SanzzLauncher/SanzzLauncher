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

```bash
# 1. Install (sekali saja)
gh auth login
#    butuh: git, gh CLI, Node.js 18+

# 2. Taruh asset di game-files/   ← lihat game-files/README.md
#    Struktur folder HARUS persis: texdb/gta3.img, textures/game.img, dst.

# 3. Generate manifest
cd backend
node generate-github-manifest.mjs ../game-files SanzzLauncher SanzzLauncher v1.0.0 ../manifest.json
mv ../manifest-config.json ../config.json

# 4. Upload ke Release (idempotent — aman diulang kalau koneksi putus)
./github-release-upload.sh SanzzLauncher SanzzLauncher v1.0.0 ../game-files 8

# 5. Push manifest supaya bisa disajikan jsDelivr
cd ..
git add manifest.json config.json changelog.txt
git commit -m "asset v1.0.0" && git push
```

Panduan lengkap + verifikasi + troubleshooting: **`backend/docs/SETUP-BACKEND-GRATIS.md`**

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

### Yang belum bisa diuji dari sini

- Upload 295 file asset sungguhan — isinya tidak ada di repo ini, lihat bagian
  "Asset game tidak termasuk" di atas.
- Build APK — proyek Android-nya belum ada di repo. File di `android/` dirancang
  untuk disalin ke proyek Compose yang sudah ada.
- `github-release-upload.sh` end-to-end — butuh `gh` CLI. Sudah diuji terhadap stub
  `gh` untuk 4 skenario (lihat `backend/docs/SETUP-BACKEND-GRATIS.md`); jalur API
  yang dipakai `gh` di balik layar sudah diverifikasi langsung di repo ini.
