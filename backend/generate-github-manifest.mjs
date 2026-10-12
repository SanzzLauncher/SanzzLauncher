#!/usr/bin/env node
/**
 * Generator manifest untuk jalur GRATIS: GitHub Releases.
 *
 * Beda dengan generate-manifest.mjs cuma di pembentukan URL:
 *
 *   GitHub tidak mengizinkan "/" di nama asset release, jadi
 *     path "texdb/gta3.img"  →  nama asset "texdb__gta3.img"
 *
 *   url  = https://github.com/<user>/<repo>/releases/download/<tag>/texdb__gta3.img
 *   path = texdb/gta3.img        (tetap — ini lokasi simpan di HP)
 *
 * AssetDownloader.kt TIDAK PERLU DIUBAH: dia mengunduh dari `url` dan
 * menyimpan ke `path`. Dua field itu memang sudah terpisah.
 *
 * Pakai:
 *   node generate-github-manifest.mjs <folder> <user> <repo> <tag> [outFile]
 * Contoh:
 *   node generate-github-manifest.mjs ./game-files sanzzdev sanzz-assets v1.0.0
 *
 * Menghasilkan 2 file:
 *   manifest.json          → upload ke repo / sajikan lewat raw.githubusercontent.com
 *   release-assets.tsv     → dibaca github-release-upload.sh (path lokal \t nama asset)
 */

import { createReadStream } from 'node:fs'
import { readdir, stat, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, relative, sep } from 'node:path'

const ROOT = process.argv[2]
const USER = process.argv[3]
const REPO = process.argv[4]
const TAG = process.argv[5] ?? 'v1.0.0'
const OUT = process.argv[6] ?? './manifest.json'
const TSV = OUT.replace(/\.json$/, '') + '-assets.tsv'
const CFG = OUT.replace(/\.json$/, '') + '-config.json'

const GH_LIMIT = 2 * 1024 ** 3 // 2 GB per asset release
const SKIP = new Set(['.DS_Store', 'Thumbs.db', '.gitkeep'])

if (!ROOT || !USER || !REPO) {
  console.error('Pakai: node generate-github-manifest.mjs <folder> <user> <repo> [tag] [outFile]')
  process.exit(1)
}

// Nama asset GitHub Release: tidak boleh ada "/", dan karakter selain
// [A-Za-z0-9._-] bikin URL perlu percent-encoding (rapuh di beberapa HTTP stack).
// Jadi semua karakter tidak aman diganti "_".
const sanitize = (s) => s.replace(/[^A-Za-z0-9._-]/g, '_')
const flatten = (rel) => sanitize(rel.split('/').join('__'))

// Hash pendek dari path, untuk memecah tabrakan nama setelah sanitasi.
// Contoh: "a b.img" dan "a_b.img" dua-duanya jadi "a_b.img".
function pathTag(rel) {
  return createHash('sha1').update(rel).digest('hex').slice(0, 8)
}

function sha256File(path) {
  return new Promise((resolve, reject) => {
    const h = createHash('sha256')
    const s = createReadStream(path, { highWaterMark: 1024 * 1024 })
    s.on('data', (d) => h.update(d))
    s.on('end', () => resolve(h.digest('hex')))
    s.on('error', reject)
  })
}

async function* walk(dir) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.') || SKIP.has(e.name)) continue
    const full = join(dir, e.name)
    if (e.isDirectory()) yield* walk(full)
    else if (e.isFile() && !e.name.endsWith('.part') && !e.name.endsWith('.tmp')) yield full
  }
}

const mb = (b) => (b / 1048576).toFixed(1)

async function main() {
  // ---- pass 1: kumpulkan semua path, selesaikan tabrakan nama asset ----
  const rels = []
  for await (const full of walk(ROOT)) {
    rels.push({ full, rel: relative(ROOT, full).split(sep).join('/') })
  }
  if (!rels.length) { console.error(`✗ Tidak ada file di ${ROOT}`); process.exit(1) }

  const byFlat = new Map()
  for (const { rel } of rels) {
    const flat = flatten(rel)
    if (!byFlat.has(flat)) byFlat.set(flat, [])
    byFlat.get(flat).push(rel)
  }
  // Kalau satu nama flat dipakai >1 path, beri akhiran hash path masing-masing.
  const finalFlat = new Map()   // rel -> nama asset
  let disambiguated = 0
  for (const [flat, group] of byFlat) {
    if (group.length === 1) { finalFlat.set(group[0], flat); continue }
    for (const rel of group) {
      finalFlat.set(rel, `${flat}-${pathTag(rel)}`)
      disambiguated++
    }
  }
  if (disambiguated) {
    console.log(`  ! ${disambiguated} nama asset bertabrakan setelah sanitasi → diberi akhiran hash path`)
  }

  // ---- pass 2: sha256 streaming + susun entri ----
  const files = []
  const tsv = []
  let total = 0
  let n = 0
  const oversized = []

  for (const { full, rel } of rels) {
    const st = await stat(full)
    const flat = finalFlat.get(rel)

    if (st.size > GH_LIMIT) oversized.push(`${rel} (${mb(st.size)} MB > 2 GB)`)

    const sha = await sha256File(full)
    files.push({
      name: rel.split('/').pop(),
      size: String(st.size),                                  // string, sama seperti Alyn
      path: rel,                                              // lokasi simpan di HP
      url: `https://github.com/${USER}/${REPO}/releases/download/${TAG}/${flat}`,
      sha256: sha,
    })
    tsv.push(`${rel}\t${flat}\t${st.size}`)

    total += st.size
    n++
    if (n % 25 === 0) process.stdout.write(`\r  hashing… ${n} file, ${mb(total)} MB   `)
  }
  process.stdout.write('\r' + ' '.repeat(60) + '\r')

  if (oversized.length) {
    console.error('✗ File ini melewati batas 2 GB per asset GitHub Release:')
    oversized.forEach((o) => console.error('    ' + o))
    console.error('  Pecah dulu jadi beberapa bagian, atau pakai jalur lain (lihat OPSI-GRATIS.md).')
    process.exit(1)
  }

  // Besar duluan: kalau download paralel, file besar mulai lebih awal
  // supaya tidak jadi ekor yang menahan progress di 99%.
  files.sort((a, b) => Number(b.size) - Number(a.size) || a.path.localeCompare(b.path))
  tsv.sort((a, b) => Number(b.split('\t')[2]) - Number(a.split('\t')[2]))

  const manifest = {
    version: TAG,
    source: `github:${USER}/${REPO}@${TAG}`,
    generated_at: new Date().toISOString(),
    total_files: files.length,
    total_bytes: String(total),
    files,
  }

  await writeFile(OUT, JSON.stringify(manifest), 'utf8')
  await writeFile(TSV, tsv.join('\n') + '\n', 'utf8')

  // config.json = pengganti endpoint /api/update milik Alyn.
  // Karena kita tidak punya server, manifest & config disajikan sebagai file
  // statis lewat jsDelivr (CDN gratis, file kecil) dengan raw.githubusercontent
  // sebagai cadangan. Client membaca `game_files` dari sini, jadi kamu bisa
  // pindah CDN kapan pun TANPA merilis ulang APK.
  const jsd = `https://cdn.jsdelivr.net/gh/${USER}/${REPO}@main`
  const raw = `https://raw.githubusercontent.com/${USER}/${REPO}/main`
  const config = {
    status: true,                                  // KILL SWITCH -> set false saat darurat
    game_version: TAG,
    game_url: `https://github.com/${USER}/${REPO}/releases/download/${TAG}/SanzzClient-${TAG}-arm64.apk`,
    game_files: `${jsd}/manifest.json`,
    game_files_fallback: [`${raw}/manifest.json`],
    changelog: `${jsd}/changelog.txt`,
    servers: process.env.SERVERS_URL || '',
    manifest_schema: 2,
    source: `github:${USER}/${REPO}@${TAG}`,
    generated_at: new Date().toISOString(),
  }
  await writeFile(CFG, JSON.stringify(config, null, 2) + '\n', 'utf8')

  console.log(`✓ ${files.length} file · ${mb(total)} MB (${(total / 1024 ** 3).toFixed(3)} GB)`)
  console.log(`✓ manifest      → ${OUT}`)
  console.log(`✓ daftar upload → ${TSV}`)
  console.log(`✓ config.json   → ${CFG}   (pengganti /api/update)`)
  console.log(`✓ base release  → https://github.com/${USER}/${REPO}/releases/tag/${TAG}`)
  console.log('')
  console.log('  5 file terbesar:')
  for (const f of files.slice(0, 5)) console.log(`    ${mb(Number(f.size)).padStart(8)} MB  ${f.path}  →  ${f.url.split('/').pop()}`)
  console.log('')
  console.log('  Langkah berikutnya:')
  console.log(`    cp ${CFG} config.json && cp ${OUT} manifest.json   (ke root repo)`)
  console.log(`    ./github-release-upload.sh ${USER} ${REPO} ${TAG} ${ROOT}`)
  console.log(`    git add manifest.json config.json && git commit -m "asset ${TAG}" && git push`)
  console.log('')
  console.log('  JANGAN LUPA: isi parameter mirrors di AssetDownloader.kt dengan sumber kedua.')
  console.log('  Repo publik + asset berhak cipta Rockstar = risiko DMCA nyata. Mirror itu wajib.')
}

main().catch((e) => { console.error('✗', e); process.exit(1) })
