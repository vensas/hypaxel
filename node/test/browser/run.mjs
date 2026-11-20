// Runs the browser test page in headless Chrome. Requires `npm run build` first.
import { runPage } from './harness.mjs'

const { userAgent, checks } = await runPage('test/browser/index.html')
console.log(userAgent)
for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok    ' : 'FAILED'} ${name}${!ok && detail ? `\n       ${detail}` : ''}`)
}
const failed = checks.filter((c) => !c.ok).length
console.log(`${checks.length - failed}/${checks.length} checks passed`)
if (failed > 0 || checks.length === 0) process.exit(1)
