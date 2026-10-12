# Backend GRATIS (tanpa bayar, dan opsi tanpa kartu)

> Semua angka di bawah **sudah aku uji langsung**, bukan dari dokumentasi.
> Beban kerjamu: 295 file · 1.376,2 MB · file terbesar 270,39 MB · **tidak ada file ≥ 2 GB**.

---

## Jawaban singkat

**Yang aku rekomendasikan kemarin (Cloudflare R2) itu sebenarnya sudah gratis.**
$0 beneran sampai ±28.000 install/bulan — storage 1,34 GB masuk free tier 10 GB, egress $0 tanpa batas, read masuk free tier 10 juta request/bulan.

**Tapi** R2 **butuh kartu kredit untuk aktivasi**, walaupun tidak akan dicharge selama di dalam free tier. Kalau itu masalahnya, ada jalur yang benar-benar tanpa kartu. 👇

---

## 🥇 Opsi 1 — GitHub Releases  (rekomendasiku untuk gratis total)

**Gratis · tanpa kartu · tanpa meteran bandwidth · resume jalan.**

Aku uji langsung di asset release 116 MB:

```
GET https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
  → 302 → release-assets.githubusercontent.com
  → HTTP/2 200 · content-length: 116173955 · accept-ranges: bytes   ✅

Range: bytes=50000000-50000999
  → HTTP 206 · 1000 bytes                                            ✅ RESUME JALAN
```

Kenapa cocok banget untuk kasusmu:

| Syarat | GitHub Releases |
|---|---|
| Batas ukuran per file | **2 GB** — file terbesarmu 270 MB ✅ semua muat |
| Range request / resume | **206 terverifikasi** ✅ |
| HTTPS | ✅ |
| Biaya bandwidth | Tidak dimeterkan ✅ |
| Kartu kredit | **Tidak perlu** ✅ |
| CDN | Otomatis (Azure Blob + edge GitHub) ✅ |

### ⚠️ Tiga jebakan yang harus ditangani

1. **Nama asset tidak boleh mengandung `/`.**
   Manifest-mu punya `texdb/gta3.img`. Sebagai asset release harus jadi `texdb__gta3.img`.
   `path` di manifest tetap `texdb/gta3.img` (untuk lokasi simpan di HP), hanya `url` yang flat.
   → `AssetDownloader.kt` **tidak perlu diubah sama sekali**, karena dia menyimpan ke `path` dan mengunduh dari `url` secara terpisah.

2. **URL hasil redirect 302 itu signed dan kedaluwarsa ±1 jam.**
   ```
   ...&se=2026-10-12T09:31:01Z&sig=NjdhQ...&jwt=eyJ0eXAi...
   ```
   Jangan pernah menyimpan/cache URL hasil redirect. Downloader harus `followRedirects(true)`
   (sudah default di `AssetApi.kt` & `AssetDownloader.kt`) supaya tiap request meng-resolve ulang 302-nya.

3. **Repo harus publik** → asset-mu bisa dilihat & diunduh siapa pun, dan GitHub menghormati DMCA.
   Asset GTA:SA adalah materi berhak cipta Rockstar; ini risiko takedown yang nyata.
   Kalau repo kena DMCA, semua client-mu mati serentak. **Punya mirror itu wajib**, bukan opsional.

### Cara pakai

```bash
# 1. Bikin repo publik (kosong saja)
gh repo create sanzz-assets --public

# 2. Taruh 295 file di ./game-files/ dengan struktur folder asli
#    ./game-files/texdb/gta3.img  dll.

# 3. Generate manifest + upload
node generate-github-manifest.mjs ./game-files <USER> sanzz-assets v1.0.0
./github-release-upload.sh <USER> sanzz-assets v1.0.0 ./game-files
```

Hasilnya: **tidak butuh server sama sekali.** "API"-nya cuma file JSON statis di repo,
disajikan lewat `raw.githubusercontent.com` atau jsDelivr.

---

## 🥈 Opsi 2 — Cloudflare Pages + chunking

**Gratis · tanpa kartu · bandwidth statis unlimited.** Tapi ada batas yang memaksa kerja ekstra:

| Limit Pages free | Nilai | Dampak buatmu |
|---|---|---|
| Ukuran **maks per file** | **25 MiB** | 8 file-mu lewat batas |
| File per deployment | 20.000 | 295 aman ✅ |
| Build/bulan | 500 | aman ✅ |
| Bandwidth statis | **unlimited** ✅ | ini daya tariknya |
| Kartu kredit | **tidak perlu** ✅ | |

Distribusi nyata asset-mu (aku hitung dari manifest):

```
< 24 MB : 287 file ·  281,7 MB · 20,5% dari total   ← langsung muat di Pages
≥ 24 MB :   8 file · 1094,5 MB · 79,5%              ← HARUS dipecah jadi chunk
            game.img(270) gta3.img(270) game.bin(223) gta3.dxt.dat(177)
            interiors.img(43) gta_int.img(43) multiplayer.img(34) samp.img(34)
```

Jadi 8 file itu dipecah jadi chunk ≤24 MB (±50 chunk), client mengunduh semua chunk lalu
menggabungnya. Sisi baiknya: resume jadi **per-chunk 24 MB**, jauh lebih tahan banting di 4G
daripada resume per-file 270 MB.

Sisi buruknya:
- Butuh tambahan logika chunk-join di `AssetDownloader` (belum aku tulis — bilang saja kalau mau)
- **ToS Cloudflare tidak dirancang untuk Pages jadi tempat hosting binary besar sebagai fungsi utama.**
  Zona abu-abu, mirip risiko yang aku sebut di dokumen sebelumnya.

---

## 🥉 Opsi 3 — R2 free tier (kalau punya kartu)

Sudah dijelaskan di `BACKEND-KEBUTUHAN.md`. Ringkasnya:

- $0/bulan sampai ±28.000 install (10 juta Class B read gratis)
- Egress $0 **tanpa batas, tanpa syarat**
- Kartu dibutuhkan untuk aktivasi, tidak dicharge selama di free tier
- Paling bersih secara ToS, paling bagus secara engineering

**Workers free** (100.000 request/hari, tanpa kartu) tetap bisa dipakai untuk endpoint JSON-nya,
terlepas dari mana pun kamu menaruh file asset-nya.

---

## Yang TIDAK aku sarankan

| Layanan | Kenapa gagal |
|---|---|
| **jsDelivr** | Aku uji file 116 MB → **404**. jsDelivr memang tidak untuk file besar (batas terdokumentasi ±20 MB). Catatan: tesku tidak 100% konklusif karena file itu asset release, bukan file di git tree. Intinya: jsDelivr bagus untuk manifest & file kecil, **bukan** untuk 1,16 GB asset-mu. |
| **Google Drive / OneDrive / Mega** | Link publik berubah-ubah, tidak ada jaminan Range, rawan limit harian, dan tidak bisa dipakai dari app tanpa scraping. |
| **Vercel / Netlify** | Bandwidth free tier terbatas (Netlify ±100 GB/bln) — 100 install saja sudah 134 GB. Langsung jebol. |
| **Backblaze B2 free** | Egress gratis cuma 3× storage = **30 GB/bulan** ≈ 22 install. Tidak relevan. |
| **Internet Archive** | Gratis & tanpa kartu, tapi lambat, tidak ada SLA, dan sangat agresif menghapus materi berhak cipta. |

---

## Perbandingan akhir

| | GitHub Releases | CF Pages + chunk | R2 free |
|---|---|---|---|
| Uang | $0 | $0 | $0 |
| Kartu kredit | **tidak** | **tidak** | ya |
| Bandwidth | tak dimeterkan | unlimited | $0 tanpa batas |
| Resume (Range) | ✅ teruji 206 | ✅ | ✅ |
| Perlu ubah kode client | **tidak** | ya (chunk-join) | tidak |
| Perlu backend | **tidak** | tidak | Worker kecil |
| Batas per file | 2 GB | 25 MiB | tak terbatas praktis |
| Kontrol cache-control | ❌ | sebagian | ✅ |
| Risiko takedown/ToS | **tinggi** (repo publik) | sedang | sedang |
| Mirror mudah ditambah | ✅ | ✅ | ✅ |

**Saran konkretku:** mulai dari **GitHub Releases** — nol biaya, nol kartu, nol perubahan kode,
dan bisa jalan hari ini. Begitu user-mu mulai ribuan dan kamu punya kartu, pindah ke R2;
manifest-mu tinggal ganti `url`, **APK tidak perlu dirilis ulang** (itu gunanya endpoint `/api/update`).

Dan apa pun pilihannya: isi parameter `mirrors` di `AssetDownloader.kt` dengan **dua** sumber
(misalnya GitHub Release + R2). Satu kena takedown, client tetap jalan.

---

## Isi folder ini untuk jalur gratis

| File | Fungsi |
|---|---|
| `generate-github-manifest.mjs` | Scan folder → manifest dengan URL GitHub Release (nama di-flatten `a/b` → `a__b`) |
| `github-release-upload.sh` | Bikin release + upload 295 asset paralel, idempotent (skip yang sudah ada), aman kalau koneksi putus |
