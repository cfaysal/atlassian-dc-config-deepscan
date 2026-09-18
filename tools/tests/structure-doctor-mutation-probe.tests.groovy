import groovy.json.JsonOutput
import java.security.MessageDigest

int passed = 0
int failed = 0
List<String> failures = []
def check = { String name, Object actual, Object expected ->
    if (actual == expected) passed++
    else { failed++; failures << (name + '\n     expected: ' + expected + '\n     actual  : ' + actual) }
}
def ok = { String name, boolean condition ->
    if (condition) passed++
    else { failed++; failures << name }
}

File repository = new File(System.getProperty('repoRoot', '.')).canonicalFile
File sourceFile = new File(repository, 'tools/structure-doctor-mutation-probe.groovy')
ok('mutation probe exists', sourceFile.isFile())
if (!sourceFile.isFile()) {
    println "Structure Doctor mutation probe tests: ${passed} passed, ${failed} failed"
    System.exit(1)
}

GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass(sourceFile)
Class<?> probeType = loader.loadClass('StructureDoctorMutationProbe')
Class<?> adapterType = loader.loadClass('MutationProbeAdapter')
Object probe = probeType.getDeclaredConstructor().newInstance()

def fingerprint = { Map value ->
    String json = JsonOutput.toJson(new TreeMap<String, Object>(value))
    MessageDigest.getInstance('SHA-256').digest(json.getBytes('UTF-8'))
        .collect { String.format('%02x', it & 0xff) }.join('')
}

Map state = [generatorId: 21L, enabled: true]
List<String> order = []
Object adapter = ([
    snapshot: { long ignored -> new LinkedHashMap<>(state) },
    applySmallestReversibleMutation: { long ignored, Map before ->
        order << 'mutate'
        state.enabled = false
        'revision-2'
    },
    waitForRevision: { long ignored, String revision, int seconds ->
        order << 'wait:' + revision
        revision
    },
    restore: { long ignored, Map before ->
        order << 'restore'
        state = new LinkedHashMap<>(before)
        'revision-3'
    }
] as Map).asType(adapterType)

Map request = [
    structureId: 9L,
    authorizedDisposable: true,
    confirmation: 'AUTHORIZE_REVERSIBLE_STRUCTURE_PROBE',
    expectedFingerprint: fingerprint(state),
    timeoutSeconds: 30
]
Map result = (Map) probe.run(request, adapter)
check('probe restores exact state', state, [generatorId: 21L, enabled: true])
check('probe reports restored', result.status, 'RESTORED')
check('probe order', order, ['mutate', 'wait:revision-2', 'restore', 'wait:revision-3'])
ok('probe output excludes snapshot payload', !result.containsKey('snapshot'))

int writes = 0
Object countingAdapter = ([
    snapshot: { long ignored -> [generatorId: 21L, enabled: true] },
    applySmallestReversibleMutation: { long ignored, Map before -> writes++; 'r2' },
    waitForRevision: { long ignored, String revision, int seconds -> revision },
    restore: { long ignored, Map before -> writes++; 'r3' }
] as Map).asType(adapterType)
['authorizedDisposable', 'confirmation', 'expectedFingerprint'].each { String field ->
    Map invalid = new LinkedHashMap<>(request)
    if (field == 'authorizedDisposable') invalid[field] = false
    if (field == 'confirmation') invalid[field] = 'WRONG'
    if (field == 'expectedFingerprint') invalid[field] = '0' * 64
    boolean rejected = false
    try { probe.run(invalid, countingAdapter) }
    catch (IllegalArgumentException ignored) { rejected = true }
    ok(field + ' is required before mutation', rejected)
}
check('rejected probes do not write', writes, 0)

Map failingState = [generatorId: 21L, enabled: true]
int restores = 0
Object failingAdapter = ([
    snapshot: { long ignored -> new LinkedHashMap<>(failingState) },
    applySmallestReversibleMutation: { long ignored, Map before ->
        failingState.enabled = false
        throw new IllegalStateException('synthetic mutation failure')
    },
    waitForRevision: { long ignored, String revision, int seconds -> revision },
    restore: { long ignored, Map before ->
        restores++
        failingState = new LinkedHashMap<>(before)
        'r3'
    }
] as Map).asType(adapterType)
boolean mutationFailurePropagated = false
try { probe.run(request, failingAdapter) }
catch (IllegalStateException ignored) { mutationFailurePropagated = true }
ok('mutation failure propagates', mutationFailurePropagated)
check('finally restores after mutation failure', restores, 1)
check('failed mutation still restores exact state', failingState,
    [generatorId: 21L, enabled: true])

String source = sourceFile.getText('UTF-8')
ok('probe contains finally restore',
    source.contains('finally') && source.contains('adapter.restore'))
List<String> networkTokens = [
    'new ' + 'URL(', 'URL' + 'Connection', 'Http' + 'Client', 'Socket' + '('
]
ok('probe has no network client',
    networkTokens.every { String token -> !source.contains(token) })

println "Structure Doctor mutation probe tests: ${passed} passed, ${failed} failed"
if (!failures.isEmpty()) {
    failures.each { println '\nFAIL: ' + it }
    System.exit(1)
}
