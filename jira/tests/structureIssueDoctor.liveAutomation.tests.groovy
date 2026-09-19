import structuredoctor.*
import groovy.json.JsonSlurper
import java.time.Instant

// Synthetic service contracts, no Jira access or rule execution.
def now = Instant.parse('2026-09-20T12:00:00Z')
def tenant = new Expando(environment: 'test')
def rule = new JsonSlurper().parse(new File('jira/tests/fixtures/structuredoctor/automation-rules-official.json')).rules[0]
def calls = []
def ruleService = new Expando(getRules: { context, project -> calls << project; [rule] })
def detail = [id: 10L, objectItem: [id: 9001L], created: Date.from(now.minusSeconds(60)),
    category: 'SUCCESS', globalAssociatedItems: [[id: '201', typeName: 'issue']], componentChanges: []]
def auditService = new Expando(
    getAuditLogRetentionPeriodDays: { env -> 90 },
    getItems: { context, filter, offset, limit -> [items: offset == 0 ? [[id: 10L]] : [], total: 1L] },
    getItem: { context, filter, id -> Optional.of(detail) })
def connection = { String api, Closure reader -> reader.call(api.endsWith('AuditService') ? auditService : ruleService, tenant) }
def filters = []
def filterFactory = { Long id, Instant from, Instant to -> filters << [id, from, to]; [ruleId: id] }
def provider = new DcAutomationProvider(connection, filterFactory, { now }, 10)
def scope = new AnalysisScope(projectIds: [101L, 102L], issueTypeIds: [], fieldIds: [], linkTypeIds: [])
def rules = provider.readRules(scope)
assert rules.complete()
assert calls == ['101', '102']
assert rules.value*.ruleId == [9001L]
assert !rules.value[0].complete // ScriptRunner actions remain opaque, never executed.
assert provider.readRules(scope.copyWith(projectIds: [])).state == ReadState.INCOMPLETE
def request = new AuditRequest(ruleIds: [9001L], issueIds: [201L], requestedDays: 30)
def audit = provider.readAudit(request)
assert audit.complete()
assert audit.value*.issueId == [201L]
assert audit.value[0].action == 'RULE_EXECUTION'
assert audit.value[0].target == 'UNPROVEN_FIELD_EFFECT'
assert audit.value[0].successful
assert filters[0] == [9001L, now.minusSeconds(30L * 86400L), now]
assert provider.readAudit(request.copyWith(issueIds: [202L])).value.empty
assert provider.readAudit(request.copyWith(ruleIds: [])).value*.ruleId == [9001L]
assert filters.last()[0] == null // Query historic executions, not a fabricated empty success.
def offsets = []
auditService.getItems = { context, filter, offset, limit ->
    offsets << offset
    [items: [[id: offset == 0 ? 10L : 11L]], total: 2L]
}
auditService.getItem = { context, filter, id -> Optional.of(detail + [id: id]) }
assert provider.readAudit(request).complete()
assert offsets == [0L, 1L]
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L]], total: 0L] }
def inconsistentPage = provider.readAudit(request)
assert inconsistentPage.state == ReadState.INCOMPLETE
assert inconsistentPage.reason.contains('Audit-Seite überschreitet gemeldete Gesamtzahl')
assert inconsistentPage.reason.contains('total=0, offset=0, gelesen=1, limit=10')
assert !inconsistentPage.reason.contains('während des Lesens geändert')
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L]], total: -1L] }
assert provider.readAudit(request).reason.contains('Audit-Gesamtzahl nicht verfügbar (total=-1')
auditService.getItems = { context, filter, offset, limit -> [items: (1L..11L).collect { [id: it] }, total: 11L] }
assert provider.readAudit(request).reason.contains('Audit-Seite überschreitet angefragte Seitengröße')
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L + offset]], total: offset == 0 ? 2L : 3L] }
assert provider.readAudit(request).reason.contains('Audit-Gesamtzahl zwischen Seiten geändert (vorher=2, total=3')
auditService.getItems = { context, filter, offset, limit -> [items: (1L..limit).collect { [id: it] }, total: 11L] }
assert provider.readAudit(request).reason.contains('Audit-Leselimit erreicht (10 Einträge)')
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L]], total: 1L] }
auditService.getItem = { context, filter, id -> Optional.of(detail + [componentChanges: [
    [associatedItems: [results: [], offset: 0L, total: 2L]]]]) }
assert provider.readAudit(request).state == ReadState.INCOMPLETE
auditService.getItem = { context, filter, id -> Optional.of(detail + [category: 'IN_PROGRESS']) }
assert !provider.readAudit(request).value[0].successful
auditService.getItem = { context, filter, id -> Optional.of(detail + [created: Date.from(now.plusSeconds(1))]) }
assert provider.readAudit(request).state == ReadState.INCOMPLETE
auditService.getItem = { context, filter, id -> Optional.of(detail) }
auditService.getAuditLogRetentionPeriodDays = { env -> 7 }
assert provider.readAudit(request).state == ReadState.INCOMPLETE
assert provider.readAudit(request).coverage.actual == 7
auditService.getAuditLogRetentionPeriodDays = { env -> 90 }
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L]], total: 11L] }
assert provider.readAudit(request).state == ReadState.INCOMPLETE // cap or repeated page
auditService.getItems = { context, filter, offset, limit -> [items: [], total: 1L] }
assert provider.readAudit(request).state == ReadState.INCOMPLETE
auditService.getItems = { context, filter, offset, limit -> [items: [[id: 10L]], total: 1L] }
auditService.getItem = { context, filter, id -> Optional.empty() }
assert provider.readAudit(request).state == ReadState.INCOMPLETE
ruleService.getRules = { context, project -> null }
assert provider.readRules(scope).state == ReadState.FAILED
def unavailable = new DcAutomationProvider({ api, reader -> null }, filterFactory, { now }, 10)
assert unavailable.readRules(scope).state == ReadState.UNAVAILABLE
assert unavailable.readAudit(request).state == ReadState.UNAVAILABLE
def denied = new DcAutomationProvider({ api, reader -> throw new SecurityException('private detail') }, filterFactory, { now }, 10)
assert denied.readRules(scope).state == ReadState.FAILED
assert !denied.readRules(scope).reason.contains('private detail')
int projectReads = 0
ruleService.getRules = { context, project -> projectReads++; [] }
assert provider.readRules(scope.copyWith(projectIds: (1L..101L).toList())).state == ReadState.INCOMPLETE
assert projectReads == 100
println 'PASS: live Automation contract assertions'
