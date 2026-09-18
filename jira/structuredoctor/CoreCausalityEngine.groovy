package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreCausalityEngine {
    private static final List<CausalEdgeType> CHAIN = [
        CausalEdgeType.AUTOMATION_TO_JIRA_DATA,
        CausalEdgeType.JIRA_DATA_TO_GENERATOR,
        CausalEdgeType.GENERATOR_TO_OCCURRENCE,
        CausalEdgeType.OCCURRENCE_TO_FINDING
    ].asImmutable()

    CausalClaim claim(CausalContext context) {
        if (context == null || blank(context.findingId)) {
            throw new IllegalArgumentException('findingId is required')
        }
        Map<CausalEdgeType, CausalEdge> byType = new LinkedHashMap<>()
        for (CausalEdge edge : context.edges ?: []) {
            validate(edge)
            if (byType.put(edge.type, edge) != null) {
                throw new IllegalArgumentException('duplicate causal edge type: ' + edge.type)
            }
        }

        List<String> edgeIds = []
        List<String> presentEvidence = []
        List<String> missingEvidence = []
        boolean allPresent = true
        for (CausalEdgeType type : CHAIN) {
            CausalEdge edge = byType.get(type)
            if (edge == null || !edge.present) {
                allPresent = false
                missingEvidence.add(type.name())
            } else {
                edgeIds.add(edge.id)
                presentEvidence.add(edge.evidenceId)
            }
        }

        CausalEdge automationEdge = byType.get(CausalEdgeType.AUTOMATION_TO_JIRA_DATA)
        boolean directlyExecuted = automationEdge != null &&
            automationEdge.present && automationEdge.direct
        boolean completeAudit = context.auditCoverage != null &&
            context.auditCoverage.complete()
        EvidenceGrade grade = grade(
            context.configurationConflict,
            !presentEvidence.isEmpty(),
            allPresent,
            directlyExecuted,
            completeAudit)

        List<String> blockers = new ArrayList<>(context.blockers ?: [])
        if (!completeAudit) {
            blockers.add('automation-audit-coverage')
        }
        blockers = blockers.unique().sort()
        List<EvidenceRequirement> requirements = context.requirements ?: []
        String id = CoreCanonical.deterministicId('causal-claim', [
            findingId: context.findingId,
            grade: grade.name(),
            edges: CHAIN.collect { CausalEdgeType type ->
                CausalEdge edge = byType.get(type)
                [type: type.name(), present: edge?.present ?: false,
                 direct: edge?.direct ?: false, evidenceId: edge?.evidenceId]
            },
            requirements: requirements.collect { EvidenceRequirement requirement ->
                [source: requirement.source, fingerprint: requirement.fingerprint,
                 required: requirement.required]
            }
        ])
        new CausalClaim(
            id: id,
            findingId: context.findingId,
            grade: grade,
            edgeIds: edgeIds,
            presentEvidence: presentEvidence,
            missingEvidence: missingEvidence,
            auditCoverage: context.auditCoverage,
            requirements: requirements,
            blockers: blockers
        )
    }

    private static EvidenceGrade grade(boolean configurationConflict,
                                       boolean hasEvidence,
                                       boolean allPresent,
                                       boolean directlyExecuted,
                                       boolean completeAudit) {
        if (allPresent && directlyExecuted && completeAudit) {
            return EvidenceGrade.CONFIRMED_CAUSE
        }
        if (allPresent) {
            return EvidenceGrade.PROBABLE_CAUSE
        }
        if (hasEvidence) {
            return EvidenceGrade.POSSIBLE_CAUSE
        }
        configurationConflict ? EvidenceGrade.CONFIGURATION_CONFLICT :
            EvidenceGrade.POSSIBLE_CAUSE
    }

    private static void validate(CausalEdge edge) {
        if (edge == null || edge.type == null || blank(edge.id) ||
            blank(edge.fromId) || blank(edge.toId)) {
            throw new IllegalArgumentException('causal edge identity is incomplete')
        }
        if (edge.present && blank(edge.evidenceId)) {
            throw new IllegalArgumentException('present causal edge requires evidence')
        }
        if (edge.direct && !edge.present) {
            throw new IllegalArgumentException('direct causal edge must be present')
        }
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}
