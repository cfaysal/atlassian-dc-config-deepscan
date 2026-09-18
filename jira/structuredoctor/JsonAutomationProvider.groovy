package structuredoctor

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets

@CompileStatic
final class JsonAutomationProvider implements AutomationDataProvider {
    private static final Set<String> RULE_EXPORT_KEYS =
        ['exportVersion', 'rules'] as Set<String>
    private static final Set<String> OFFICIAL_RULE_EXPORT_KEYS =
        ['cloud', 'rules'] as Set<String>
    private static final Set<String> AUDIT_EXPORT_KEYS = [
        'exportVersion', 'requestedDays', 'actualDays', 'capped',
        'fromInclusive', 'toInclusive', 'entries'
    ] as Set<String>

    private final byte[] rulePayload
    private final byte[] auditPayload
    private final int maxBytes

    JsonAutomationProvider(byte[] rulePayload, byte[] auditPayload, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException('maxBytes must be positive')
        }
        this.rulePayload = rulePayload == null ? null : rulePayload.clone()
        this.auditPayload = auditPayload == null ? null : auditPayload.clone()
        this.maxBytes = maxBytes
    }

    @Override
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope) {
        if (rulePayload == null) {
            return ReadResult.unavailable('Automation rule export was not supplied')
        }
        try {
            Map<String, Object> root = parse(rulePayload)
            List<AutomationRuleSnapshot> rules = parseRules(root)
                .findAll { AutomationRuleSnapshot rule -> overlaps(rule, scope) }
            ReadResult.complete(rules)
        } catch (RuntimeException ignored) {
            ReadResult.failed('Automation rule export was rejected')
        }
    }

    @Override
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) {
        if (auditPayload == null) {
            return ReadResult.unavailable('Automation audit export was not supplied')
        }
        try {
            Map<String, Object> root = parse(auditPayload)
            CoreAutomationJsonSupport.requireExactKeys(
                root, AUDIT_EXPORT_KEYS, AUDIT_EXPORT_KEYS)
            requireVersion(CoreAutomationJsonSupport.text(
                root.exportVersion, 'exportVersion'))
            long actualDays = CoreAutomationJsonSupport.longValue(
                root.actualDays, 'actualDays')
            long exportedRequest = CoreAutomationJsonSupport.longValue(
                root.requestedDays, 'requestedDays')
            if (request == null || request.requestedDays <= 0 ||
                exportedRequest != request.requestedDays) {
                throw new IllegalArgumentException('audit request mismatch')
            }
            Coverage coverage = Coverage.bounded(
                request.requestedDays,
                actualDays,
                CoreAutomationJsonSupport.booleanValue(root.capped, 'capped'),
                CoreAutomationJsonSupport.text(root.fromInclusive, 'fromInclusive'),
                CoreAutomationJsonSupport.text(root.toInclusive, 'toInclusive'))
            List<AutomationAuditSnapshot> entries = CoreAutomationJsonSupport
                .objectList(root.entries, 'entries')
                .collect { Map<String, Object> value -> CoreAutomationJsonSupport.audit(value) }
                .findAll { AutomationAuditSnapshot entry -> matches(entry, request) }
            coverage.complete() ? ReadResult.complete(entries, coverage) :
                ReadResult.incomplete(entries, 'Automation audit coverage is incomplete', coverage)
        } catch (RuntimeException ignored) {
            ReadResult.failed('Automation audit export was rejected')
        }
    }

    private Map<String, Object> parse(byte[] payload) {
        if (payload.length > maxBytes) {
            throw new IllegalArgumentException('payload exceeds byte limit')
        }
        Object value = new JsonSlurper().parseText(
            new String(payload, StandardCharsets.UTF_8))
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException('root must be an object')
        }
        (Map<String, Object>) value
    }

    private static void requireVersion(String version) {
        if (version != '1') {
            throw new IllegalArgumentException('unsupported export version')
        }
    }

    private static List<AutomationRuleSnapshot> parseRules(Map<String, Object> root) {
        if (root.keySet() == RULE_EXPORT_KEYS) {
            String version = CoreAutomationJsonSupport.text(
                root.exportVersion, 'exportVersion')
            requireVersion(version)
            return CoreAutomationJsonSupport.objectList(root.rules, 'rules').collect {
                Map<String, Object> value -> CoreAutomationJsonSupport.rule(value, version)
            }
        }
        if (root.keySet() == OFFICIAL_RULE_EXPORT_KEYS && root.cloud instanceof Boolean) {
            return CoreAutomationJsonSupport.objectList(root.rules, 'rules').collect {
                Map<String, Object> value -> CoreAutomationJsonSupport.officialRule(value)
            }
        }
        throw new IllegalArgumentException('unknown Automation export schema')
    }

    private static boolean overlaps(AutomationRuleSnapshot rule, AnalysisScope scope) {
        if (scope == null) {
            return true
        }
        boolean projects = intersectsOrGlobal(rule.projectIds, scope.projectIds)
        boolean types = intersectsOrGlobal(rule.issueTypeIds, scope.issueTypeIds)
        Set<String> selected = new LinkedHashSet<>(scope.fieldIds ?: [])
        selected.addAll((scope.linkTypeIds ?: []).collect { Long id -> 'link:' + id })
        Set<String> touched = new LinkedHashSet<>(rule.reads ?: [])
        touched.addAll(rule.writes ?: [])
        touched.addAll(rule.clears ?: [])
        projects && types && (selected.isEmpty() || !Collections.disjoint(selected, touched))
    }

    private static boolean intersectsOrGlobal(List<Long> ruleValues, List<Long> selected) {
        ruleValues == null || ruleValues.isEmpty() || selected == null || selected.isEmpty() ||
            !Collections.disjoint(ruleValues, selected)
    }

    private static boolean matches(AutomationAuditSnapshot entry, AuditRequest request) {
        (request.ruleIds == null || request.ruleIds.isEmpty() || request.ruleIds.contains(entry.ruleId)) &&
            (request.issueIds == null || request.issueIds.isEmpty() ||
                request.issueIds.contains(entry.issueId))
    }
}
