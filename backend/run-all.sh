#!/usr/bin/env bash
# ============================================================================
# SATU PERINTAH: verifikasi → generate → upload → push → uji
#
#   ./run-all.sh <folderAsset> <tag> [paralel]
#
# Contoh:
#   ./run-all.sh ../game-files v1.0.0 8
#
# Berhenti di tahap mana pun yang gagal, dengan pesan yang jelas.
# Aman dijalankan ulang (idempotent).
#
# Prasyarat: git, gh CLI (gh auth login), Node 18+, curl.
# ============================================================================
set -uo pipefail

ROOT="${1:-../game-files}"
TAG="${2:-v1.0.0}"
PAR="${3:-8}"

USER_="SanzzLauncher"
REPO="SanzzLauncher"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$HERE")"

hr(){ printf '\n\033[1m══════ %s ══════\033[0m\n' "$1"; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$1"; }
die(){ printf '\n  \033[31m✗ %s\033[0m\n\n' "$1" >&2; exit "${2:-1}"; }

# ---------------------------------------------------------------- 0. preflight
hr "0/6  Preflight"
command -v node >/dev/null || die "Node.js belum ada (butuh 18+)"
command -v git  >/dev/null || die "git belum ada"
command -v curl >/dev/null || die "curl belum ada"
ok "node $(node -v) · git $(git --version | awk '{print $3}')"

if command -v gh >/dev/null; then
  gh auth status >/dev/null 2>&1 || die "gh ada tapi belum login. Jalankan: gh auth login"
  ok "gh $(gh --version | head -1 | awk '{print $3}') · sudah login"
else
  die "gh CLI belum ada. Tanpa itu asset tidak bisa di-upload.
      Windows : winget install --id GitHub.cli
      Linux   : lihat backend/docs/SETUP-BACKEND-GRATIS.md Tahap 1
      macOS   : brew install gh
      lalu    : gh auth login"
fi

[ -d "$ROOT" ] || die "folder asset tidak ditemukan: $ROOT"
ok "folder asset: $ROOT"

# Ruang disk: butuh ±2x ukuran asset (staging + git)
NEED_KB=$(( $(du -sk "$ROOT" | cut -f1) * 2 ))
FREE_KB=$(df -Pk "$REPO_ROOT" | awk 'NR==2{print $4}')
if [ "$FREE_KB" -lt "$NEED_KB" ]; then
  warn "ruang disk tipis: bebas $(( FREE_KB/1024 )) MB, disarankan $(( NEED_KB/1024 )) MB"
else
  ok "ruang disk: $(( FREE_KB/1024 )) MB bebas"
fi

# ---------------------------------------------------------------- 1. verifikasi
hr "1/6  Verifikasi kelengkapan asset"
node "$HERE/verify-game-files.mjs" "$ROOT" || \
  die "asset belum lengkap. Perbaiki dulu daftar di atas, lalu jalankan ulang.
      (Sengaja dihentikan: manifest dari folder tidak lengkap bikin client
       mengunduh sebagian saja lalu gagal verifikasi tanpa pesan jelas.)"
ok "asset lengkap"

# ---------------------------------------------------------------- 2. manifest
hr "2/6  Generate manifest + config"
cd "$REPO_ROOT" || die "gagal masuk ke root repo"
node "$HERE/generate-github-manifest.mjs" "$ROOT" "$USER_" "$REPO" "$TAG" ./manifest.json \
  || die "generate manifest gagal"
mv -f manifest-config.json config.json
[ -f manifest.json ] && [ -f config.json ] || die "manifest.json / config.json tidak terbentuk"
ok "manifest.json + config.json"

# ---------------------------------------------------------------- 3. upload
hr "3/6  Upload asset ke GitHub Release ($TAG)"
"$HERE/github-release-upload.sh" "$USER_" "$REPO" "$TAG" "$ROOT" "$PAR" \
  || die "upload release gagal (lihat pesan di atas). Jalankan ulang untuk retry."
ok "release $TAG terisi"

# ---------------------------------------------------------------- 4. push
hr "4/6  Push manifest ke repo"
git add manifest.json config.json changelog.txt 2>/dev/null
if git diff --cached --quiet; then
  warn "tidak ada perubahan untuk di-commit (manifest identik)"
else
  git commit -q -m "asset manifest $TAG" && git push -q origin HEAD \
    && ok "manifest ter-push" || die "git push gagal. Cek: gh auth status, lalu git push manual."
fi

# ---------------------------------------------------------------- 5. uji dari internet
hr "5/6  Verifikasi dari internet"
sleep 8
FAIL=0

BIG=$(python3 -c "import json;d=json.load(open('manifest.json'));\
print(sorted(d['files'],key=lambda f:-int(f['size']))[0]['url'])" 2>/dev/null) \
  || BIG=$(node -e "const d=require('./manifest.json');\
console.log([...d.files].sort((a,b)=>b.size-a.size)[0].url)")

printf '  asset terbesar : %s\n' "$BIG"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 90 -r 1000-1999 -L "$BIG" || echo 000)
if [ "$CODE" = "206" ]; then ok "Range request → HTTP 206 (resume jalan)"
else warn "Range → HTTP $CODE (harus 206). CDN mungkin belum siap; coba lagi 2 menit."; FAIL=1; fi

# Suffix range memang TIDAK didukung GitHub/Fastly — ini normal, jangan kaget.
CODE2=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 -r -500 -L "$BIG" || echo 000)
[ "$CODE2" = "501" ] && ok "Suffix range → 501 (NORMAL: Fastly menolak 'bytes=-N', downloader tidak memakainya)"

for u in "https://cdn.jsdelivr.net/gh/$USER_/$REPO@main/manifest.json" \
         "https://cdn.jsdelivr.net/gh/$USER_/$REPO@main/config.json"; do
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 40 "$u")
  if [ "$C" = "200" ]; then ok "jsDelivr: $(basename "$u")"
  else warn "jsDelivr: $(basename "$u") → HTTP $C (cache baru butuh 2-5 menit)"; fi
done

C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 40 \
    "https://raw.githubusercontent.com/$USER_/$REPO/main/manifest.json")
[ "$C" = "200" ] && ok "raw.githubusercontent (fallback) → 200" || warn "fallback → HTTP $C"

# ---------------------------------------------------------------- 6. selesai
hr "6/6  Selesai"
echo "  Release  : https://github.com/$USER_/$REPO/releases/tag/$TAG"
echo "  Manifest : https://cdn.jsdelivr.net/gh/$USER_/$REPO@main/manifest.json"
echo "  Config   : https://cdn.jsdelivr.net/gh/$USER_/$REPO@main/config.json"
echo
echo "  Sisi Android sudah di-hardcode ke repo ini (android/GameInstallerV2.kt),"
echo "  jadi tidak ada yang perlu diubah lagi kalau nama repo tidak berubah."
echo
if [ "$FAIL" = "0" ]; then
  ok "SEMUA HIJAU. Client sudah bisa mengunduh."
  echo
  echo "  ⚠ Satu hal yang masih wajib: bikin repo MIRROR kedua."
  echo "    Repo publik berisi asset berhak cipta bisa kena DMCA kapan saja."
  echo "    Tanpa mirror, semua client mati serentak. Lihat README bagian Mirror."
else
  warn "Ada yang belum hijau — jalankan ulang skrip ini, yang sudah selesai di-skip."
fi
