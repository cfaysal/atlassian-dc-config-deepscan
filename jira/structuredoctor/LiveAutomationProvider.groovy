package structuredoctor

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
