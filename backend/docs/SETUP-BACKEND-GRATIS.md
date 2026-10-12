# Runbook: Backend Asset GRATIS (GitHub Releases + jsDelivr)

> Jalur yang aku rekomendasikan. **Biaya $0, tanpa kartu kredit, tanpa server.**
> Semua perintah di bawah sudah diuji. Ikuti berurutan, jangan lompat.
>
> Yang dibangun:
> ```
>   repo GitHub (publik)
>   ├── manifest.json      295 entri + sha256   → disajikan jsDelivr (CDN gratis)
>   ├── config.json        bootstrap + kill switch → disajikan jsDelivr
>   ├── changelog.txt      opsional
>   └── Release v1.0.0
>       └── 295 asset      1.376,2 MB           → diunduh client, Range/resume OK
> ```
> Tidak ada database, tidak ada Worker, tidak ada VPS.

---

## TAHAP 0 — Yang kamu butuhkan

Centang semua sebelum mulai:

- [ ] **Akun GitHub** (gratis) — daftar di github.com
- [ ] **Git** — `git --version`
- [ ] **gh CLI** — GitHub CLI resmi, untuk upload release
- [ ] **Node.js 18+** — `node -v` (untuk generator manifest)
- [ ] **curl** — `curl --version` (untuk verifikasi)
- [ ] **Ruang disk ±3 GB** — 1,34 GB asset + ruang kerja
- [ ] **Folder asset 295 file (1,34 GB)** — struktur foldernya lihat Tahap 3
- [ ] **Repo kedua untuk mirror** — boleh akun yang sama. *Jangan dilewati*, lihat Tahap 10.

Tidak butuh: domain, kartu kredit, server, database, Cloudflare.

---

## TAHAP 1 — Install tools

**Windows (winget):**
```powershell
winget install --id GitHub.cli
winget install --id OpenJS.NodeJS.LTS
winget install --id Git.Git
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt update && sudo apt install -y git curl nodejs npm
(type -p wget >/dev/null || sudo apt install wget -y) \
  && sudo mkdir -p -m 755 /etc/apt/keyrings \
  && wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg \
     | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
     | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
  && sudo apt update && sudo apt install gh -y
```

**macOS:**
```bash
brew install gh git node
```

**Lalu login:**
```bash
gh auth login
#  ? What account?   GitHub.com
#  ? Protocol?       HTTPS
#  ? Authenticate?   Login with a web browser   → salin kode 8 karakter
gh auth status        # harus muncul "Logged in to github.com as <username>"
```

---

## TAHAP 2 — Bikin repo

```bash
gh repo create sanzz-assets --public --description "Asset data game untuk SanzzClient"
cd sanzz-assets
git checkout -b main 2>/dev/null || git branch -M main
```

⚠️ **Harus public.** Asset release di repo private tidak bisa diunduh tanpa token,
dan client-mu tidak boleh menyimpan token.

Catat nama lengkapnya: `<USERNAME>/sanzz-assets` — dipakai di semua tahap berikutnya.

---

## TAHAP 3 — Susun folder asset

Struktur folder = `path` di manifest = lokasi simpan di HP. **Harus persis.**

```
game-files/
├── SAMP/          (SAMP.ide, TIMECYC.DAT, WEAPON.dat, …)
├── anim/          (cuts.img, ped.ifp, …)
├── audio/         (CONFIG/, STREAMS/, …)
├── data/          (fonts.dat, ShopUVs.dat, maps/, script/mainV1.scm, …)
├── fonts/
├── models/        (coll/peds.col, effects.fxp, …)
├── texdb/         (gta3.img ← 270 MB, gta_int.img, samp.img, gta3/gta3.dxt.dat, …)
├── textures/      (game.img ← 270 MB, game.bin ← 223 MB, multiplayer.img, …)
└── stream.ini
```

Acuan ukuran per grup (dari manifest Alyn, total 1.376,2 MB):

| Grup | Ukuran | | Grup | Ukuran |
|---|---|---|---|---|
| `textures/` | 655,9 MB | | `data/` | 19,6 MB |
| `texdb/` | 607,0 MB | | `SAMP/` | 2,9 MB |
| `audio/` | 62,6 MB | | `models/` | 0,6 MB |
| `anim/` | 27,1 MB | | `fonts/` | 0,4 MB |

**Dua path yang wajib ada** — ini yang bikin `checkGameIntegrity` lama selalu gagal:
```
texdb/gta3.img          270,39 MB     ← BUKAN models/gta3.img
texdb/gta3/gta3.txt       0,76 MB     ← BUKAN texdb/gta3.txt
```

---

## TAHAP 4 — DRY RUN dulu (jangan dilewati!)

**Jangan langsung upload 1,34 GB.** Bikin folder uji 3 file kecil, pastikan seluruh alur
benar, baru naik. Iterasi 10 detik vs 40 menit tiap kali ada yang salah.

```bash
mkdir -p uji/data uji/texdb
head -c 200000 /dev/urandom > uji/texdb/gta3.img     # 200 KB, pura-pura jadi file besar
echo "test" > uji/data/fonts.dat
echo "test2" > uji/data/ShopUVs.dat
```

Salin dua tool dari folder ini ke repo-mu:
```bash
cp .../backend/generate-github-manifest.mjs .
cp .../backend/github-release-upload.sh .
chmod +x github-release-upload.sh
```

Lalu jalankan Tahap 5–8 dengan `./uji` sebagai folder asset. Kalau semuanya hijau,
ulangi dengan `./game-files` yang sesungguhnya.

---

## TAHAP 5 — Generate manifest + config

```bash
node generate-github-manifest.mjs ./game-files <USERNAME> sanzz-assets v1.0.0 ./manifest.json
```

Hasilnya 3 file:

| File | Isi |
|---|---|
| `manifest.json` | 295 entri: `name`, `size`, `path`, `url`, `sha256` |
| `manifest-assets.tsv` | daftar upload (dibaca script Tahap 6) |
| `manifest-config.json` | bootstrap config + kill switch |

Output yang benar kurang lebih:
```
✓ 295 file · 1376.2 MB (1.344 GB)
✓ manifest      → ./manifest.json
✓ daftar upload → ./manifest-assets.tsv
✓ config.json   → ./manifest-config.json   (pengganti /api/update)
✓ base release  → https://github.com/<USERNAME>/sanzz-assets/releases/tag/v1.0.0
```

Pindahkan ke nama final:
```bash
mv manifest-config.json config.json
echo "* Rilis pertama." > changelog.txt
```

**Kalau muncul `✗ File ini melewati batas 2 GB`** → pecah file itu, GitHub tidak terima.
**Kalau muncul `! N nama asset bertabrakan`** → normal, generator sudah memberi akhiran hash otomatis.

---

## TAHAP 6 — Upload ke GitHub Release

```bash
./github-release-upload.sh <USERNAME> sanzz-assets v1.0.0 ./game-files 8
```

Angka terakhir = jumlah upload paralel. Pakai `8` untuk 295 file; turunkan ke `4`
kalau koneksi upload-mu kecil.

Script ini **idempotent** — sudah aku uji 4 skenario:

| Kondisi | Perilaku |
|---|---|
| Pertama kali | upload semua, verifikasi `295 / 295 asset lengkap ✅` |
| Dijalankan ulang tanpa perubahan | `skip 295 (sudah lengkap) · upload 0` |
| 1 file diubah, manifest belum di-regenerate | **berhenti, exit 1**, tampilkan perintah regenerate |
| Setelah regenerate | upload **1 file itu saja** |

Jadi kalau koneksi putus di file ke-140, jalankan ulang perintah yang sama — lanjut dari situ.

Akhirnya ada uji otomatis yang menentukan:
```
✓ Range test → HTTP 206 (resume jalan)  [texdb__gta3.img]
```
**Kalau ini bukan 206, jangan lanjut ke Tahap 9** — resume tidak akan berfungsi.

---

## TAHAP 7 — Taruh manifest & config di repo

```bash
git add manifest.json config.json changelog.txt
git commit -m "asset manifest v1.0.0 (295 file, 1376 MB)"
git branch -M main
git remote add origin https://github.com/<USERNAME>/sanzz-assets.git 2>/dev/null || true
git push -u origin main
```

`manifest.json` ~40 KB, `config.json` ~1 KB — repo tetap kecil walaupun assetnya 1,34 GB,
karena asset hidup di Release, bukan di git.

---

## TAHAP 8 — Verifikasi dari internet

Jalankan lima cek ini. Semua harus hijau sebelum menyentuh kode Android.

```bash
U=<USERNAME>; R=sanzz-assets; T=v1.0.0

# 1. config.json lewat jsDelivr
curl -s "https://cdn.jsdelivr.net/gh/$U/$R@main/config.json" | head -c 300; echo
#    → harus ada "game_files" dan "status":true

# 2. manifest.json lewat jsDelivr
curl -s "https://cdn.jsdelivr.net/gh/$U/$R@main/manifest.json" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
print('file:',d['total_files'],'| bytes:',d['total_bytes'])"
#    → file: 295 | bytes: 1443047957   (1376,2 MB — angka persis tergantung asset-mu)

# 3. cadangan raw.githubusercontent
curl -s -o /dev/null -w "raw: HTTP %{http_code}\n" \
  "https://raw.githubusercontent.com/$U/$R/main/manifest.json"

# 4. RANGE REQUEST — INI YANG PALING MENENTUKAN
BIG=$(python3 -c "import json;d=json.load(open('manifest.json'));\
print(sorted(d['files'],key=lambda f:-int(f['size']))[0]['url'])")
echo "uji: $BIG"
curl -s -o /dev/null -w "  HTTP %{http_code}  bytes %{size_download}\n" -r 1000-1999 -L "$BIG"
#    → HARUS HTTP 206  bytes 1000

# 5. resume dari titik tengah file besar (bukan dari awal)
curl -s -o /dev/null -w "  mid-file: HTTP %{http_code}\n" -r 100000000-100000999 -L "$BIG"
#    → HARUS HTTP 206
```

Catatan:
- jsDelivr butuh beberapa menit untuk cache pertama kali. Kalau cek #1 masih 404,
  tunggu 2–5 menit atau pakai URL raw (#3) sementara.
- `@main` di URL jsDelivr **harus** sama dengan nama branch-mu. Aku sudah verifikasi
  pola ini jalan untuk branch (`@main`, `@master`), tag (`@v1.5.1`), dan tanpa ref
  (otomatis pakai default) — semuanya HTTP 200. Tahap 2 & 7 sudah memaksa branch
  bernama `main` lewat `git branch -M main`, jadi konsisten.
- Kalau branch-mu ternyata `master`, ganti `JSDELIVR` di `GameInstallerV2.kt`
  dari `@main` ke `@master`.

---

## TAHAP 9 — Sambungkan ke Android

### 9.1 Isi username-mu

`kotlin/GameInstallerV2.kt`, bagian **KONFIGURASI BACKEND**:
```kotlin
private const val GH_USER = "USERNAME-KAMU"     // ← ganti ini saja
private const val GH_REPO = "sanzz-assets"
```
Sisanya (`JSDELIVR`, `RAW_GH`, `CONFIG_URL`, `ASSET_BASE_URL`) otomatis mengikuti.

### 9.2 Salin 5 file Kotlin

Ke `app/src/main/java/com/sanzz/client/`:

| File | Ke package |
|---|---|
| `AssetApi.kt` | `com.sanzz.client.data.download` |
| `AssetDownloader.kt` | `com.sanzz.client.data.download` |
| `DownloadProgressService.kt` | `com.sanzz.client.data.download` |
| `GameInstallerV2.kt` | `com.sanzz.client.data` |
| `HomeScreenProgress.kt` | `com.sanzz.client.ui.screens` |

### 9.3 Daftarkan service

`AndroidManifest.xml` → di dalam `<application>` (detail di `kotlin/AndroidManifest.snippet.xml`):
```xml
<service
    android:name="com.sanzz.client.data.download.DownloadProgressService"
    android:exported="false"
    android:foregroundServiceType="dataSync"
    android:stopWithTask="false" />
```
Permission-nya **sudah ada semua** di manifest-mu — tidak perlu tambah apa pun.

### 9.4 Perbaiki 2 path di integrity check

`GameInstallerV2.REQUIRED_FILES` sudah benar. Tapi kalau kamu masih pakai
`GameInstaller.checkGameIntegrity()` yang lama, ganti dua string ini:
```
models/gta3.img   →  texdb/gta3.img
texdb/gta3.txt    →  texdb/gta3/gta3.txt
```

### 9.5 Ganti handler tombol

`MainActivity`: `onInstallAssets` yang sekarang memanggil `extractBundledAssets`
→ arahkan ke `GameInstallerV2.launchInstall(...)` (contoh lengkap ada di komentar
bawah `HomeScreenProgress.kt`).

### 9.6 Minta POST_NOTIFICATIONS

Android 13+ : minta sebelum `DownloadProgressService.start()`, kalau tidak
notifikasinya tidak muncul.

---

## TAHAP 10 — Mirror (wajib, bukan opsional)

Repo publik berisi asset berhak cipta Rockstar bisa kena DMCA kapan saja.
Begitu kena, **semua client-mu mati serentak**.

Bikin repo kedua (`sanzz-assets-mirror`), upload asset yang sama, lalu isi di
`GameInstallerV2.kt`:
```kotlin
private val mirrorRewrites = listOf(
    "$GH_USER/$GH_REPO" to "$GH_USER/sanzz-assets-mirror",
)
```
`AssetDownloader` otomatis coba mirror kalau sumber utama gagal. Biaya: $0.

Kalau nanti punya kartu, tambahkan R2 sebagai mirror ketiga — pola URL-nya beda,
jadi isi lewat lambda `mirrors` yang memetakan `path` ke URL R2.

---

## Rilis update asset

```bash
# 1. ubah file di ./game-files/
# 2. regenerate (WAJIB — sha256 & size harus cocok)
node generate-github-manifest.mjs ./game-files <U> sanzz-assets v1.0.1 ./manifest.json
mv manifest-config.json config.json
# 3. upload ke tag baru
./github-release-upload.sh <U> sanzz-assets v1.0.1 ./game-files 8
# 4. push manifest
git add manifest.json config.json && git commit -m "asset v1.0.1" && git push
```

Client lama tetap jalan (tag `v1.0.0` masih ada). Client baru ambil `v1.0.1`.
**APK tidak perlu dirilis ulang** — itu gunanya `config.json`.

**Rollback:** edit `config.json` → kembalikan URL manifest ke versi lama, atau
set `"status": false` untuk menghentikan semua client (kill switch saat darurat).

---

## Troubleshooting

| Gejala | Sebab | Perbaikan |
|---|---|---|
| `Range test → HTTP 404` | Tag salah / asset belum ter-upload | `gh release view v1.0.0 -R <U>/sanzz-assets` |
| `Range test → HTTP 200` (bukan 206) | URL redirect tidak diikuti | Pastikan `-L` di curl; di client pastikan `followRedirects(true)` (sudah default) |
| jsDelivr 404 terus | Cache pertama butuh waktu, atau repo private | Tunggu 5 menit; cek repo benar-benar public |
| `✗ MANIFEST BASI` | Asset diedit setelah generate manifest | Regenerate (perintahnya sudah ditampilkan script) |
| Upload gagal massal | Rate limit GitHub | Turunkan paralel ke `2`, jalankan ulang — yang sudah ada di-skip |
| Progress bar loncat | Masih pakai callback lama per-file | Pastikan pakai `AssetDownloader.stream()`, bukan `extractBundledAssets` |
| Download mati saat app di-background | Service belum terdaftar | Cek Tahap 9.3 |
| `totalSizeMB` kecil tapi status "siap" | Gate 1 MiB dari kode lama | Pakai `GameInstallerV2.checkIntegrity()` |

---

## Checklist akhir

```
[ ] gh auth status → logged in
[ ] repo publik <USERNAME>/sanzz-assets dibuat
[ ] 295 file di ./game-files dengan struktur path benar
[ ] texdb/gta3.img dan texdb/gta3/gta3.txt ADA (bukan path lama yang salah)
[ ] manifest.json: 295 entri, total_bytes = 1443047957 (1376,2 MB)
[ ] config.json: "status": true
[ ] release v1.0.0: 295 asset, semua ukuran cocok
[ ] Range test → HTTP 206  ← penentu resume
[ ] jsDelivr menyajikan manifest.json & config.json
[ ] GH_USER diisi di GameInstallerV2.kt
[ ] <service> terdaftar di AndroidManifest.xml
[ ] mirror kedua sudah di-upload
[ ] DRY RUN 3 file lolos sebelum naik ke 1,34 GB
```

---

## Soal isi asset-nya

Satu kali saja aku ingatkan, sisanya keputusanmu: 1,34 GB itu asset GTA: San Andreas,
hak cipta Rockstar/Take-Two. Meng-upload-nya ke repo GitHub publik membuatmu jadi
distributor, bukan cuma pembuat launcher — dan GitHub menghormati DMCA.

Jalur paling defensibel tetap `importArchive()` yang sudah ada di kodemu: user mengimpor
data GTA:SA milik mereka sendiri, launcher-mu menyediakan engine + SA-MP + UI.
Pertahankan itu sebagai jalur utama, dan perlakukan download otomatis sebagai kemudahan
tambahan.
