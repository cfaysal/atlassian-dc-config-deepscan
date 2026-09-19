import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'

const source = readFileSync(new URL('../../jira/structuredoctor/DoctorRenderer.groovy', import.meta.url), 'utf8')
// Decode the escapes used in the Groovy triple-single-quoted browser literal.
const script = source.match(/private static String browserScript\(\) \{\s*'''([\s\S]*?)'''/)[1]
  .replace(/\\\\/g, '\\')

test('full response documents rebind analysis and plan without global redeclaration', async () => {
  const requests = []
  const alerts = []
  let elements
  let failNext = false
  let context
  const selected = { value: 'group-1', closest: () => ({
    querySelector: () => ({ value: 'occurrence-1' }), querySelectorAll: () => [],
  }) }
  const document = {
    getElementById: id => elements[id],
    querySelectorAll: selector => selector.startsWith('.finding:') ? [selected] : [],
    querySelector: () => ({ dataset: { snapshotId: 'snapshot-1' } }),
    open() { reset() },
    write(text) { assert.equal(text, 'FULL_DOCUMENT'); vm.runInContext(script, context) },
    close() {},
  }
  function reset() {
    elements = Object.fromEntries(['analyzeButton', 'planButton', 'structureId', 'issueKeyFilter',
      'auditDays', 'ruleExportFile', 'auditExportFile'].map(id => [id, {
      value: id === 'auditDays' ? '30' : '', files: [], disabled: false,
      addEventListener(event, handler) { this.handler = handler },
    }]))
  }
  reset()
  context = vm.createContext({ document, window: {
    location: { pathname: '/jira/rest/scriptrunner/latest/custom/structureIssueDoctor' },
    alert: text => alerts.push(text),
  }, fetch: async (url, request) => {
    requests.push({ url, payload: JSON.parse(request.body) })
    const ok = !failNext
    failNext = false
    return { ok, text: async () => ok ? 'FULL_DOCUMENT' : 'RETRYABLE_ERROR' }
  } })
  vm.runInContext(script, context)
  for (const structureId of ['1', '2']) {
    elements.structureId.value = structureId
    await elements.analyzeButton.handler()
    assert.deepEqual(alerts, [])
    assert.equal(typeof elements.analyzeButton.handler, 'function')
    assert.equal(elements.analyzeButton.disabled, false)
  }
  await elements.planButton.handler()
  elements.structureId.value = '1'
  failNext = true
  await elements.analyzeButton.handler()
  assert.deepEqual(alerts, ['RETRYABLE_ERROR'])
  assert.equal(elements.analyzeButton.disabled, false)
  await elements.analyzeButton.handler()
  assert.deepEqual(requests.map(r => r.payload.structureId || 'plan'), ['1', '2', 'plan', '1', '1'])
  assert.ok(requests.every(r => r.url.startsWith('/jira/rest/scriptrunner/latest/custom/structureIssueDoctor')))
})
