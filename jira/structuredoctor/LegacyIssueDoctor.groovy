package structuredoctor

final class LegacyIssueDoctor {
    static final String CONFIRMATION = 'SET_PARENT_LINK'

    private final Closure<?> inspector
    private final Closure<?> parentLinkRepair

    LegacyIssueDoctor(Closure<?> inspector, Closure<?> parentLinkRepair) {
        this.inspector = inspector
        this.parentLinkRepair = parentLinkRepair
    }

    Object analyze(long structureId, String issueKey) {
        if (inspector == null) {
            throw new IllegalStateException('Legacy issue analysis is unavailable')
        }
        inspector.call(structureId, issueKey)
    }

    Object repair(long structureId, String issueKey, String confirmation) {
        if (confirmation != CONFIRMATION) {
            throw new IllegalArgumentException('Invalid Parent Link confirmation')
        }
        if (parentLinkRepair == null) {
            throw new IllegalStateException('Legacy Parent Link repair is unavailable')
        }
        parentLinkRepair.call(structureId, issueKey, confirmation)
    }
}
