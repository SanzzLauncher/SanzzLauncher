# Backend untuk Download Asset 1,34 GB — Kebutuhan & Biaya

> Pendamping `ANALISIS-ALUR-ALYN.md`. Angka harga diverifikasi Oktober 2026.
>
> **Mau yang nol rupiah dan tanpa kartu kredit?** Baca `OPSI-GRATIS.md` dulu —
> dokumen ini mengasumsikan jalur berbayar/R2, sedangkan jalur GitHub Releases
> di sana bisa jalan hari ini tanpa keluar uang sama sekali.

---

## 1. Enam hal yang WAJIB ada

| # | Kebutuhan | Kenapa tidak bisa ditawar |
|---|---|---|
| 1 | **Static/object host yang support HTTP Range** (`accept-ranges: bytes`) | Tanpa ini resume mati. File 270 MB yang putus di 90% diulang dari nol. Di HP Indonesia (4G naik-turun) ini bikin download tidak pernah selesai. |
| 2 | **Endpoint manifest** `/api/game-files` | Sumber `size` total → ini yang bikin progress bar bisa akurat & ETA bisa dihitung **sebelum** byte pertama turun. |
| 3 | **Endpoint config** `/api/update` | Bootstrap: versi, URL manifest, URL APK, changelog. Supaya kamu bisa pindah CDN / ganti skema **tanpa rilis APK baru**. |
| 4 | **HTTPS** | Android 9+ (API 28) memblokir cleartext HTTP secara default. `minSdk` kamu 24 → butuh `usesCleartextTraffic` kalau nekat HTTP. Jangan. |
| 5 | **CDN di depan origin** | 1,34 GB × N user langsung ke VPS = VPS mati. Cloudflare punya PoP di **Jakarta & Singapura**, jadi user ID dapat latency rendah. |
| 6 | **Generator manifest (script)** | Manifest harus di-regenerate tiap asset berubah. Manual = pasti lupa = client rusak. |

Yang **tidak** wajib tapi sangat disarankan: `sha256` per file, versioning path, mirror kedua, rate limit.

---

## 2. Skema endpoint (persis yang dibaca `AssetApi.kt`)

### `GET /api/update`
```json
{
  "status": true,
  "game_version": "1.0.0",
  "game_url": "https://cdn.client-mu.example/apk/SanzzClient-1.0.0-arm64.apk",
  "game_files": "https://cdn.client-mu.example/api/game-files",
  "changelog": "https://cdn.client-mu.example/api/changelog",
  "servers": "https://cdn.client-mu.example/api/servers"
}
```
`status: false` = kill switch. Kalau CDN-mu bermasalah, set false → semua client berhenti mencoba, tidak ada badai retry.

### `GET /api/game-files`
```json
{
  "files": [
    { "name": "gta3.img", "size": "283521024",
      "path": "texdb/gta3.img",
      "url": "https://cdn.client-mu.example/game-files/texdb/gta3.img",
      "sha256": "…" }
  ]
}
```
- `size` = **string** byte (Alyn begitu; `AssetApi.kt` terima string maupun angka)
- `path` = relatif terhadap `gameDir` di HP
- `url` = boleh absolut, boleh relatif terhadap `baseUrl`, boleh dihilangkan (otomatis jadi `{baseUrl}/game-files/{path}`)
- `sha256` = **tambahan dari Alyn**. Mereka tidak punya; verifikasi mereka cuma bandingkan ukuran. File korup yang kebetulan panjangnya sama akan lolos. Jangan ditiru.

---

## 3. Biaya — ini bagian yang paling menentukan

Asumsi: 1 install baru = **1,34 GB** egress + **±350 request** (295 file + retry).

### Cloudflare R2  ← **rekomendasiku**
Storage $0,015/GB-bulan · **egress $0 tanpa syarat, tanpa batas** · Class B (read) $0,36/juta · free tier bulanan: 10 GB + 1 juta Class A + **10 juta Class B**.

| Install baru/bulan | Egress | Biaya R2 |
|---|---|---|
| 100 | 134 GB | **$0** |
| 1.000 | 1,34 TB | **$0** |
| 10.000 | 13,4 TB | **$0** (3,5 juta request, masih di bawah free tier 10 juta) |
| 30.000 | 40 TB | **±$1** |
| 100.000 | 134 TB | **±$9** (25 juta request di luar free tier) |
| 1.000.000 | 1,34 PB | **±$100** |

Asset kamu cuma 1,34 GB → storage gratis selamanya (di bawah free tier 10 GB).
**Egress-nya yang gratis, dan itu 99% dari biaya workload seperti ini.**

Sebagai pembanding, AWS S3 di 100.000 install = 134 TB × $0,09/GB = **$12.000/bulan**. R2 = $9.

### Bunny CDN
$0,01/GB (Eropa/NA) tapi **$0,03/GB untuk Asia & Oseania** — dan user-mu kemungkinan besar di Indonesia. Minimum $1/bulan.

| Install baru/bulan | Egress | Biaya Bunny (Asia) |
|---|---|---|
| 1.000 | 1,34 TB | ±$41 |
| 10.000 | 13,4 TB | ±$403 |
| 100.000 | 134 TB | ±$4.030 |

Bunny baru masuk akal kalau mayoritas user-mu di Eropa/NA ($0,005–0,01/GB di tier volume).

### Backblaze B2 + Cloudflare CDN
$6,95/TB-bulan storage, egress **gratis lewat Bandwidth Alliance** kalau keluar via Cloudflare. Total ±$7/bulan flat berapa pun traffic-nya.

⚠️ Tapi: ini pola yang dipakai Alyn (`server: cloudflare` + `x-served-by: alynsampmobile.pro` = origin sendiri di belakang proxy Cloudflare). Cloudflare **Free plan** secara ToS tidak untuk menyajikan konten mayoritas non-HTML. Di 134 TB/bulan itu zona abu-abu yang bisa berujung akun ditangguhkan. **R2 tidak punya masalah ini** — R2 memang produk storage berbayar dengan egress gratis sebagai fitur.

### VPS + nginx (Hetzner/Contabo dsb.)
Kontrol penuh, biaya tetap, biasanya sudah termasuk kuota traffic puluhan TB. Cocok untuk mulai. Tapi: harus urus CDN sendiri, dan begitu melewati kuota, biaya per-TB-nya jauh lebih mahal dari R2. **Cek harga & kuota terkini sendiri sebelum commit** — aku tidak verifikasi angka VPS di sesi ini.

### Kesimpulan
**Cloudflare R2 + custom domain.** Nol biaya sampai ±30.000 install/bulan, tidak ada zona abu-abu ToS, PoP Jakarta tersedia, Range request didukung native.

---

## 4. Arsitektur yang aku sarankan

```
                    ┌──────────────────────────────────────────┐
   APK SanzzClient  │  Cloudflare                              │
        │           │                                          │
        │  GET /api/update          ┌────────────┐             │
        ├──────────────────────────►│  Worker    │  vars dari  │
        │  GET /api/game-files      │  (JSON     │  wrangler   │
        │                           │   kecil)   │  .toml      │
        │                           └─────┬──────┘             │
        │                                 │ baca manifest.json │
        │                                 ▼                    │
        │                           ┌──────────┐               │
        │  GET /game-files/<path>   │  Bucket  │  1,34 GB      │
        └──────────────────────────►│  R2      │  295 file     │
             LANGSUNG, tidak lewat  │  + custom│               │
             Worker (lebih murah &  │  domain  │               │
             tidak kena CPU time)   └──────────┘               │
                    └──────────────────────────────────────────┘
```

**Poin pentingnya:** biarkan file asset dilayani **langsung dari custom domain R2**, jangan diproksikan lewat Worker. Worker cuma untuk 2 endpoint JSON yang ukurannya 1–40 KB. Ini yang bikin biayanya tetap $0.

---

## 5. Keputusan `cache-control` (Alyn salah di sini)

Alyn mengirim:
```
cache-control: public, max-age=120
```
Cuma **2 menit**. Untuk asset game yang tidak berubah, itu artinya edge Cloudflare terus-menerus revalidasi ke origin → biaya request naik, TTFB lebih lambat.

Mereka terpaksa begitu karena **menimpa file di path yang sama**. Kalau `texdb/gta3.img` di-update, client yang sudah cache tidak akan lihat versi baru tanpa max-age pendek.

**Pola yang lebih baik — taruh versi di path:**
```
/game-files/1.0.0/texdb/gta3.img     → cache-control: public, max-age=31536000, immutable
/game-files/1.0.1/texdb/gta3.img     → path baru, cache baru
```
Manifest menunjuk ke path berversi. Hasilnya:
- Asset bisa di-cache 1 tahun, nol revalidasi
- Update = ganti manifest, bukan hapus cache
- Rollback instan (manifest lama masih valid)
- Dua versi bisa hidup bersamaan → user di APK lama tidak rusak saat kamu rilis asset baru

Kalau tidak mau versioning path, pakai `max-age=86400` + `ETag`, dan terima bahwa update butuh purge manual.

Untuk manifest-nya sendiri: `public, max-age=60` (seperti Alyn) — harus cepat terlihat saat kamu ganti.

---

## 6. Yang perlu kamu tambahkan di atas desain Alyn

1. **`sha256` per file.** Alyn cuma cek panjang. File korup dengan panjang sama lolos diam-diam → game crash tanpa pesan yang jelas. `AssetDownloader.kt` sudah menyiapkan field-nya; tinggal kamu isi di manifest.
2. **Versioning path** (lihat §5).
3. **Mirror kedua.** Alyn punya fallback jsDelivr↔GitHub untuk model 0.3.DL, tapi tidak untuk game files. Parameter `mirrors` di `AssetDownloader.kt` sudah siap — isi dengan CDN cadangan.
4. **Cek ruang storage sebelum mulai.** 1,34 GB asset + ruang `.part` = butuh ±3 GB bebas. `StatFs(gameDir).availableBytes`.
5. **`status: false` kill switch** di `/api/update`.
6. **Rate limit ringan** di endpoint JSON (manifest 40 KB, kalau di-hammer jadi mahal). Cloudflare WAF rate limiting rule: 60 request/menit/IP. File asset tidak perlu — sudah di-cache edge.

---

## 7. Isi folder ini

| File | Fungsi |
|---|---|
| `generate-manifest.mjs` | Jalan di komputermu: scan folder asset → hitung sha256 → tulis `manifest.json`. Node 18+, tanpa dependency. |
| `worker.mjs` | Cloudflare Worker: `/api/update`, `/api/game-files`, `/api/changelog` |
| `wrangler.toml` | Config deploy + variabel versi |
| `nginx-assets.conf` | Alternatif kalau kamu pilih VPS sendiri |
| `deploy.sh` | Urutan lengkap: generate → upload R2 → deploy Worker → verifikasi |

---

## 8. Urutan eksekusi

```
1. wrangler login                        (akun Cloudflare, gratis)
2. wrangler r2 bucket create sanzz-assets
3. taruh 295 file asset di ./game-files/  (struktur = path di manifest)
4. node generate-manifest.mjs ./game-files https://cdn.client-mu.example
5. ./deploy.sh                            (upload + deploy worker)
6. R2 → Settings → Custom Domain → cdn.client-mu.example
7. ubah ASSET_BASE_URL di GameInstallerV2.kt
8. tes dengan manifest 3 file dulu, baru full 295
```

**Jangan langsung tes 1,34 GB.** Bikin folder uji berisi 3 file kecil, generate manifest, pastikan alur resume/retry/progress benar, baru naik ke asset sungguhan. Iterasi 10 detik vs 20 menit.

---

## 9. Soal isi asset-nya

Yang 1,34 GB itu `textures/`, `texdb/`, `audio/`, `anim/` — asset GTA: San Andreas, hak cipta Rockstar/Take-Two. Alyn meng-host dan mendistribusikannya sendiri; situs mereka memasang disclaimer "Not affiliated with Rockstar Games or Take-Two Interactive", yang tidak mengubah status hukumnya.

Keputusannya di tanganmu, tapi sadarilah bahwa begitu kamu meng-host 1,34 GB asset itu di R2-mu sendiri, **kamu jadi distributor-nya** — bukan lagi sekadar pembuat launcher. Jalur paling defensibel tetap yang sudah ada di kodemu: `importArchive()` — user mengimpor data GTA:SA milik mereka sendiri, launcher-mu cuma menyediakan engine + SA-MP + UI. Itu juga alasan kenapa `archivePickerLauncher` layak dipertahankan sebagai jalur utama, bukan cadangan.
