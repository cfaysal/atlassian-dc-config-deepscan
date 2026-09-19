package structuredoctor

import groovy.transform.CompileDynamic
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Read-only DC Automation adapter. Service resolution is scoped to the authenticated request. */
@CompileDynamic
final class DcAutomationProvider implements AutomationDataProvider {
    private static final String CONFIG_API = 'com.codebarrel.automation.api.service.AutomationConfigService'
    private static final String AUDIT_API = 'com.codebarrel.automation.api.service.AuditService'
    private static final int MAX_PROJECTS = 100
    private static final int MAX_RULES = 500
    private static final int AUDIT_PAGE_SIZE = 50
    private final Closure connection
    private final Closure filterFactory
    private final Closure clock
    private final int auditLimit

    DcAutomationProvider(Closure connection, Closure filterFactory,
                         Closure clock = { Instant.now() }, int auditLimit = 300) {
        this.connection = connection
        this.filterFactory = filterFactory
        this.clock = clock
        this.auditLimit = auditLimit
    }

    @Override
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope) {
        if (!scope?.projectIds) return ReadResult.incomplete([], 'Automation: Projektumfang nicht vollständig ermittelt')
        try {
            Object result = connection.call(CONFIG_API, { service, tenant ->
                Map<Long, AutomationRuleSnapshot> rules = [:]
                List<Long> projects = scope.projectIds.unique(false)
                boolean complete = projects.size() <= MAX_PROJECTS
                for (Long projectId : projects.take(MAX_PROJECTS)) {
                    List beans = DcAutomationMapping.list(service.getRules(tenant, String.valueOf(projectId)))
                    if (beans.size() > MAX_RULES) complete = false
                    for (Object bean : beans.take(MAX_RULES)) {
                        try {
                            AutomationRuleSnapshot rule = DcAutomationMapping.rule(bean)
                            if (rules.containsKey(rule.ruleId) && rules[rule.ruleId].revision != rule.revision) complete = false
                            rules[rule.ruleId] = rule
                        } catch (RuntimeException ignored) { complete = false }
                    }
                    if (rules.size() > MAX_RULES) { complete = false; break }
                }
                List values = rules.values().take(MAX_RULES).sort { it.ruleId }
                complete ? ReadResult.complete(values) : ReadResult.incomplete(values,
                    'Automation-Regeln nur teilweise gelesen: Leselimit, geänderte Revision oder unbekanntes Datenformat')
            })
            result == null ? ReadResult.unavailable('AutomationConfigService im installierten Plugin nicht erreichbar') : result
        } catch (Exception failure) {
            ReadResult.failed('Automation-Regeln konnten nicht gelesen werden (' + failure.class.simpleName + ')')
        }
    }

    @Override
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) {
        if (request == null || request.requestedDays < 1 || request.requestedDays > 365 ||
            !request.issueIds) {
            return ReadResult.incomplete([], 'Automation-Audit: Vorgangsumfang oder gültiges Zeitfenster fehlt')
        }
        try {
            Object result = connection.call(AUDIT_API, { service, tenant -> readAuditPages(service, tenant, request) })
            result == null ? ReadResult.unavailable('AuditService im installierten Plugin nicht erreichbar') : result
        } catch (Exception failure) {
            ReadResult.failed('Automation-Audit konnte nicht gelesen werden (' + failure.class.simpleName + ')')
        }
    }

    private ReadResult readAuditPages(Object service, Object tenant, AuditRequest request) {
        Instant to = (Instant) clock.call()
        long retention = service.getAuditLogRetentionPeriodDays(DcAutomationMapping.property(tenant, 'environment'))
        if (retention < -1) throw new IllegalArgumentException('Unknown audit retention')
        long days = retention == -1 ? request.requestedDays : Math.min(request.requestedDays as long, retention)
        Instant from = to.minus(days, ChronoUnit.DAYS)
        List<AutomationAuditSnapshot> entries = []
        Set<Long> requestedIssueIds = new HashSet<>(request.issueIds)
        Set<Long> seen = new LinkedHashSet<>()
        List<String> gaps = []
        if (days < request.requestedDays) gaps.add('Aufbewahrung nur ' + days + ' Tage')
        boolean capped = false
        // No current rules does not prove no historic (e.g. deleted-rule) executions.
        List<Long> ruleIds = request.ruleIds ? request.ruleIds.unique(false) : [null]
        for (Long ruleId : ruleIds) {
            Object filter = filterFactory.call(ruleId, from, to)
            long offset = 0
            Long previousTotal = null
            while (true) {
                if (seen.size() >= auditLimit) {
                    gaps.add('Audit-Leselimit erreicht (' + auditLimit + ' Einträge)'); capped = true; break
                }
                long limit = Math.min(AUDIT_PAGE_SIZE, auditLimit - seen.size())
                Object page = service.getItems(tenant, filter, offset, limit)
                List items = DcAutomationMapping.list(DcAutomationMapping.property(page, 'items'))
                long total = CoreAutomationJsonSupport.flexibleLong(DcAutomationMapping.property(page, 'total'), 'total')
                String pageProblem = null
                if (total < 0) pageProblem = 'Audit-Gesamtzahl nicht verfügbar'
                else if (items.size() > limit) pageProblem = 'Audit-Seite überschreitet angefragte Seitengröße'
                else if (offset + items.size() > total) pageProblem = 'Audit-Seite überschreitet gemeldete Gesamtzahl'
                else if (previousTotal != null && total != previousTotal) pageProblem = 'Audit-Gesamtzahl zwischen Seiten geändert'
                if (pageProblem != null) {
                    gaps.add(pageProblem + ' (' + (previousTotal == null ? '' : 'vorher=' + previousTotal + ', ') +
                        'total=' + total + ', offset=' + offset + ', gelesen=' + items.size() + ', limit=' + limit + ')')
                    capped = true; break
                }
                previousTotal = total
                if (items.empty && offset < total) { gaps.add('Audit-Seite fehlt'); capped = true; break }
                for (Object item : items) {
                    long id = CoreAutomationJsonSupport.flexibleLong(DcAutomationMapping.property(item, 'id'), 'auditId')
                    if (!seen.add(id)) { gaps.add('Audit-Seite wiederholt'); capped = true; break }
                    Object detail = service.getItem(tenant, filter, id)
                    if (!(detail instanceof Optional) || !detail.present) {
                        gaps.add('Audit-Details fehlen'); capped = true; continue
                    }
                    try {
                        Map normalized = DcAutomationMapping.audit(detail.get(), requestedIssueIds, ruleId)
                        Instant occurredAt = Instant.parse(normalized.occurredAt)
                        if (occurredAt.isBefore(from) || occurredAt.isAfter(to)) {
                            gaps.add('Audit-Zeitfilter nicht eingehalten'); capped = true; continue
                        }
                        entries.addAll(normalized.entries)
                        if (!normalized.complete) { gaps.add('Audit-Vorgangszuordnung unvollständig'); capped = true }
                    } catch (RuntimeException failure) {
                        gaps.add('Audit-Datenformat nicht vollständig lesbar (' + failure.class.simpleName + ')'); capped = true
                    }
                }
                offset += items.size()
                if (capped || offset >= total) break
            }
            if (capped) break
        }
        Coverage coverage = Coverage.bounded(request.requestedDays, days, capped, from.toString(), to.toString())
        coverage.complete() ? ReadResult.complete(entries, coverage) : ReadResult.incomplete(entries,
            gaps.unique().join('; '), coverage)
    }
}
