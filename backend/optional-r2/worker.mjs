/**
 * Cloudflare Worker — endpoint JSON untuk SanzzClient.
 *
 * PENTING: Worker ini TIDAK melayani file asset. File 1,34 GB dilayani langsung
 * dari custom domain bucket R2 (lihat BACKEND-KEBUTUHAN.md §4). Worker cuma
 * untuk 3 endpoint kecil ini — itu sebabnya biayanya tetap ~$0.
 *
 * Endpoint:
 *   GET /api/update       → bootstrap config (kill switch ada di sini)
 *   GET /api/game-files   → manifest, dibaca dari R2 key `manifest.json`
 *   GET /api/game-files?prefix=texdb/,textures/   → subset (mode "lite")
 *   GET /api/changelog    → isi R2 key `changelog.txt`
 *   GET /health           → cek hidup
 *
 * Deploy: wrangler deploy
 */

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,HEAD,OPTIONS',
  'access-control-allow-headers': 'Content-Type, User-Agent',
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url)
    const path = url.pathname.replace(/\/{2,}/g, '/').replace(/\/+$/, '') || '/'

    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: JSON_HEADERS })
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return json({ error: 'Method not allowed' }, 405)
    }

    try {
      switch (path) {
        case '/api/update':
          return json(updatePayload(env))

        case '/api/game-files':
          return await manifestResponse(env, url)

        case '/api/changelog':
          return await changelogResponse(env)

        case '/health':
          return json({ ok: true, version: env.GAME_VERSION ?? null, ts: Date.now() })

        default:
          return json({ error: 'Not found' }, 404)
      }
    } catch (e) {
      return json({ error: 'Internal error', detail: String(e?.message ?? e) }, 500)
    }
  },
}

// ---------------------------------------------------------------------------

function updatePayload(env) {
  const base = (env.PUBLIC_BASE ?? '').replace(/\/+$/, '')
  const api = (env.API_ORIGIN ?? base).replace(/\/+$/, '')
  return {
    // KILL SWITCH. Set "false" di wrangler.toml → semua client berhenti mencoba.
    // Gunanya saat CDN bermasalah: mencegah badai retry dari ribuan device.
    status: String(env.SERVICE_ONLINE ?? 'true') === 'true',

    game_version: env.GAME_VERSION ?? '0.0.0',
    game_url: env.APK_URL || `${base}/apk/SanzzClient-latest.apk`,

    // Client membaca field ini untuk tahu manifest ada di mana.
    // Sama seperti Alyn: "game_files": "https://…/api/game-files"
    game_files: `${api}/api/game-files`,

    changelog: `${api}/api/changelog`,
    servers: env.SERVERS_URL || `${api}/api/servers`,

    // Opsional — biar client lama tidak kaget kalau field baru ditambah
    manifest_schema: 2,
  }
}

async function manifestResponse(env, url) {
  const obj = await env.ASSETS.get('manifest.json')
  if (!obj) {
    return json(
      { error: 'Manifest belum di-upload. Jalankan: wrangler r2 object put sanzz-assets/manifest.json --file=manifest.json' },
      503,
    )
  }

  let body = await obj.text()

  // Filter mode "lite" — client bisa minta subset supaya install awal lebih ringan.
  // Contoh: /api/game-files?prefix=texdb/,textures/  → hanya grup terbesar
  const prefixParam = url.searchParams.get('prefix')
  if (prefixParam) {
    const prefixes = prefixParam.split(',').map((s) => s.trim()).filter(Boolean)
    if (prefixes.length) {
      const parsed = JSON.parse(body)
      const files = (parsed.files ?? []).filter((f) => prefixes.some((p) => f.path.startsWith(p)))
      parsed.files = files
      parsed.total_files = files.length
      parsed.total_bytes = String(files.reduce((a, f) => a + Number(f.size || 0), 0))
      parsed.filtered_by = prefixes
      body = JSON.stringify(parsed)
    }
  }

  return new Response(body, {
    status: 200,
    headers: {
      ...JSON_HEADERS,
      // Manifest HARUS cepat terlihat saat kamu update. Alyn pakai max-age=120.
      'cache-control': 'public, max-age=60, stale-while-revalidate=300',
      etag: obj.httpEtag ?? `"${obj.etag}"`,
      'last-modified': obj.uploaded?.toUTCString?.() ?? new Date().toUTCString(),
    },
  })
}

async function changelogResponse(env) {
  const obj = await env.ASSETS.get('changelog.txt')
  const content = obj ? await obj.text() : (env.CHANGELOG_FALLBACK ?? 'Belum ada catatan rilis.')
  return json(
    { success: true, version: env.GAME_VERSION ?? '', content },
    200,
    { 'cache-control': 'public, max-age=300' },
  )
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...JSON_HEADERS, ...extraHeaders },
  })
}
