package structuredoctor

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.lang.reflect.Array
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@CompileStatic
final class CoreCanonical {
    private CoreCanonical() {
        throw new UnsupportedOperationException('utility class')
    }

    static String canonicalJson(Object value) {
        JsonOutput.toJson(normalize(value))
    }

    static String sha256(Object value) {
        byte[] bytes = canonicalJson(value).getBytes(StandardCharsets.UTF_8)
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(bytes)
        digest.collect { byte item -> String.format('%02x', item & 0xff) }.join('')
    }

    static String deterministicId(String namespace, Object value) {
        if (namespace == null || !(namespace ==~ /[a-z][a-z0-9-]{1,40}/)) {
            throw new IllegalArgumentException('invalid deterministic ID namespace')
        }
        namespace + ':' + sha256(value)
    }

    static String planningFingerprint(StructureSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete || snapshot.hierarchy == null) {
            return null
        }
        if (snapshot.generators.any { GeneratorSnapshot generator -> !generator.complete }) {
            return null
        }
        if (snapshot.occurrences.any { OccurrenceSnapshot occurrence -> !occurrence.provenanceComplete }) {
            return null
        }
        sha256(snapshotValue(snapshot))
    }

    static Object normalize(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum) {
            return String.valueOf(value)
        }
        if (value instanceof Number) {
            return normalizeNumber((Number) value)
        }
        if (value instanceof Map) {
            Map<String, Object> normalized = new TreeMap<String, Object>()
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.key)
                if (normalized.containsKey(key)) {
                    throw new IllegalArgumentException('canonical map key collision: ' + key)
                }
                normalized.put(key, normalize(entry.value))
            }
            return normalized
        }
        if (value instanceof Set) {
            List<Object> normalized = ((Set<?>) value).collect { Object item -> normalize(item) }
            normalized.sort { Object left, Object right ->
                JsonOutput.toJson(left) <=> JsonOutput.toJson(right)
            }
            return normalized
        }
        if (value instanceof Iterable) {
            return ((Iterable<?>) value).collect { Object item -> normalize(item) }
        }
        if (value.class.isArray()) {
            List<Object> normalized = []
            int length = Array.getLength(value)
            for (int index = 0; index < length; index++) {
                normalized.add(normalize(Array.get(value, index)))
            }
            return normalized
        }
        throw new IllegalArgumentException('unsupported canonical value: ' + value.class.name)
    }

    private static Object normalizeNumber(Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer ||
            number instanceof Long || number instanceof BigInteger) {
            BigInteger integral = new BigInteger(number.toString())
            if (integral < BigInteger.valueOf(Long.MIN_VALUE) || integral > BigInteger.valueOf(Long.MAX_VALUE)) {
                throw new IllegalArgumentException('integral value exceeds signed long range')
            }
            return integral.longValue()
        }
        if ((number instanceof Double && !Double.isFinite(number.doubleValue())) ||
            (number instanceof Float && !Float.isFinite(number.floatValue()))) {
            throw new IllegalArgumentException('non-finite number is not canonical')
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros()
        if (decimal.scale() <= 0) {
            try {
                return decimal.longValueExact()
            } catch (ArithmeticException ignored) {
                throw new IllegalArgumentException('integral value exceeds signed long range')
            }
        }
        decimal
    }

    private static Map<String, Object> snapshotValue(StructureSnapshot snapshot) {
        [
            structureId: snapshot.structureId,
            revision: snapshot.revision,
            hierarchy: [
                levels: snapshot.hierarchy.levels.collect { HierarchyLevel level ->
                    [rank: level.rank, levelId: level.levelId, issueTypeIds: level.issueTypeIds]
                }
            ],
            generators: snapshot.generators.collect { GeneratorSnapshot generator ->
                [id: generator.generatorId, moduleKey: generator.moduleKey, type: generator.type,
                 order: generator.order, enabled: generator.enabled, parameters: generator.parameters,
                 revision: generator.revision]
            },
            occurrences: snapshot.occurrences.collect { OccurrenceSnapshot occurrence ->
                [id: occurrence.occurrenceId, issueId: occurrence.issueId, rowId: occurrence.rowId,
                 parentPath: occurrence.parentPath, parentIssueId: occurrence.parentIssueId,
                 depth: occurrence.depth, position: occurrence.position,
                 provenance: occurrence.provenance, creatorId: occurrence.creatorId]
            },
            relations: snapshot.relations.collect { IssueRelationSnapshot relation ->
                [issueId: relation.issueId, issueTypeId: relation.issueTypeId, projectId: relation.projectId,
                 nativeParentId: relation.nativeParentId, leadingParentIds: relation.leadingParentIds,
                 revisions: relation.revisions]
            }
        ] as Map<String, Object>
    }
}
