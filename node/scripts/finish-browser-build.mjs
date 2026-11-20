// Completes lib/browser after tsc: copies the wasm-bindgen output next to the compiled entry and
// marks the directory as ES modules, which the rest of the package (CommonJS) is not.
import { copyFile, mkdir, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const from = join(root, 'ts', 'wasm')
const to = join(root, 'lib', 'browser', 'wasm')

await mkdir(to, { recursive: true })
for (const file of ['hypaxel.js', 'hypaxel.d.ts', 'hypaxel_bg.wasm']) {
  await copyFile(join(from, file), join(to, file))
}
await writeFile(join(root, 'lib', 'browser', 'package.json'), JSON.stringify({ type: 'module' }, null, 2) + '\n')
