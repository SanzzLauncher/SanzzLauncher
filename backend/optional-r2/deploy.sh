#!/usr/bin/env bash
# ============================================================================
# Deploy backend asset SanzzClient ke Cloudflare R2 + Workers.
#
#   ./deploy.sh [folderAsset] [publicBaseUrl] [versi]
#
# Contoh:
#   ./deploy.sh ./game-files https://cdn.client-mu.example 1.0.0
#
# Prasyarat:
#   • Node 18+          (cek: node -v)
#   • wrangler          (npm i -g wrangler  →  wrangler login)
#   • bucket R2 sudah dibuat:
#       wrangler r2 bucket create sanzz-assets
#   • wrangler.toml sudah diisi domain yang benar
#
# Asset game TIDAK di-upload lewat skrip ini secara default (1,34 GB lewat
# `wrangler r2 object put` satu-satu itu lambat). Pakai rclone — lihat bagian
# UPLOAD MASSAL di bawah.
# ============================================================================
set -euo pipefail

ROOT="${1:-./game-files}"
BASE="${2:-https://cdn.client-mu.example}"
VERSION="${3:-1.0.0}"

BUCKET="sanzz-assets"
MANIFEST="./manifest.json"
UPDATE_JSON="./update.json"

hr() { printf '\n\033[1m── %s ──\033[0m\n' "$1"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die(){ printf '  \033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- 0. cek
hr "0. Prasyarat"
command -v node     >/dev/null || die "node belum ada. Install Node 18+."
command -v wrangler >/dev/null || die "wrangler belum ada. Jalankan: npm i -g wrangler && wrangler login"
command -v curl     >/dev/null || die "curl belum ada."
[ -d "$ROOT" ] || die "Folder asset tidak ditemukan: $ROOT"
ok "node $(node -v)"
ok "wrangler $(wrangler --version 2>/dev/null | head -1)"
ok "asset root: $ROOT"

# ---------------------------------------------------------------- 1. manifest
hr "1. Generate manifest (+ sha256)"
node generate-manifest.mjs "$ROOT" "$BASE" "$MANIFEST" "$VERSION"
[ -f "$MANIFEST" ] || die "manifest gagal dibuat"

TOTAL_FILES=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$MANIFEST')).total_files)")
TOTAL_BYTES=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$MANIFEST')).total_bytes)")
ok "$TOTAL_FILES file · $(( TOTAL_BYTES / 1048576 )) MB"

# ---------------------------------------------------------------- 2. update.json
hr "2. Buat update.json (bootstrap config)"
APK_URL="${APK_URL:-$BASE/apk/SanzzClient-$VERSION-arm64.apk}"
cat > "$UPDATE_JSON" <<EOF
{
  "status": true,
  "game_version": "$VERSION",
  "game_url": "$APK_URL",
  "game_files": "$BASE/api/game-files",
  "changelog": "$BASE/api/changelog",
  "servers": "${SERVERS_URL:-$BASE/api/servers}"
}
EOF
cat "$UPDATE_JSON"
ok "update.json"

# ---------------------------------------------------------------- 3. upload metadata
hr "3. Upload manifest + update + changelog ke R2"
wrangler r2 object put "$BUCKET/manifest.json" --file="$MANIFEST"  --content-type="application/json" --remote >/dev/null
ok "r2://$BUCKET/manifest.json"
wrangler r2 object put "$BUCKET/update.json"   --file="$UPDATE_JSON" --content-type="application/json" --remote >/dev/null
ok "r2://$BUCKET/update.json"
if [ -f changelog.txt ]; then
  wrangler r2 object put "$BUCKET/changelog.txt" --file=changelog.txt --content-type="text/plain; charset=utf-8" --remote >/dev/null
  ok "r2://$BUCKET/changelog.txt"
fi

# ---------------------------------------------------------------- 4. deploy worker
hr "4. Deploy Worker"
wrangler deploy
ok "worker ter-deploy"

# ---------------------------------------------------------------- 5. verifikasi
hr "5. Verifikasi dari luar"
sleep 5
fail=0

check() { # url, harapan_substring, label
  local out; out=$(curl -s --max-time 30 "$1" || true)
  if echo "$out" | grep -q "$2"; then ok "$3"; else
    printf '  \033[31m✗ %s\033[0m\n' "$3"; echo "      → ${out:0:200}"; fail=1
  fi
}

check "$BASE/api/update"      '"game_files"'  "GET /api/update mengembalikan game_files"
check "$BASE/api/game-files"  '"files"'       "GET /api/game-files mengembalikan files"

# Range support — INI YANG PALING KRITIS. Tanpa 206, resume mati total.
SAMPLE=$(node -e "const m=require('$MANIFEST');const f=[...m.files].sort((a,b)=>b.size-a.size)[0];console.log(f.url)")
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 40 -r 0-1023 "$SAMPLE" || echo "000")
if [ "$CODE" = "206" ]; then
  ok "Range request → HTTP 206 (resume jalan)  [$SAMPLE]"
else
  printf '  \033[31m✗ Range request → HTTP %s (harus 206)\033[0m\n' "$CODE"
  echo "      Kalau 200: server mengabaikan header Range → download tidak bisa resume."
  echo "      Kalau 404: file belum di-upload ke R2, atau custom domain belum diset."
  fail=1
fi

AR=$(curl -sI --max-time 30 "$SAMPLE" | grep -i '^accept-ranges' || true)
[ -n "$AR" ] && ok "$AR" || { printf '  \033[33m! header accept-ranges tidak terlihat\033[0m\n'; }

hr "6. Selesai"
if [ "$fail" = "0" ]; then
  ok "Semua cek lolos."
  echo
  echo "  Langkah berikutnya:"
  echo "    1. Upload 295 file asset ke bucket (lihat UPLOAD MASSAL di komentar skrip ini)"
  echo "    2. R2 → Settings → Custom Domain → pastikan $BASE aktif + HTTPS"
  echo "    3. Ubah ASSET_BASE_URL di GameInstallerV2.kt → $BASE"
  echo "    4. TES DULU dengan manifest 3 file kecil sebelum naik ke 1,34 GB"
else
  printf '  \033[33mAda cek yang gagal — lihat di atas.\033[0m\n'
  exit 1
fi

# ============================================================================
# UPLOAD MASSAL 1,34 GB — jangan pakai `wrangler r2 object put` per file.
#
# Cara tercepat: rclone dengan remote S3-compat menuju R2.
#
#   1. rclone config
#        n) new remote          → nama: r2
#        storage) s3
#        provider) Cloudflare
#        access_key_id / secret_access_key  → dari
#            Cloudflare dashboard → R2 → Manage R2 API Tokens
#        endpoint) https://<ACCOUNT_ID>.r2.cloudflarestorage.com
#
#   2. upload paralel:
#        rclone copy ./game-files r2:sanzz-assets/game-files \
#          --transfers 16 --checkers 16 --progress --s3-chunk-size 64M
#
#      --transfers 16 → 295 file / 1,34 GB selesai dalam beberapa menit.
#      rclone otomatis skip file yang sudah ada & ukurannya sama,
#      jadi aman dijalankan ulang setelah koneksi putus.
#
#   3. verifikasi jumlah:
#        rclone size r2:sanzz-assets/game-files
#      harus cocok dengan angka di manifest.
#
# STRUKTUR KEY di bucket harus PERSIS sama dengan `path` di manifest:
#   manifest path "texdb/gta3.img"  →  r2:sanzz-assets/game-files/texdb/gta3.img
#   dan URL publiknya               →  $BASE/game-files/texdb/gta3.img
# ============================================================================
