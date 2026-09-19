import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const generator = join(repoRoot, 'tools', 'build-structure-doctor-bundle.mjs')
const controller = join(repoRoot, 'jira', 'structureIssueDoctor.groovy')
const moduleDir = join(repoRoot, 'jira', 'structuredoctor')

const toRepoPath = file => relative(repoRoot, file).split(sep).join('/')
const modules = readdirSync(moduleDir, { withFileTypes: true })
  .filter(entry => entry.isFile() && entry.name.endsWith('.groovy'))
  .map(entry => join(moduleDir, entry.name))
  .sort((left, right) => {
    const leftName = basename(left)
    const rightName = basename(right)
    return leftName < rightName ? -1 : leftName > rightName ? 1 : 0
  })
const expectedInputs = [controller, ...modules].map(toRepoPath)

function runGenerator(args) {
  return spawnSync(process.execPath, [generator, ...args], {
    cwd: repoRoot,
    encoding: 'utf8',
  })
}

function count(text, pattern) {
  return [...text.matchAll(pattern)].length
}

function expectedBody(file) {
  const lines = readFileSync(file, 'utf8').replace(/\r\n?/g, '\n').split('\n')
    .filter(line => !/^\s*(?:package|import)\s+/.test(line))
  while (lines.length && !lines[0].trim()) lines.shift()
  while (lines.length && !lines.at(-1).trim()) lines.pop()
  return lines.join('\n')
}

test('bundles the exact maintained sources deterministically', () => {
  assert.equal(modules.length, 33, 'the maintained module inventory changed')
  const dir = mkdtempSync(join(tmpdir(), 'structure-doctor-bundle-'))
  try {
    const first = join(dir, 'first.groovy')
    const second = join(dir, 'second.groovy')
    const firstRun = runGenerator(['--output', first])
    assert.equal(firstRun.status, 0, firstRun.stderr || firstRun.stdout)
    const secondRun = runGenerator(['--output', second])
    assert.equal(secondRun.status, 0, secondRun.stderr || secondRun.stdout)

    const firstBytes = readFileSync(first)
    const secondBytes = readFileSync(second)
    assert.deepEqual(firstBytes, secondBytes)
    const bundle = firstBytes.toString('utf8')
    assert.ok(bundle.endsWith('\n'))
    assert.ok(!bundle.endsWith('\n\n'))
    assert.ok(!bundle.includes('\r'))

    assert.equal(count(bundle, /^package structuredoctor$/gm), 1)
    const imports = [...bundle.matchAll(/^import(?: static)? .+$/gm)].map(match => match[0])
    assert.deepEqual(imports, [...new Set(imports)])

    const manifestInputs = [...bundle.matchAll(/^ \* INPUT (.+)$/gm)].map(match => match[1])
    assert.deepEqual(manifestInputs, expectedInputs)
    assert.equal(count(bundle, /^ \* INPUT_SHA256 [a-f0-9]{64}$/gm), 1)
    const sourceMarkers = [...bundle.matchAll(/^\/\/ SOURCE: (.+)$/gm)].map(match => match[1])
    assert.deepEqual(sourceMarkers, [...expectedInputs.slice(1), expectedInputs[0]])
    for (const file of [...modules, controller]) {
      assert.ok(bundle.includes(`// SOURCE: ${toRepoPath(file)}\n${expectedBody(file)}`))
    }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('preserves endpoint and repair security boundaries', () => {
  const dir = mkdtempSync(join(tmpdir(), 'structure-doctor-security-'))
  try {
    const output = join(dir, 'doctor.groovy')
    const result = runGenerator(['--output', output])
    assert.equal(result.status, 0, result.stderr || result.stdout)
    const bundle = readFileSync(output, 'utf8')

    assert.equal(count(bundle, /^structureIssueDoctor\w*\(httpMethod:/gm), 6)
    assert.equal(count(bundle, /groups: \["jira-administrators"\]/g), 6)
    const authenticationGate = /if \(user == null\) return respondJson\.call\(401, \[ok: false, error: 'AUTHENTICATION_REQUIRED'\]\)/g
    assert.equal(count(bundle, authenticationGate), count(readFileSync(controller, 'utf8'), authenticationGate))
    assert.match(bundle, /new DisabledRepairInfrastructure\(\)/)
    assert.match(bundle, /SET_PARENT_LINK/)
    assert.doesNotMatch(bundle, /structure-doctor-(?:capability|mutation)-probe/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('check mode detects drift without overwriting the artifact', () => {
  const dir = mkdtempSync(join(tmpdir(), 'structure-doctor-check-'))
  try {
    const output = join(dir, 'doctor.groovy')
    const generated = runGenerator(['--output', output])
    assert.equal(generated.status, 0, generated.stderr || generated.stdout)
    const drifted = `${readFileSync(output, 'utf8')}// local drift\n`
    writeFileSync(output, drifted, 'utf8')

    const stale = runGenerator(['--check', '--output', output])
    assert.notEqual(stale.status, 0)
    assert.match(`${stale.stdout}${stale.stderr}`, /stale/i)
    assert.equal(readFileSync(output, 'utf8'), drifted)

    const refreshed = runGenerator(['--output', output])
    assert.equal(refreshed.status, 0, refreshed.stderr || refreshed.stdout)
    const current = runGenerator(['--check', '--output', output])
    assert.equal(current.status, 0, current.stderr || current.stdout)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
