#!/usr/bin/env bash
# ============================================================================
# Upload 295 file asset ke GitHub Release — gratis, tanpa kartu, resume jalan.
#
#   ./github-release-upload.sh <user> <repo> <tag> <folderAsset> [paralel]
#
# Contoh:
#   ./github-release-upload.sh sanzzdev sanzz-assets v1.0.0 ./game-files 8
#
# Sifatnya IDEMPOTENT: asset yang sudah ada dengan ukuran sama di-skip.
# Jadi kalau koneksi putus di file ke-140, jalankan ulang — lanjut dari situ.
#
# Prasyarat:
#   • gh CLI   →  https://cli.github.com   lalu:  gh auth login
#   • repo publik sudah dibuat:  gh repo create <repo> --public
#   • manifest sudah digenerate:  node generate-github-manifest.mjs …
# ============================================================================
set -uo pipefail   # sengaja bukan -e: satu asset gagal tidak boleh menghentikan sisanya

USER_="${1:-}"; REPO="${2:-}"; TAG="${3:-}"; ROOT="${4:-./game-files}"; PAR="${5:-6}"

if [ -z "$USER_" ] || [ -z "$REPO" ] || [ -z "$TAG" ]; then
  echo "Pakai: $0 <user> <repo> <tag> <folderAsset> [paralel]" >&2; exit 1
fi

command -v gh >/dev/null || { echo "✗ gh CLI belum ada. Install: https://cli.github.com" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "✗ belum login. Jalankan: gh auth login" >&2; exit 1; }
[ -d "$ROOT" ] || { echo "✗ folder tidak ada: $ROOT" >&2; exit 1; }

REPO_FULL="$USER_/$REPO"
TSV="./manifest-assets.tsv"

hr(){ printf '\n\033[1m── %s ──\033[0m\n' "$1"; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$1"; }
err(){ printf '  \033[31m✗\033[0m %s\n' "$1" >&2; }

# ukuran file dalam byte — portabel GNU (stat -c) & BSD/macOS (stat -f)
fsize(){ stat -c%s "$1" 2>/dev/null || stat -f%z "$1" 2>/dev/null || echo 0; }

# ---------------------------------------------------------------- 1. release
hr "1. Release $REPO_FULL @ $TAG"
if gh release view "$TAG" -R "$REPO_FULL" >/dev/null 2>&1; then
  ok "release sudah ada, dilanjutkan"
else
  gh release create "$TAG" -R "$REPO_FULL" \
      --title "Asset game $TAG" \
      --notes "Asset data game untuk SanzzClient. $(date -u +%F). Total $(find "$ROOT" -type f | wc -l) file." \
      --target "$(gh repo view "$REPO_FULL" --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null || echo main)" \
    && ok "release dibuat" || { err "gagal membuat release"; exit 1; }
fi

# ---------------------------------------------------------------- 2. daftar asset yang sudah ada
hr "2. Cek asset yang sudah ter-upload"
if [ -f "$TSV" ]; then
  ok "pakai $TSV (dari generate-github-manifest.mjs)"
else
  warn "$TSV tidak ada — dibuat ulang dari isi folder (tanpa sha256)"
  : > "$TSV"
  while IFS= read -r f; do
    rel="${f#$ROOT/}"; flat="${rel//\//__}"
    printf '%s\t%s\t%s\n' "$rel" "$flat" "$(stat -c%s "$f")" >> "$TSV"
  done < <(find "$ROOT" -type f ! -name '*.part' ! -name '.*' | sort)
fi

TOTAL=$(wc -l < "$TSV")
ok "$TOTAL asset akan dipastikan ada di release"

# Nama + ukuran asset yang SUDAH ada di release (untuk skip)
gh release view "$TAG" -R "$REPO_FULL" --json assets -q '.assets[] | "\(.name)\t\(.size)"' \
   > /tmp/existing.tsv 2>/dev/null || : > /tmp/existing.tsv
EXISTING=$(wc -l < /tmp/existing.tsv)
ok "$EXISTING asset sudah ada di release"

# ---------------------------------------------------------------- 3. upload yang kurang
hr "3. Upload (paralel $PAR)"
TODO="/tmp/todo_$$.txt"; : > "$TODO"
STALE="/tmp/stale_$$.txt"; : > "$STALE"
SKIPN=0; DONEN=0; MISSING=0

while IFS=$'\t' read -r rel flat size; do
  [ -z "${rel:-}" ] && continue
  local_path="$ROOT/$rel"
  if [ ! -f "$local_path" ]; then err "file lokal hilang: $rel"; MISSING=$((MISSING+1)); continue; fi

  # PENTING: bandingkan dengan ukuran file AKTUAL di disk, BUKAN angka dari TSV.
  # TSV bisa basi kalau kamu mengedit asset setelah generate manifest.
  # Kalau itu terjadi, byte yang di-upload tidak akan cocok dengan sha256 di
  # manifest → cek integritas client gagal tanpa pesan yang jelas.
  actual=$(fsize "$local_path")
  if [ "$actual" != "$size" ]; then
    printf '%s\t%s\t%s\n' "$rel" "$size" "$actual" >> "$STALE"
  fi

  # sudah ada di release dengan ukuran yang sama dengan file lokal → skip
  if grep -qxF "$(printf '%s\t%s' "$flat" "$actual")" /tmp/existing.tsv; then
    SKIPN=$((SKIPN+1)); continue
  fi
  printf '%s\t%s\n' "$local_path" "$flat" >> "$TODO"
  DONEN=$((DONEN+1))
done < "$TSV"

ok "skip $SKIPN (sudah lengkap) · upload $DONEN"
[ "$MISSING" -gt 0 ] && warn "$MISSING file ada di manifest tapi hilang dari folder"

# Manifest basi = wajib regenerate. Jangan lanjut diam-diam.
if [ -s "$STALE" ]; then
  echo
  err "MANIFEST BASI — asset berubah setelah manifest dibuat:"
  while IFS=$'\t' read -r rel old new; do
    printf '      %-46s manifest=%s  disk=%s\n' "$rel" "$old" "$new"
  done < "$STALE"
  echo
  echo "    Ukuran/sha256 di manifest tidak lagi cocok dengan file yang akan di-upload."
  echo "    Client akan gagal verifikasi integritas tanpa pesan yang jelas."
  echo
  echo "    Perbaiki dulu:"
  echo "      node generate-github-manifest.mjs $ROOT $USER_ $REPO $TAG ./manifest.json"
  echo "    lalu jalankan ulang skrip ini."
  rm -f "$TODO" "$STALE"; exit 1
fi
rm -f "$STALE"

if [ "$DONEN" -gt 0 ]; then
  # gh release upload per asset, paralel.
  #   • NUL-delimited (-0) supaya path berspasi/tab aman
  #   • -n 2 → tiap invocation dapat 2 argumen: src lalu name
  #   • "$src#$name" → sintaks gh untuk meng-upload file dengan nama berbeda
  #   • --clobber → timpa asset lama dengan nama sama
  # Kalau koneksi putus di tengah, jalankan ulang skrip: yang sudah lengkap di-skip.
  while IFS=$'\t' read -r src name; do
    [ -n "$src" ] && printf '%s\0%s\0' "$src" "$name"
  done < "$TODO" \
  | xargs -0 -P "$PAR" -n 2 bash -c '
      src="$0"; name="$1"
      if gh release upload "'"$TAG"'" "$src#$name" -R "'"$REPO_FULL"'" --clobber >/dev/null 2>&1; then
        printf "  \033[32m✓\033[0m %s\n" "$name"
      else
        printf "  \033[31m✗\033[0m %s  (jalankan ulang skrip ini untuk retry)\n" "$name"
      fi
    '
fi
rm -f "$TODO"

# ---------------------------------------------------------------- 4. verifikasi
hr "4. Verifikasi"
sleep 3
gh release view "$TAG" -R "$REPO_FULL" --json assets -q '.assets[] | "\(.name)\t\(.size)"' \
   > /tmp/final.tsv 2>/dev/null || : > /tmp/final.tsv
FINAL=$(wc -l < /tmp/final.tsv)

if [ "$FINAL" -eq "$TOTAL" ]; then
  ok "$FINAL / $TOTAL asset lengkap ✅"
else
  warn "$FINAL / $TOTAL asset — jalankan ulang skrip ini, yang sudah ada akan di-skip"
fi

# Cek ukuran cocok
MISMATCH=0
while IFS=$'\t' read -r rel flat size; do
  [ -z "${rel:-}" ] && continue
  got=$(grep -F "$(printf '%s\t' "$flat")" /tmp/final.tsv | head -1 | cut -f2)
  actual=$(fsize "$ROOT/$rel" 2>/dev/null || echo 0)
  if [ -n "$got" ] && [ "$got" != "$actual" ]; then
    err "ukuran beda: $flat  disk=$actual  release=$got"; MISMATCH=$((MISMATCH+1))
  fi
done < "$TSV"
[ "$MISMATCH" -eq 0 ] && ok "semua ukuran cocok" || warn "$MISMATCH asset ukurannya beda"

# Uji Range pada asset terbesar — INI PENENTU resume jalan atau tidak
BIG=$(sort -t$'\t' -k3 -rn "$TSV" | head -1 | cut -f2)
URL="https://github.com/$REPO_FULL/releases/download/$TAG/$BIG"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 -r 1000-1999 -L "$URL" || echo 000)
if [ "$CODE" = "206" ]; then
  ok "Range test → HTTP 206 (resume jalan)  [$BIG]"
else
  err "Range test → HTTP $CODE (harus 206). Cek nama asset & tag."
fi

hr "5. Selesai"
echo "  Release : https://github.com/$REPO_FULL/releases/tag/$TAG"
echo "  Manifest: upload manifest.json ke repo, lalu client ambil dari"
echo "            https://raw.githubusercontent.com/$REPO_FULL/$TAG/manifest.json"
echo "            atau https://cdn.jsdelivr.net/gh/$REPO_FULL@$TAG/manifest.json"
echo
echo "  PENTING — mirror:"
echo "    Repo publik berisi asset berhak cipta Rockstar bisa kena DMCA kapan saja."
echo "    Isi parameter 'mirrors' di AssetDownloader.kt dengan sumber kedua supaya"
echo "    client tidak mati total saat itu terjadi."
