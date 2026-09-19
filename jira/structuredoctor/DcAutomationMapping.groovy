package structuredoctor

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.codehaus.groovy.runtime.InvokerHelper

/** Project only known read properties. Never evaluate action payloads or serialize entire beans. */
@CompileDynamic
final class DcAutomationMapping {
    static Object property(Object bean, String name) {
        if (bean == null) throw new IllegalArgumentException('Missing Automation bean')
        InvokerHelper.getProperty(bean, name)
    }

    static List list(Object value) {
        if (!(value instanceof Collection)) throw new IllegalArgumentException('Missing Automation collection')
        new ArrayList((Collection) value)
    }

    static AutomationRuleSnapshot rule(Object bean) {
        Object updated = property(bean, 'updated')
        Map data = [id: property(bean, 'id'), state: String.valueOf(property(bean, 'state')),
            updated: updated instanceof Date ? updated.toInstant().toString() : updated,
            trigger: component(property(bean, 'trigger'), 0),
            components: list(property(bean, 'components')).collect { component(it, 0) },
            projects: list(property(bean, 'projects')).collect { [projectId: property(it, 'projectId')] },
            canOtherRuleTrigger: property(bean, 'canOtherRuleTrigger')]
        CoreAutomationJsonSupport.officialRule(data)
    }

    private static Map component(Object bean, int depth) {
        if (depth > 50) throw new IllegalArgumentException('Automation component depth limit')
        Object value = property(bean, 'value')
        // Some DC bean versions expose the JSON value as text, exports expose it as an object.
        if (value instanceof String && value.trim().startsWith('{')) {
            if (value.length() > 1_048_576) throw new IllegalArgumentException('Automation component size limit')
            value = new JsonSlurper().parseText(value)
        }
        [id: property(bean, 'id'), component: String.valueOf(property(bean, 'component')),
            type: String.valueOf(property(bean, 'type')), schemaVersion: property(bean, 'schemaVersion'),
            value: value, children: list(property(bean, 'children')).collect { component(it, depth + 1) },
            conditions: list(property(bean, 'conditions')).collect { component(it, depth + 1) }]
    }

    static Map audit(Object bean, Set<Long> requestedIssueIds, Long expectedRuleId) {
        long ruleId = CoreAutomationJsonSupport.flexibleLong(property(property(bean, 'objectItem'), 'id'), 'ruleId')
        if (expectedRuleId != null && ruleId != expectedRuleId) throw new IllegalArgumentException('Audit rule mismatch')
        Object created = property(bean, 'created')
        if (!(created instanceof Date)) throw new IllegalArgumentException('Missing audit date')
        List associated = list(property(bean, 'globalAssociatedItems'))
        boolean complete = true
        for (Object component : list(property(bean, 'componentChanges'))) {
            Object page = property(component, 'associatedItems')
            List results = list(property(page, 'results'))
            long total = CoreAutomationJsonSupport.flexibleLong(property(page, 'total'), 'total')
            long offset = CoreAutomationJsonSupport.flexibleLong(property(page, 'offset'), 'offset')
            if (offset != 0L || total != results.size()) complete = false
            associated.addAll(results)
        }
        Set<Long> issueIds = new LinkedHashSet<>()
        for (Object item : associated) {
            String type = String.valueOf(property(item, 'typeName'))
            if (type.equalsIgnoreCase('issue')) {
                Object id = property(item, 'id')
                if (!(String.valueOf(id) ==~ /[0-9]+/)) { complete = false; continue }
                long issueId = Long.parseLong(String.valueOf(id))
                if (requestedIssueIds.contains(issueId)) issueIds.add(issueId)
            } else if (!(type.toUpperCase(Locale.ROOT) in ['USER', 'AUTOMATION_SYSTEM'])) {
                complete = false
            }
        }
        String category = String.valueOf(property(bean, 'category'))
        String occurredAt = ((Date) created).toInstant().toString()
        String revision = CoreCanonical.sha256([id: property(bean, 'id'), ruleId: ruleId,
            occurredAt: occurredAt, category: category, issueIds: issueIds])
        [complete: complete, occurredAt: occurredAt, entries: issueIds.collect { Long issueId ->
            new AutomationAuditSnapshot(ruleId: ruleId, issueId: issueId, occurredAt: occurredAt,
                action: 'RULE_EXECUTION', target: 'UNPROVEN_FIELD_EFFECT',
                successful: category == 'SUCCESS', revision: revision)
        }]
    }
}
