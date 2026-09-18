package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreAutomationJsonSupport {
    private static final Set<String> RULE_KEYS = [
        'id', 'enabled', 'projects', 'issueTypes', 'reads', 'writes', 'clears',
        'sourcesByTarget', 'trigger', 'orderedComponents', 'conditions',
        'asynchronous', 'allowOtherRuleTrigger', 'actor', 'revision', 'complete'
    ] as Set<String>
    private static final Set<String> AUDIT_KEYS = [
        'ruleId', 'issueId', 'occurredAt', 'action', 'target', 'successful', 'revision'
    ] as Set<String>
    private static final Set<String> OFFICIAL_RULE_KEYS = [
        'id', 'clientKey', 'name', 'state', 'description', 'authorAccountId',
        'actorAccountId', 'created', 'updated', 'trigger', 'components', 'projects',
        'canOtherRuleTrigger', 'notifyOnError', 'labels', 'tags'
    ] as Set<String>
    private static final Set<String> OFFICIAL_REQUIRED_KEYS = [
        'id', 'state', 'updated', 'trigger', 'components', 'projects',
        'canOtherRuleTrigger'
    ] as Set<String>
    private static final Set<String> COMPONENT_KEYS = [
        'id', 'parentId', 'conditionParentId', 'component', 'schemaVersion',
        'type', 'value', 'children', 'conditions'
    ] as Set<String>
    private static final Set<String> COMPONENT_REQUIRED_KEYS = [
        'id', 'component', 'schemaVersion', 'type', 'children', 'conditions'
    ] as Set<String>

    private CoreAutomationJsonSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static AutomationRuleSnapshot rule(Map<String, Object> value, String exportVersion) {
        requireExactKeys(value, RULE_KEYS, RULE_KEYS)
        new AutomationRuleSnapshot(
            ruleId: longValue(value.id, 'id'),
            enabled: booleanValue(value.enabled, 'enabled'),
            projectIds: longList(value.projects, 'projects'),
            issueTypeIds: longList(value.issueTypes, 'issueTypes'),
            reads: stringList(value.reads, 'reads'),
            writes: stringList(value.writes, 'writes'),
            clears: stringList(value.clears, 'clears'),
            sourcesByTarget: stringListMap(value.sourcesByTarget, 'sourcesByTarget'),
            trigger: text(value.trigger, 'trigger'),
            orderedComponents: stringList(value.orderedComponents, 'orderedComponents'),
            conditions: stringList(value.conditions, 'conditions'),
            asynchronous: booleanValue(value.asynchronous, 'asynchronous'),
            allowOtherRuleTrigger: booleanValue(
                value.allowOtherRuleTrigger, 'allowOtherRuleTrigger'),
            actor: text(value.actor, 'actor'),
            revision: text(value.revision, 'revision'),
            exportVersion: exportVersion,
            complete: booleanValue(value.complete, 'complete')
        )
    }

    static AutomationAuditSnapshot audit(Map<String, Object> value) {
        requireExactKeys(value, AUDIT_KEYS, AUDIT_KEYS)
        new AutomationAuditSnapshot(
            ruleId: longValue(value.ruleId, 'ruleId'),
            issueId: longValue(value.issueId, 'issueId'),
            occurredAt: text(value.occurredAt, 'occurredAt'),
            action: text(value.action, 'action'),
            target: text(value.target, 'target'),
            successful: booleanValue(value.successful, 'successful'),
            revision: text(value.revision, 'revision')
        )
    }

    static AutomationRuleSnapshot officialRule(Map<String, Object> value) {
        requireExactKeys(value, OFFICIAL_RULE_KEYS, OFFICIAL_REQUIRED_KEYS)
        Map<String, Object> triggerNode = objectValue(value.trigger, 'trigger')
        List<Map<String, Object>> nodes = []
        flattenComponent(triggerNode, nodes)
        for (Map<String, Object> component : objectList(value.components, 'components')) {
            flattenComponent(component, nodes)
        }
        List<String> reads = officialReads(nodes)
        boolean opaqueAction = nodes.any { Map<String, Object> node ->
            node.component == 'ACTION'
        }
        Map<String, Object> triggerValue = valueMap(triggerNode.value)
        List<Long> projects = objectList(value.projects, 'projects').collect {
            Map<String, Object> project -> flexibleLong(project.projectId, 'projectId')
        }
        new AutomationRuleSnapshot(
            ruleId: flexibleLong(value.id, 'id'),
            enabled: text(value.state, 'state') == 'ENABLED',
            projectIds: projects,
            issueTypeIds: [],
            reads: reads,
            writes: [],
            clears: [],
            sourcesByTarget: [:],
            trigger: text(triggerNode.type, 'trigger.type'),
            orderedComponents: nodes*.component.collect { Object item -> String.valueOf(item) },
            conditions: nodes.findAll { Map<String, Object> node ->
                String.valueOf(node.component).startsWith('CONDITION')
            }*.type.collect { Object item -> String.valueOf(item) },
            asynchronous: triggerValue.containsKey('synchronous') &&
                !booleanValue(triggerValue.synchronous, 'synchronous'),
            allowOtherRuleTrigger: booleanValue(
                value.canOtherRuleTrigger, 'canOtherRuleTrigger'),
            actor: value.actorAccountId == null ? 'UNSPECIFIED_ACTOR' : 'CONFIGURED_ACTOR',
            revision: revision(value.updated, 'updated'),
            exportVersion: 'jira-automation-dc',
            complete: !opaqueAction
        )
    }

    static void requireExactKeys(Map<String, Object> value,
                                 Set<String> allowed,
                                 Set<String> required) {
        if (value == null || !allowed.containsAll(value.keySet()) ||
            !value.keySet().containsAll(required)) {
            throw new IllegalArgumentException('JSON object does not match schema')
        }
    }

    static List<Map<String, Object>> objectList(Object value, String name) {
        if (!(value instanceof List) || ((List<?>) value).any { Object item -> !(item instanceof Map) }) {
            throw new IllegalArgumentException(name + ' must be an object array')
        }
        (List<Map<String, Object>>) value
    }

    static long longValue(Object value, String name) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + ' must be numeric')
        }
        BigDecimal decimal = new BigDecimal(value.toString())
        try {
            decimal.longValueExact()
        } catch (ArithmeticException ignored) {
            throw new IllegalArgumentException(name + ' must be an integer')
        }
    }

    static long flexibleLong(Object value, String name) {
        if (value instanceof Number) {
            return longValue(value, name)
        }
        if (value instanceof String && value ==~ /[0-9]+/) {
            try {
                return Long.parseLong((String) value)
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException(name + ' exceeds signed long range')
            }
        }
        throw new IllegalArgumentException(name + ' must be a numeric identifier')
    }

    static boolean booleanValue(Object value, String name) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + ' must be boolean')
        }
        (Boolean) value
    }

    static String text(Object value, String name) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + ' must be non-blank text')
        }
        (String) value
    }

    static String revision(Object value, String name) {
        if (value instanceof Number) {
            return String.valueOf(longValue(value, name))
        }
        text(value, name)
    }

    static List<Long> longList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(name + ' must be an array')
        }
        ((List<?>) value).collect { Object item -> longValue(item, name) }
    }

    static List<String> stringList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(name + ' must be an array')
        }
        ((List<?>) value).collect { Object item -> text(item, name) }
    }

    static Map<String, List<String>> stringListMap(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(name + ' must be an object')
        }
        Map<String, List<String>> result = new LinkedHashMap<>()
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = text(entry.key, name)
            result.put(key, stringList(entry.value, name))
        }
        result
    }

    private static Map<String, Object> objectValue(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(name + ' must be an object')
        }
        (Map<String, Object>) value
    }

    private static Map<String, Object> valueMap(Object value) {
        value instanceof Map ? (Map<String, Object>) value : [:]
    }

    private static void flattenComponent(Map<String, Object> node,
                                         List<Map<String, Object>> result) {
        requireExactKeys(node, COMPONENT_KEYS, COMPONENT_REQUIRED_KEYS)
        result.add(node)
        for (Map<String, Object> child : objectList(node.children, 'children')) {
            flattenComponent(child, result)
        }
        for (Map<String, Object> condition : objectList(node.conditions, 'conditions')) {
            flattenComponent(condition, result)
        }
    }

    private static List<String> officialReads(List<Map<String, Object>> nodes) {
        LinkedHashSet<String> reads = [] as LinkedHashSet<String>
        for (Map<String, Object> node : nodes) {
            Map<String, Object> componentValue = valueMap(node.value)
            Object fields = componentValue.fields
            if (fields instanceof List) {
                for (Object field : (List<?>) fields) {
                    Map<String, Object> fieldValue = objectValue(field, 'field')
                    reads.add('field:' + text(fieldValue.value, 'field.value'))
                }
            }
            Object linkTypes = componentValue.linkTypes
            if (linkTypes instanceof List) {
                for (Object linkType : (List<?>) linkTypes) {
                    reads.add('link:' + flexibleLong(linkType, 'linkType'))
                }
            }
        }
        reads as List<String>
    }
}
