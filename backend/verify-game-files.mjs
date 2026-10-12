#!/usr/bin/env node
/**
 * Pemeriksa folder asset — menjawab "folder game-files/ gue udah lengkap belum?"
 * dalam satu perintah, SEBELUM kamu upload 1,34 GB dan menyesal.
 *
 * Membandingkan isi folder dengan backend/game-files-spec.json (295 entri).
 * Script ini TIDAK mengunduh apa pun — dia cuma membaca disk lokal.
 *
 * Pakai:
 *   node verify-game-files.mjs [folder] [spec]
 *   node verify-game-files.mjs ../game-files
 *   node verify-game-files.mjs ../game-files --json      # output mesin
 *   node verify-game-files.mjs ../game-files --list      # tampilkan semua yang kurang
 *
 * Exit code:
 *   0  lengkap, siap upload
 *   1  ada yang kurang / ukurannya beda
 *   2  folder atau spec tidak ditemukan
 */

import { readdir, stat } from 'node:fs/promises'
import { join, relative, sep } from 'node:path'

const args = process.argv.slice(2)
const FLAGS = new Set(args.filter((a) => a.startsWith('--')))
const POS = args.filter((a) => !a.startsWith('--'))

const ROOT = POS[0] ?? '../game-files'
const SPEC = POS[1] ?? new URL('./game-files-spec.json', import.meta.url).pathname
const AS_JSON = FLAGS.has('--json')
const LIST_ALL = FLAGS.has('--list')

const SKIP = new Set(['.DS_Store', 'Thumbs.db', '.gitkeep', 'README.md'])
const mb = (b) => (b / 1048576).toFixed(1)
const pad = (s, n) => String(s).padEnd(n)

async function* walk(dir) {
  let entries
  try { entries = await readdir(dir, { withFileTypes: true }) } catch { return }
  for (const e of entries) {
    if (e.name.startsWith('.') || SKIP.has(e.name)) continue
    const full = join(dir, e.name)
    if (e.isDirectory()) yield* walk(full)
    else if (e.isFile() && !e.name.endsWith('.part')) yield full
  }
}

const out = (s = '') => { if (!AS_JSON) process.stdout.write(s + '\n') }

async function main() {
  let spec
  try { spec = JSON.parse(await (await import('node:fs/promises')).readFile(SPEC, 'utf8')) }
  catch { console.error(`✗ spec tidak ditemukan: ${SPEC}`); process.exit(2) }

  let rootStat
  try { rootStat = await stat(ROOT) } catch { console.error(`✗ folder tidak ditemukan: ${ROOT}`); process.exit(2) }
  if (!rootStat.isDirectory()) { console.error(`✗ bukan folder: ${ROOT}`); process.exit(2) }

  // ---- baca disk ----
  const local = new Map()
  for await (const full of walk(ROOT)) {
    const rel = relative(ROOT, full).split(sep).join('/')
    const st = await stat(full)
    local.set(rel, st.size)
  }

  // ---- bandingkan ----
  const missing = [], wrongSize = [], ok = []
  for (const f of spec.files) {
    if (!local.has(f.path)) missing.push(f)
    else if (local.get(f.path) !== f.size) wrongSize.push({ ...f, actual: local.get(f.path) })
    else ok.push(f)
  }

  const specPaths = new Set(spec.files.map((f) => f.path))
  const extra = [...local.keys()].filter((p) => !specPaths.has(p))

  const okBytes = ok.reduce((a, f) => a + f.size, 0)
  const missingBytes = missing.reduce((a, f) => a + f.size, 0)
  const wrongBytes = wrongSize.reduce((a, f) => a + f.size, 0)
  const totalBytes = spec.total_bytes
  const complete = missing.length === 0 && wrongSize.length === 0

  if (AS_JSON) {
    console.log(JSON.stringify({
      complete,
      root: ROOT,
      expected: { files: spec.total_files, bytes: totalBytes },
      present: { files: ok.length, bytes: okBytes },
      missing: LIST_ALL ? missing : missing.length,
      wrong_size: LIST_ALL ? wrongSize : wrongSize.length,
      extra,
      percent_bytes: +((okBytes / totalBytes) * 100).toFixed(2),
    }, null, 2))
    process.exit(complete ? 0 : 1)
  }

  out('')
  out(`  Folder : ${ROOT}`)
  out(`  Spec   : ${spec.total_files} file · ${mb(totalBytes)} MB (${spec.total_gb} GB)`)
  out('')
  out('  ── Hasil ─────────────────────────────────────────────')
  out(`  ✓ lengkap & ukuran cocok   ${pad(ok.length, 5)} file   ${pad(mb(okBytes), 9)} MB`)
  if (wrongSize.length) out(`  ✗ ada tapi ukuran beda     ${pad(wrongSize.length, 5)} file   ${pad(mb(wrongBytes), 9)} MB`)
  if (missing.length)   out(`  ✗ tidak ada                ${pad(missing.length, 5)} file   ${pad(mb(missingBytes), 9)} MB`)
  if (extra.length)     out(`  ! ekstra (tidak di spec)   ${pad(extra.length, 5)} file`)
  out('')
  out(`  Progress byte : ${(okBytes / totalBytes * 100).toFixed(2)}%`)

  // Bar sederhana biar kelihatan kurang berapa
  const W = 46
  const filled = Math.round((okBytes / totalBytes) * W)
  out(`  [${'█'.repeat(filled)}${'·'.repeat(W - filled)}] ${mb(totalBytes - okBytes)} MB lagi`)
  out('')

  // ---- dua path yang paling sering salah ----
  const traps = [
    ['models/gta3.img', 'texdb/gta3.img'],
    ['texdb/gta3.txt', 'texdb/gta3/gta3.txt'],
  ]
  const trapHit = traps.filter(([wrong, right]) => local.has(wrong) && !local.has(right))
  if (trapHit.length) {
    out('  ⚠ KELIHATANNYA KAMU KENA JEBAKAN PATH YANG INI:')
    for (const [wrong, right] of trapHit) {
      out(`      kamu punya  "${wrong}"   ← path ini TIDAK ADA di spec`)
      out(`      seharusnya  "${right}"`)
    }
    out('    Ini sebabnya checkGameIntegrity lama selalu bilang "Perlu Setup".')
    out('')
  }

  // ---- detail yang kurang ----
  if (wrongSize.length) {
    out(`  ✗ UKURAN BEDA (${wrongSize.length}) — file korup atau versi lain:`)
    for (const f of (LIST_ALL ? wrongSize : wrongSize.slice(0, 12))) {
      out(`      ${pad(f.path, 44)} disk=${pad(f.actual, 11)} spec=${f.size}`)
    }
    if (!LIST_ALL && wrongSize.length > 12) out(`      … +${wrongSize.length - 12} lagi (pakai --list)`)
    out('')
  }

  if (missing.length) {
    out(`  ✗ BELUM ADA (${missing.length}) — diurutkan dari yang terbesar:`)
    const sorted = [...missing].sort((a, b) => b.size - a.size)
    for (const f of (LIST_ALL ? sorted : sorted.slice(0, 20))) {
      out(`      ${pad(mb(f.size).padStart(8), 9)} MB  ${f.path}`)
    }
    if (!LIST_ALL && sorted.length > 20) out(`      … +${sorted.length - 20} lagi (pakai --list)`)
    out('')
    // ringkasan per grup supaya cepat kelihatan kurang apa
    const byDir = new Map()
    for (const f of missing) {
      const d = f.path.split('/')[0]
      byDir.set(d, (byDir.get(d) ?? 0) + f.size)
    }
    out('    kurang per grup:')
    for (const [d, b] of [...byDir].sort((a, c) => c[1] - a[1])) {
      out(`      ${pad(mb(b).padStart(9))} MB  ${d}/`)
    }
    out('')
  }

  if (extra.length && LIST_ALL) {
    out(`  ! EKSTRA (${extra.length}) — tidak di spec, tidak akan di-upload client:`)
    for (const p of extra.slice(0, 20)) out(`      ${p}`)
    out('')
  }

  if (complete) {
    out('  ✅ LENGKAP. Siap lanjut:')
    out(`       node generate-github-manifest.mjs ${ROOT} SanzzLauncher SanzzLauncher v1.0.0 ../manifest.json`)
    out(`       ./github-release-upload.sh SanzzLauncher SanzzLauncher v1.0.0 ${ROOT} 8`)
    out('')
    process.exit(0)
  } else {
    out('  ⛔ BELUM LENGKAP — jangan upload dulu.')
    out('     Manifest yang dibuat dari folder tidak lengkap akan membuat client')
    out('     mengunduh sebagian saja, lalu gagal verifikasi tanpa pesan yang jelas.')
    out('')
    process.exit(1)
  }
}

main().catch((e) => { console.error('✗', e); process.exit(2) })
