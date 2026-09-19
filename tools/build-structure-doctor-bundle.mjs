import { createHash, randomUUID } from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const defaultOutput = join(repoRoot, 'dist', 'structuredoctor', 'structureIssueDoctor.groovy')

function parseArgs(argv) {
  let check = false
  let output = null
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--check') {
      if (check) throw new Error('Duplicate --check')
      check = true
    } else if (arg === '--output') {
      if (output !== null) throw new Error('Duplicate --output')
      if (index + 1 >= argv.length) throw new Error('Missing value for --output')
      output = argv[index += 1]
    } else {
      throw new Error(`Unknown argument: ${arg}`)
    }
  }
  return { check, output }
}

function discoverSources() {
  const controllerPath = join(repoRoot, 'jira', 'structureIssueDoctor.groovy')
  const moduleDir = join(repoRoot, 'jira', 'structuredoctor')
  const modulePaths = readdirSync(moduleDir, { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.groovy'))
    .map(entry => join(moduleDir, entry.name))
    .sort((left, right) => {
      const leftName = basename(left)
      const rightName = basename(right)
      return leftName < rightName ? -1 : leftName > rightName ? 1 : 0
    })

  if (!statSync(controllerPath).isFile()) throw new Error('Controller is not a regular file')
  if (modulePaths.length !== 33) {
    throw new Error(`Expected 33 Structure Doctor modules, found ${modulePaths.length}`)
  }
  return { controllerPath, modulePaths }
}

function repoPath(file) {
  return relative(repoRoot, file).split(sep).join('/')
}

function parseGroovySource(file, isController) {
  const relativePath = repoPath(file)
  const normalized = readFileSync(file, 'utf8').replace(/\r\n?/g, '\n')
  if (!normalized.trim()) throw new Error(`Blank input: ${relativePath}`)

  const lines = normalized.split('\n')
  const packages = lines.filter(line => /^\s*package\s+/.test(line))
  if (isController) {
    if (packages.length !== 0) throw new Error(`Controller declares a package: ${relativePath}`)
  } else if (packages.length !== 1 || packages[0].trim() !== 'package structuredoctor') {
    throw new Error(`Expected exactly package structuredoctor: ${relativePath}`)
  }

  const imports = lines
    .filter(line => /^\s*import\s+/.test(line))
    .map(line => line.trim())
  const bodyLines = lines.filter(line => !/^\s*(?:package|import)\s+/.test(line))
  while (bodyLines.length && !bodyLines[0].trim()) bodyLines.shift()
  while (bodyLines.length && !bodyLines.at(-1).trim()) bodyLines.pop()
  const body = bodyLines.join('\n')
  if (!body) throw new Error(`Empty body: ${relativePath}`)
  if (/^\s*(?:package|import)\s+/m.test(body)) {
    throw new Error(`Residual package or import declaration: ${relativePath}`)
  }
  return { relativePath, normalized, imports: [...new Set(imports)], body }
}

function fingerprint(sources) {
  const hash = createHash('sha256')
  for (const source of sources) {
    hash.update(source.relativePath)
    hash.update('\0')
    hash.update(source.normalized)
    hash.update('\0')
  }
  return hash.digest('hex')
}

function renderBundle(controller, modules) {
  const manifestOrder = [controller, ...modules]
  const bodyOrder = [...modules, controller]
  const imports = [...new Set(manifestOrder.flatMap(source => source.imports))]
    .map(line => line.startsWith('import static structuredoctor.')
      ? line.replace('import static structuredoctor.', 'import static ')
      : line)
    .filter(line => !line.startsWith('import structuredoctor.'))
    .sort()
  const header = [
    '/*',
    ' * GENERATED FILE - DO NOT EDIT.',
    ' * Source: tools/build-structure-doctor-bundle.mjs',
    ...manifestOrder.map(source => ` * INPUT ${source.relativePath}`),
    ` * INPUT_SHA256 ${fingerprint(manifestOrder)}`,
    ' */',
  ].join('\n')
  const bodies = bodyOrder.map(source => `// SOURCE: ${source.relativePath}\n${source.body}`)
  return [header, imports.join('\n'), ...bodies]
    .filter(section => section.length > 0)
    .join('\n\n') + '\n'
}

function resolveOutput(value) {
  if (!value) return defaultOutput
  return isAbsolute(value) ? value : resolve(process.cwd(), value)
}

function writeAtomically(target, bytes) {
  mkdirSync(dirname(target), { recursive: true })
  const temporary = join(dirname(target), `.${basename(target)}.${process.pid}.${randomUUID()}.tmp`)
  try {
    writeFileSync(temporary, bytes, { flag: 'wx' })
    renameSync(temporary, target)
  } finally {
    if (existsSync(temporary)) rmSync(temporary)
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2))
  const { controllerPath, modulePaths } = discoverSources()
  const controller = parseGroovySource(controllerPath, true)
  const modules = modulePaths.map(file => parseGroovySource(file, false))
  const bytes = Buffer.from(renderBundle(controller, modules), 'utf8')
  const output = resolveOutput(args.output)

  if (args.check) {
    let current = null
    try {
      current = readFileSync(output)
    } catch {}
    if (!current || !current.equals(bytes)) throw new Error(`Bundle is stale: ${output}`)
    process.stdout.write(`CURRENT ${output}\n`)
    return
  }

  writeAtomically(output, bytes)
  process.stdout.write(`WROTE ${output}\n`)
}

try {
  main()
} catch (error) {
  process.stderr.write(`structure-doctor bundle failed: ${error.message}\n`)
  process.exitCode = 1
}
