package structuredoctor

import java.nio.charset.StandardCharsets

final class LiveAutomationProvider implements AutomationDataProvider {
    private final Closure<ReadResult<List<AutomationRuleSnapshot>>> ruleReader
    private final Closure<ReadResult<List<AutomationAuditSnapshot>>> auditReader

    LiveAutomationProvider(
        Closure<ReadResult<List<AutomationRuleSnapshot>>> ruleReader,
        Closure<ReadResult<List<AutomationAuditSnapshot>>> auditReader) {
        this.ruleReader = ruleReader
        this.auditReader = auditReader
    }

    @Override
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope) {
        if (ruleReader == null) {
            return ReadResult.unavailable('The live Automation rule API has not been proven')
        }
        try {
            ReadResult<List<AutomationRuleSnapshot>> result = ruleReader.call(scope)
            result ?: ReadResult.failed('The Automation rule reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Automation rule read failed')
        }
    }

    @Override
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) {
        if (auditReader == null) {
            return ReadResult.unavailable('The live Automation audit API has not been proven')
        }
        try {
            ReadResult<List<AutomationAuditSnapshot>> result = auditReader.call(request)
            result ?: ReadResult.failed('The Automation audit reader returned no result')
        } catch (Exception ignored) {
            ReadResult.failed('The Automation audit read failed')
        }
    }
}

final class DoctorAutomationReads {
    ReadResult<List<AutomationRuleSnapshot>> rules
    ReadResult<List<AutomationAuditSnapshot>> audit
    String ruleProvider
    String auditProvider
    boolean inlineExport
}

final class DoctorAutomationEvidence {
    static final int MAX_EXPORT_BYTES = 5_242_880

    private final AutomationDataProvider live
    private final AutomationDataProvider configuredJson
    private final Map<String, DoctorAutomationReads> remembered =
        Collections.synchronizedMap(new LinkedHashMap<String, DoctorAutomationReads>())

    DoctorAutomationEvidence(AutomationDataProvider live,
                             AutomationDataProvider configuredJson) {
        this.live = live
        this.configuredJson = configuredJson
    }

    DoctorAutomationReads read(AnalyzeRequest request, AnalysisScope scope,
                               Collection<Long> issueIds, int days) {
        boolean inline = request.ruleExportJson != null || request.auditExportJson != null
        AutomationDataProvider requestJson = inline ? new JsonAutomationProvider(
            bytes(request.ruleExportJson), bytes(request.auditExportJson),
            MAX_EXPORT_BYTES) : configuredJson
        ReadResult<List<AutomationRuleSnapshot>> rules = live == null ?
            ReadResult.unavailable('Live Automation rules are unavailable') :
            live.readRules(scope)
        String ruleProvider = 'LIVE'
        if (!rules.complete() && (request.ruleExportRef || request.ruleExportJson) &&
            requestJson != null) {
            rules = requestJson.readRules(scope)
            ruleProvider = 'JSON_FALLBACK'
        }
        AuditRequest auditRequest = auditRequest(rules, issueIds, days)
        ReadResult<List<AutomationAuditSnapshot>> audit = live == null ?
            ReadResult.unavailable('Live Automation audit is unavailable') :
            live.readAudit(auditRequest)
        String auditProvider = 'LIVE'
        if (!audit.complete() && (request.auditExportRef || request.auditExportJson) &&
            requestJson != null) {
            audit = requestJson.readAudit(auditRequest)
            auditProvider = 'JSON_FALLBACK'
        }
        new DoctorAutomationReads(
            rules: rules, audit: audit, ruleProvider: ruleProvider,
            auditProvider: auditProvider, inlineExport: inline)
    }

    void remember(String snapshotId, DoctorAutomationReads reads) {
        remembered.put(snapshotId, reads)
    }

    void remove(String snapshotId) {
        remembered.remove(snapshotId)
    }

    DoctorAutomationReads readForPlan(String snapshotId,
                                      boolean useJsonRules,
                                      boolean useJsonAudit,
                                      AnalysisScope scope,
                                      Collection<Long> issueIds,
                                      int days) {
        DoctorAutomationReads prior = remembered.get(snapshotId)
        boolean inline = prior?.inlineExport == true
        AutomationDataProvider ruleSource = useJsonRules ? configuredJson : live
        AutomationDataProvider auditSource = useJsonAudit ? configuredJson : live
        ReadResult<List<AutomationRuleSnapshot>> rules = useJsonRules && inline ?
            prior.rules : (ruleSource == null ?
                ReadResult.unavailable('Automation rule provider is unavailable') :
                ruleSource.readRules(scope))
        AuditRequest auditRequest = auditRequest(rules, issueIds, days)
        ReadResult<List<AutomationAuditSnapshot>> audit = useJsonAudit && inline ?
            prior.audit : (auditSource == null ?
                ReadResult.unavailable('Automation audit provider is unavailable') :
                auditSource.readAudit(auditRequest))
        new DoctorAutomationReads(
            rules: rules, audit: audit,
            ruleProvider: useJsonRules ? 'JSON_FALLBACK' : 'LIVE',
            auditProvider: useJsonAudit ? 'JSON_FALLBACK' : 'LIVE',
            inlineExport: inline)
    }

    private static AuditRequest auditRequest(
        ReadResult<List<AutomationRuleSnapshot>> rules,
        Collection<Long> issueIds, int days) {
        new AuditRequest(ruleIds: (rules.value ?: [])*.ruleId,
            issueIds: (issueIds ?: []) as List<Long>, requestedDays: days)
    }

    private static byte[] bytes(String value) {
        value == null ? null : value.getBytes(StandardCharsets.UTF_8)
    }
}
