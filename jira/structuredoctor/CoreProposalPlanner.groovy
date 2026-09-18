package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreProposalPlanner {
    ProposalPlan plan(StructureSnapshot snapshot,
                      DuplicateAnalysis duplicates,
                      RepairSelection selection,
                      ProposalSource source) {
        List<String> blockers = validate(snapshot, duplicates, selection, source)
        if (selection == null || selection.findingGroupIds == null ||
            selection.findingGroupIds.isEmpty()) {
            return new ProposalPlan(
                packages: [], complete: blockers.isEmpty(), blockers: blockers)
        }

        Map<String, DuplicateGroup> groups = (duplicates?.groups ?: []).collectEntries {
            DuplicateGroup group -> [(group.id): group]
        } as Map<String, DuplicateGroup>
        validateSelection(selection, groups, blockers)
        if (!blockers.isEmpty()) {
            return new ProposalPlan(packages: [], complete: false,
                blockers: blockers.unique().sort())
        }

        ReadResult<List<ProposalCandidate>> candidateRead = source.readCandidates(snapshot)
        if (candidateRead == null || !candidateRead.complete() || candidateRead.value == null) {
            blockers.add('proposal-source')
            return new ProposalPlan(packages: [], complete: false,
                blockers: blockers.unique().sort())
        }

        Set<String> selected = selection.findingGroupIds as Set<String>
        List<ProposalCandidate> relevant = candidateRead.value.findAll {
            ProposalCandidate candidate ->
                !(candidate.findingGroupIds as Set<String>).intersect(selected).isEmpty()
        }
        for (String groupId : selected) {
            if (!relevant.any { ProposalCandidate candidate ->
                candidate.findingGroupIds.contains(groupId)
            }) {
                blockers.add('no-proposal:' + groupId)
            }
        }
        List<RepairPackage> packages = relevant.collect {
            ProposalCandidate candidate -> buildPackage(snapshot, candidate)
        }
        new ProposalPlan(
            packages: packages,
            complete: blockers.isEmpty(),
            blockers: blockers.unique().sort()
        )
    }

    private static List<String> validate(StructureSnapshot snapshot,
                                         DuplicateAnalysis duplicates,
                                         RepairSelection selection,
                                         ProposalSource source) {
        List<String> blockers = []
        if (snapshot == null || !snapshot.complete || snapshot.planningFingerprint() == null) {
            blockers.add('structure-snapshot')
        }
        if (duplicates == null || !duplicates.complete) {
            blockers.add('duplicate-analysis')
        }
        if (selection == null) {
            blockers.add('repair-selection')
        }
        if (source == null) {
            blockers.add('proposal-source')
        }
        blockers
    }

    private static void validateSelection(RepairSelection selection,
                                          Map<String, DuplicateGroup> groups,
                                          List<String> blockers) {
        Set<String> selected = new LinkedHashSet<>(selection.findingGroupIds ?: [])
        if (selected.size() != (selection.findingGroupIds ?: []).size()) {
            blockers.add('duplicate-finding-selection')
        }
        for (String groupId : selected) {
            DuplicateGroup group = groups.get(groupId)
            if (group == null) {
                blockers.add('unknown-finding-group:' + groupId)
                continue
            }
            if (!group.selectable) {
                blockers.add('blocked-finding-group:' + groupId)
            }
            String retained = selection.retainOccurrenceByGroup?.get(groupId)
            if (retained == null || !group.occurrences*.occurrenceId.contains(retained)) {
                blockers.add('invalid-retain-occurrence:' + groupId)
            }
        }
        for (String groupId : selection.retainOccurrenceByGroup?.keySet() ?: []) {
            if (!selected.contains(groupId)) {
                blockers.add('unexpected-retain-selection:' + groupId)
            }
        }
    }

    private static RepairPackage buildPackage(StructureSnapshot snapshot,
                                              ProposalCandidate candidate) {
        List<String> candidateBlockers = candidate.blockers ?: []
        List<Confirmation> confirmations = [Confirmation.STRUCTURE_CHANGE]
        if (candidate.kind == RepairKind.JIRA_DATA) {
            confirmations.add(Confirmation.JIRA_DATA_CHANGE)
        }
        Map<String, Object> identity = [
            structureId: snapshot.structureId,
            sourceId: candidate.sourceId,
            strategy: candidate.strategy.name(),
            kind: candidate.kind.name(),
            groups: candidate.findingGroupIds,
            issues: candidate.affectedIssueIds,
            generators: candidate.generatorIds,
            before: candidate.beforeState,
            after: candidate.afterState
        ]
        new RepairPackage(
            id: CoreCanonical.deterministicId('repair-package', identity),
            kind: candidate.kind,
            strategy: candidate.strategy,
            findingGroupIds: candidate.findingGroupIds,
            affectedIssueIds: candidate.affectedIssueIds,
            generatorIds: candidate.generatorIds,
            beforeState: candidate.beforeState,
            afterState: candidate.afterState,
            permanentRowIds: candidate.permanentRowIds,
            explanation: candidate.explanation,
            warnings: candidate.warnings,
            confirmations: confirmations,
            requirements: candidate.requirements,
            blockers: candidateBlockers,
            selectable: candidateBlockers.isEmpty()
        )
    }
}
