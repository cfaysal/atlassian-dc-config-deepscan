package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreImpactSimulator {
    ImpactResult simulate(SimulationRequest request) {
        List<String> blockers = []
        if (request == null || request.beforeSnapshot == null ||
            request.afterSnapshot == null || request.repairPackage == null) {
            return result(false, [], [], [], [], ['simulation-input'])
        }
        if (!request.capabilityAvailable) {
            blockers.add('repair-capability-unavailable')
        }
        if (!request.beforeSnapshot.complete) {
            blockers.add('incomplete-before-population')
        }
        if (!request.afterSnapshot.complete) {
            blockers.add('incomplete-preview')
        }
        if (!request.repairPackage.selectable) {
            blockers.add('repair-package-blocked')
        }
        checkFingerprints(request, blockers)

        Map<String, OccurrenceSnapshot> before = occurrenceIndex(
            request.beforeSnapshot.occurrences, 'before', blockers)
        Map<String, OccurrenceSnapshot> after = occurrenceIndex(
            request.afterSnapshot.occurrences, 'after', blockers)
        List<String> added = after.keySet().findAll { String id -> !before.containsKey(id) } as List<String>
        List<String> removed = before.keySet().findAll { String id -> !after.containsKey(id) } as List<String>
        List<String> moved = []
        List<String> unchanged = []
        for (String id : before.keySet().findAll { String value -> after.containsKey(value) }) {
            if (signature(before.get(id)) == signature(after.get(id))) {
                unchanged.add(id)
            } else {
                moved.add(id)
            }
        }

        checkRetainedOccurrences(request, after, blockers)
        checkPermanentRows(request, removed, blockers)
        checkFindingChanges(request, blockers)
        result(
            request.beforeSnapshot.complete && request.afterSnapshot.complete,
            added, removed, moved, unchanged, blockers.unique().sort())
    }

    private static void checkFingerprints(SimulationRequest request,
                                          List<String> blockers) {
        Map<String, String> current = request.currentFingerprints ?: [:]
        for (EvidenceRequirement requirement : request.repairPackage.requirements ?: []) {
            if (requirement.required && current.get(requirement.source) != requirement.fingerprint) {
                blockers.add('stale-dependency:' + requirement.source)
            }
        }
    }

    private static Map<String, OccurrenceSnapshot> occurrenceIndex(
        List<OccurrenceSnapshot> occurrences,
        String side,
        List<String> blockers) {
        if (occurrences == null) {
            blockers.add('unknown-' + side + '-population')
            return [:]
        }
        Map<String, OccurrenceSnapshot> values = new LinkedHashMap<>()
        for (OccurrenceSnapshot occurrence : occurrences) {
            if (occurrence.occurrenceId == null ||
                values.put(occurrence.occurrenceId, occurrence) != null) {
                blockers.add('invalid-' + side + '-occurrence-identity')
            }
        }
        values
    }

    private static void checkRetainedOccurrences(SimulationRequest request,
                                                 Map<String, OccurrenceSnapshot> after,
                                                 List<String> blockers) {
        Map<String, Finding> beforeFindings = (request.beforeFindings ?: []).collectEntries {
            Finding finding -> [(finding.id): finding]
        } as Map<String, Finding>
        for (String groupId : request.repairPackage.findingGroupIds ?: []) {
            Finding finding = beforeFindings.get(groupId)
            if (finding?.type != FindingType.DUPLICATE) {
                continue
            }
            String retained = request.retainOccurrenceByGroup?.get(groupId)
            if (retained == null) {
                blockers.add('retain-occurrence-required:' + groupId)
            } else if (!after.containsKey(retained)) {
                blockers.add('retained-occurrence-lost:' + retained)
            }
        }
    }

    private static void checkPermanentRows(SimulationRequest request,
                                           List<String> removed,
                                           List<String> blockers) {
        Set<String> selected = (request.selectedPermanentRowIds ?: []) as Set<String>
        for (String rowId : request.repairPackage.permanentRowIds ?: []) {
            if (removed.contains(rowId) && !selected.contains(rowId)) {
                blockers.add('permanent-row-not-selected:' + rowId)
            }
        }
    }

    private static void checkFindingChanges(SimulationRequest request,
                                            List<String> blockers) {
        Set<String> beforeIds = (request.beforeFindings ?: [])*.id as Set<String>
        Set<String> afterIds = (request.afterFindings ?: [])*.id as Set<String>
        Set<String> selected = (request.selectedFindingIds ?: []) as Set<String>
        Set<String> approvedNew = (request.approvedNewFindingIds ?: []) as Set<String>
        for (String findingId : afterIds - beforeIds) {
            if (!approvedNew.contains(findingId)) {
                blockers.add('new-unapproved-finding:' + findingId)
            }
        }
        for (String findingId : beforeIds - afterIds) {
            if (!selected.contains(findingId)) {
                blockers.add('unselected-finding-changed:' + findingId)
            }
        }
    }

    private static Map<String, Object> signature(OccurrenceSnapshot occurrence) {
        [
            rowId: occurrence.rowId,
            parentPath: occurrence.parentPath,
            parentIssueId: occurrence.parentIssueId,
            depth: occurrence.depth,
            position: occurrence.position,
            provenance: occurrence.provenance,
            creatorId: occurrence.creatorId
        ] as Map<String, Object>
    }

    private static ImpactResult result(boolean complete,
                                       List<String> added,
                                       List<String> removed,
                                       List<String> moved,
                                       List<String> unchanged,
                                       List<String> blockers) {
        new ImpactResult(
            complete: complete,
            safeToApply: blockers.isEmpty(),
            added: added,
            removed: removed,
            moved: moved,
            unchanged: unchanged,
            blockers: blockers
        )
    }
}
