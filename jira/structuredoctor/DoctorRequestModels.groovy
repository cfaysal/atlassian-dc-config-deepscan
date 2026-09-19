package structuredoctor

import groovy.transform.Immutable

@Immutable(copyWith = true)
class StructureChoice {
    long id
    String name
}

@Immutable(copyWith = true)
class AnalyzeRequest {
    long structureId
    String issueKeyFilter
    Integer requestedAuditDays
    String ruleExportRef
    String auditExportRef
    String ruleExportJson
    String auditExportJson
}

@Immutable(copyWith = true)
class SourceCoverage {
    String source
    ReadState state
    String reason
    Coverage coverage
    String provider
}

@Immutable(copyWith = true)
class DoctorAnalysis {
    String snapshotId
    long structureId
    String issueKeyFilter
    Long displayIssueId
    int requestedAuditDays
    StructureSnapshot snapshot
    String dependencyFingerprint
    List<SourceCoverage> coverage
    HierarchyAnalysis hierarchy
    DuplicateAnalysis duplicates
    AutomationAnalysis automation
    List<CausalClaim> causalClaims
    boolean complete
    List<String> blockers
}

@Immutable(copyWith = true)
class PlanRequest {
    String snapshotId
    List<String> findingGroupIds
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedPermanentRowIds
}

final class DoctorRequests {
    private static final Set<String> ANALYZE_KEYS = [
        'structureId', 'issueKeyFilter', 'requestedAuditDays',
        'ruleExportRef', 'auditExportRef', 'ruleExportJson', 'auditExportJson'
    ] as Set<String>
    private static final Set<String> PLAN_KEYS = [
        'snapshotId', 'findingGroupIds', 'retainOccurrenceByGroup',
        'selectedPermanentRowIds'
    ] as Set<String>

    static AnalyzeRequest parseAnalyze(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != ANALYZE_KEYS) {
            throw new IllegalArgumentException('Analyze request contains missing or unsupported keys')
        }
        long structureId
        int days
        try {
            structureId = Long.parseLong(String.valueOf(payload.structureId))
            days = payload.requestedAuditDays == null ? 30 :
                Integer.parseInt(String.valueOf(payload.requestedAuditDays))
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException('Analyze request has invalid numeric values')
        }
        new AnalyzeRequest(
            structureId: structureId,
            issueKeyFilter: optionalText(payload.issueKeyFilter),
            requestedAuditDays: days,
            ruleExportRef: optionalReference(payload.ruleExportRef),
            auditExportRef: optionalReference(payload.auditExportRef),
            ruleExportJson: optionalPayload(payload.ruleExportJson),
            auditExportJson: optionalPayload(payload.auditExportJson))
    }

    static PlanRequest parsePlan(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != PLAN_KEYS) {
            throw new IllegalArgumentException('Plan request contains missing or unsupported keys')
        }
        if (!(payload.findingGroupIds instanceof List) ||
            !(payload.retainOccurrenceByGroup instanceof Map) ||
            !(payload.selectedPermanentRowIds instanceof List)) {
            throw new IllegalArgumentException('Plan request has invalid value types')
        }
        new PlanRequest(
            snapshotId: text(payload.snapshotId, 'snapshotId'),
            findingGroupIds: strings(payload.findingGroupIds, 'findingGroupIds'),
            retainOccurrenceByGroup: stringMap(payload.retainOccurrenceByGroup),
            selectedPermanentRowIds: strings(
                payload.selectedPermanentRowIds, 'selectedPermanentRowIds'))
    }

    private static String optionalText(Object value) {
        value == null || !value.toString().trim() ? null : value.toString().trim()
    }

    private static String optionalReference(Object value) {
        String result = optionalText(value)
        if (result != null && !(result ==~ /[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/)) {
            throw new IllegalArgumentException('Upload reference is invalid')
        }
        result
    }

    private static String optionalPayload(Object value) {
        if (value == null) return null
        if (!(value instanceof CharSequence)) {
            throw new IllegalArgumentException('Automation export must be JSON text')
        }
        String result = value.toString()
        result.trim().isEmpty() ? null : result
    }

    private static String text(Object value, String name) {
        if (!(value instanceof CharSequence) || !value.toString().trim()) {
            throw new IllegalArgumentException(name + ' is required')
        }
        value.toString().trim()
    }

    private static List<String> strings(Object value, String name) {
        ((List<?>) value).collect { Object item -> text(item, name) }
    }

    private static Map<String, String> stringMap(Object value) {
        ((Map<?, ?>) value).collectEntries { Object key, Object item ->
            [(text(key, 'retain group')): text(item, 'retain occurrence')]
        } as Map<String, String>
    }
}
