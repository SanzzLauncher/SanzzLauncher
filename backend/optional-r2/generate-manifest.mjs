#!/usr/bin/env node
/**
 * Generator manifest asset — meniru skema GET https://alynsampmobile.pro/api/game-files
 * tapi ditambah `sha256` (Alyn tidak punya; itu kelemahan mereka).
 *
 * Pakai:
 *   node generate-manifest.mjs <folderAsset> [publicBaseUrl] [outFile] [versi]
 *
 * Contoh:
 *   node generate-manifest.mjs ./game-files https://cdn.client-mu.example ./manifest.json 1.0.0
 *
 * Node 18+. Tanpa dependency eksternal.
 *
 * Yang dilakukan:
 *   1. walk rekursif, lewati .part / .DS_Store / hidden
 *   2. sha256 STREAMING (wajib — file 270 MB tidak boleh masuk memori)
 *   3. tulis manifest.json dengan `size` sebagai STRING (kompatibel skema Alyn)
 *   4. kalau path mengandung segmen versi, URL ikut berversi (lihat BACKEND-KEBUTUHAN.md §5)
 */

import { createReadStream } from 'node:fs'
import { readdir, stat, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, relative, sep } from 'node:path'

const ROOT = process.argv[2] ?? './game-files'
const PUBLIC_BASE = (process.argv[3] ?? 'https://cdn.client-mu.example').replace(/\/+$/, '')
const OUT = process.argv[4] ?? './manifest.json'
const VERSION = process.argv[5] ?? '1.0.0'

const SKIP = new Set(['.DS_Store', 'Thumbs.db', '.gitkeep'])

/** sha256 streaming — hemat memori untuk file ratusan MB. */
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
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || SKIP.has(entry.name)) continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) yield* walk(full)
    else if (entry.isFile()) {
      if (entry.name.endsWith('.part') || entry.name.endsWith('.tmp')) continue
      yield full
    }
  }
}

const mb = (b) => (b / 1048576).toFixed(1)

async function main() {
  const started = Date.now()
  let rootStat
  try {
    rootStat = await stat(ROOT)
  } catch {
    console.error(`✗ Folder tidak ditemukan: ${ROOT}`)
    process.exit(1)
  }
  if (!rootStat.isDirectory()) {
    console.error(`✗ Bukan folder: ${ROOT}`)
    process.exit(1)
  }

  const files = []
  let totalBytes = 0
  let n = 0

  for await (const full of walk(ROOT)) {
    // path pakai "/" selalu, jangan sep — Windows memisah dengan "\"
    const rel = relative(ROOT, full).split(sep).join('/')
    const st = await stat(full)
    const sha = await sha256File(full)

    files.push({
      name: rel.split('/').pop(),
      // STRING, bukan angka — persis skema Alyn. AssetApi.kt menerima keduanya.
      size: String(st.size),
      path: rel,
      url: `${PUBLIC_BASE}/game-files/${rel}`,
      sha256: sha,
    })

    totalBytes += st.size
    n++
    if (n % 25 === 0) process.stdout.write(`\r  hashing… ${n} file, ${mb(totalBytes)} MB   `)
  }
  process.stdout.write('\r' + ' '.repeat(60) + '\r')

  if (files.length === 0) {
    console.error(`✗ Tidak ada file di ${ROOT}`)
    process.exit(1)
  }

  // Urutkan: file besar duluan. Kalau download paralel, file besar paling baik
  // dimulai lebih awal supaya tidak jadi ekor yang menahan progress di 99%.
  files.sort((a, b) => Number(b.size) - Number(a.size) || a.path.localeCompare(b.path))

  const manifest = {
    version: VERSION,
    generated_at: new Date().toISOString(),
    total_files: files.length,
    total_bytes: String(totalBytes),
    files,
  }

  await writeFile(OUT, JSON.stringify(manifest), 'utf8')

  const elapsed = ((Date.now() - started) / 1000).toFixed(1)
  console.log(`✓ ${files.length} file · ${mb(totalBytes)} MB (${(totalBytes / 1024 ** 3).toFixed(3)} GB)`)
  console.log(`✓ manifest → ${OUT}  (${mb((await stat(OUT)).size)} MB, ${elapsed}s)`)
  console.log(`✓ base URL  → ${PUBLIC_BASE}/game-files/`)
  console.log('')
  console.log('  5 file terbesar:')
  for (const f of files.slice(0, 5)) console.log(`    ${mb(Number(f.size)).padStart(8)} MB  ${f.path}`)
  console.log('')
  console.log('  Langkah berikutnya:')
  console.log(`    wrangler r2 object put sanzz-assets/manifest.json --file=${OUT} --content-type=application/json`)
  console.log('    (atau jalankan ./deploy.sh)')
}

main().catch((e) => {
  console.error('✗', e)
  process.exit(1)
})
