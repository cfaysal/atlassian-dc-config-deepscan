import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

interface MutationProbeAdapter {
    Map<String, Object> snapshot(long structureId)
    String applySmallestReversibleMutation(long structureId, Map<String, Object> before)
    String waitForRevision(long structureId, String expectedRevision, int timeoutSeconds)
    String restore(long structureId, Map<String, Object> before)
}

final class StructureDoctorMutationProbe {
    static final String CONFIRMATION = 'AUTHORIZE_REVERSIBLE_STRUCTURE_PROBE'
    private static final Set<String> REQUEST_KEYS = [
        'structureId', 'authorizedDisposable', 'confirmation',
        'expectedFingerprint', 'timeoutSeconds'
    ] as Set<String>

    Map<String, Object> run(Map<String, Object> request, MutationProbeAdapter adapter) {
        validate(request, adapter)
        long structureId = ((Number) request.structureId).longValue()
        int timeoutSeconds = ((Number) request.timeoutSeconds).intValue()
        Map<String, Object> before = copy(adapter.snapshot(structureId))
        String beforeFingerprint = fingerprint(before)
        if (beforeFingerprint != request.expectedFingerprint) {
            throw new IllegalArgumentException('expected fingerprint does not match target')
        }

        String mutationRevision = null
        String restoreRevision = null
        try {
            mutationRevision = required(adapter.applySmallestReversibleMutation(
                structureId, copy(before)), 'mutation revision')
            String observed = adapter.waitForRevision(
                structureId, mutationRevision, timeoutSeconds)
            if (observed != mutationRevision) {
                throw new IllegalStateException('mutation revision was not observed')
            }
        } finally {
            restoreRevision = required(adapter.restore(structureId, copy(before)),
                'restore revision')
            String observedRestore = adapter.waitForRevision(
                structureId, restoreRevision, timeoutSeconds)
            if (observedRestore != restoreRevision) {
                throw new IllegalStateException('restore revision was not observed')
            }
            Map<String, Object> restored = copy(adapter.snapshot(structureId))
            if (fingerprint(restored) != beforeFingerprint) {
                throw new IllegalStateException('restored target does not match original snapshot')
            }
        }

        [
            status: 'RESTORED',
            structureId: structureId,
            beforeFingerprint: beforeFingerprint,
            restoredFingerprint: beforeFingerprint,
            mutationRevision: mutationRevision,
            restoreRevision: restoreRevision
        ] as Map<String, Object>
    }

    static String fingerprint(Map<String, Object> snapshot) {
        byte[] value = JsonOutput.toJson(normalize(snapshot))
            .getBytes(StandardCharsets.UTF_8)
        MessageDigest.getInstance('SHA-256').digest(value)
            .collect { byte item -> String.format('%02x', item & 0xff) }.join('')
    }

    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean ||
            value instanceof Number) return value
        if (value instanceof Map) {
            Map<String, Object> result = new TreeMap<>()
            ((Map<?, ?>) value).each { Object key, Object item ->
                result.put(String.valueOf(key), normalize(item))
            }
            return result
        }
        if (value instanceof Iterable) {
            return ((Iterable<?>) value).collect { Object item -> normalize(item) }
        }
        throw new IllegalArgumentException('unsupported snapshot value')
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        if (value == null) throw new IllegalStateException('snapshot is unavailable')
        (Map<String, Object>) normalize(value)
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + ' is unavailable')
        }
        value
    }

    private static void validate(Map<String, Object> request,
                                 MutationProbeAdapter adapter) {
        if (request == null || request.keySet() != REQUEST_KEYS) {
            throw new IllegalArgumentException('exact probe request is required')
        }
        if (adapter == null) throw new IllegalArgumentException('adapter is required')
        if (!(request.structureId instanceof Number) ||
            ((Number) request.structureId).longValue() <= 0L) {
            throw new IllegalArgumentException('positive disposable Structure ID is required')
        }
        if (request.authorizedDisposable != Boolean.TRUE) {
            throw new IllegalArgumentException('disposable target authorization is required')
        }
        if (request.confirmation != CONFIRMATION) {
            throw new IllegalArgumentException('explicit probe confirmation is required')
        }
        if (!(request.expectedFingerprint instanceof String) ||
            !(request.expectedFingerprint ==~ /[0-9a-f]{64}/)) {
            throw new IllegalArgumentException('expected fingerprint is required')
        }
        if (!(request.timeoutSeconds instanceof Number) ||
            ((Number) request.timeoutSeconds).intValue() < 1 ||
            ((Number) request.timeoutSeconds).intValue() > 600) {
            throw new IllegalArgumentException('timeout must be 1 to 600 seconds')
        }
    }
}
