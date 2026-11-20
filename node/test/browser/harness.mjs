// Runs a page from this package in headless Chrome and returns the JSON the page POSTs to
// `/result`. No browser automation dependency: a static server plus Chrome's headless mode.
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, extname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json',
  '.wasm': 'application/wasm',
}

const CANDIDATES = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
]

export function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH
  return CANDIDATES.find((path) => existsSync(path))
}

function serve(root, onResult) {
  return createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost')
    if (request.method === 'POST' && url.pathname === '/result') {
      const parts = []
      for await (const part of request) parts.push(part)
      response.writeHead(204).end()
      onResult(JSON.parse(Buffer.concat(parts).toString('utf8')))
      return
    }
    const path = resolve(root, '.' + decodeURIComponent(url.pathname))
    if (!path.startsWith(root + sep)) {
      response.writeHead(403).end()
      return
    }
    try {
      const body = await readFile(path)
      response.writeHead(200, { 'content-type': TYPES[extname(path)] ?? 'application/octet-stream' }).end(body)
    } catch {
      response.writeHead(404).end()
    }
  })
}

/** Opens `page` (a path relative to `root`, by default the package) and resolves with what it reports. */
export async function runPage(page, { timeoutMs = 120_000, root = ROOT } = {}) {
  const chrome = findChrome()
  if (chrome === undefined) throw new Error('Chrome not found; set CHROME_PATH')

  let settle
  const result = new Promise((resolve, reject) => (settle = { resolve, reject }))
  const server = serve(resolve(root), (body) => settle.resolve(body))
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const profile = await mkdtemp(join(tmpdir(), 'hypaxel-chrome-'))
  const browser = spawn(
    chrome,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-extensions',
      `--user-data-dir=${profile}`,
      // Ubuntu 24.04 CI runners block the user namespaces Chrome's sandbox needs; the pages are our own.
      ...(process.env.CI ? ['--no-sandbox'] : []),
      `http://127.0.0.1:${server.address().port}/${page}`,
    ],
    { stdio: 'ignore' },
  )
  browser.on('exit', (code) => settle.reject(new Error(`Chrome exited with code ${code} before the page reported`)))
  const timer = setTimeout(() => settle.reject(new Error(`no result from ${page} within ${timeoutMs} ms`)), timeoutMs)
  try {
    return await result
  } finally {
    clearTimeout(timer)
    browser.removeAllListeners('exit')
    browser.kill()
    await new Promise((resolve) => server.close(resolve))
    await rm(profile, { recursive: true, force: true, maxRetries: 3 }).catch(() => {})
  }
}

export function chromeVersion() {
  const chrome = findChrome()
  return new Promise((resolve) => {
    const child = spawn(chrome, ['--version'])
    let out = ''
    child.stdout.on('data', (data) => (out += data))
    child.on('close', () => resolve(out.trim()))
    child.on('error', () => resolve('unknown'))
  })
}
