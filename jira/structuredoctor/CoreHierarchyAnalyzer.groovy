package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreHierarchyAnalyzer {
    HierarchyAnalysis analyze(StructureSnapshot snapshot) {
        List<String> blockers = readinessBlockers(snapshot)
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        Map<Long, HierarchyLevel> levelsByIssueType = [:]
        for (HierarchyLevel level : snapshot.hierarchy.levels) {
            for (Long issueTypeId : level.issueTypeIds) {
                if (issueTypeId == null || levelsByIssueType.containsKey(issueTypeId)) {
                    blockers.add('jira-hierarchy')
                } else {
                    levelsByIssueType.put(issueTypeId, level)
                }
            }
        }
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        Map<Long, IssueRelationSnapshot> relationsByIssue = [:]
        for (IssueRelationSnapshot relation : snapshot.relations) {
            if (relationsByIssue.put(relation.issueId, relation) != null) {
                blockers.add('jira-relations')
            }
        }
        if (!blockers.isEmpty()) {
            return result([], blockers)
        }

        long topRank = snapshot.hierarchy.levels.collect { HierarchyLevel level -> level.rank }.max()
        List<Finding> findings = []
        for (IssueRelationSnapshot relation : snapshot.relations) {
            analyzeRelation(snapshot, relation, relationsByIssue, levelsByIssueType,
                topRank, findings, blockers)
        }
        for (OccurrenceSnapshot occurrence : snapshot.occurrences) {
            analyzeOccurrence(snapshot, occurrence, relationsByIssue, findings, blockers)
        }

        findings.sort { Finding left, Finding right ->
            int issueOrder = left.issueId <=> right.issueId
            if (issueOrder != 0) {
                return issueOrder
            }
            int typeOrder = left.type.name() <=> right.type.name()
            typeOrder != 0 ? typeOrder :
                left.occurrenceIds.join(',') <=> right.occurrenceIds.join(',')
        }
        result(findings, blockers.unique().sort())
    }

    private static List<String> readinessBlockers(StructureSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete || snapshot.hierarchy == null ||
            snapshot.hierarchy.levels == null || snapshot.hierarchy.levels.isEmpty() ||
            blank(snapshot.hierarchy.fingerprint)) {
            return ['jira-hierarchy']
        }
        if (snapshot.relations == null) {
            return ['jira-relations']
        }
        if (snapshot.occurrences == null) {
            return ['structure-forest']
        }
        []
    }

    private static void analyzeRelation(StructureSnapshot snapshot,
                                        IssueRelationSnapshot relation,
                                        Map<Long, IssueRelationSnapshot> relationsByIssue,
                                        Map<Long, HierarchyLevel> levelsByIssueType,
                                        long topRank,
                                        List<Finding> findings,
                                        List<String> blockers) {
        HierarchyLevel childLevel = levelsByIssueType.get(relation.issueTypeId)
        if (childLevel == null) {
            blockers.add('jira-hierarchy')
            return
        }

        List<Long> leadingParents = relation.leadingParentIds ?: []
        if (relation.nativeParentId == null) {
            if (leadingParents.size() == 1) {
                findings.add(finding(snapshot, FindingType.MISSING_PARENT,
                    relation.issueId, [], 'Native parent is missing'))
            } else if (leadingParents.size() > 1) {
                findings.add(finding(snapshot, FindingType.CONFLICTING_PARENT,
                    relation.issueId, [], 'Multiple parent relationships compete'))
            } else if (childLevel.rank < topRank) {
                findings.add(finding(snapshot, FindingType.ORPHAN,
                    relation.issueId, [], 'Work item has no parent relationship'))
            }
            return
        }

        if (!leadingParents.isEmpty() &&
            (leadingParents.size() > 1 || !leadingParents.contains(relation.nativeParentId))) {
            findings.add(finding(snapshot, FindingType.CONFLICTING_PARENT,
                relation.issueId, [], 'Configured parent relationships disagree'))
        }

        IssueRelationSnapshot parent = relationsByIssue.get(relation.nativeParentId)
        if (parent == null) {
            blockers.add('jira-relations')
            return
        }
        HierarchyLevel parentLevel = levelsByIssueType.get(parent.issueTypeId)
        if (parentLevel == null) {
            blockers.add('jira-hierarchy')
            return
        }
        if (parentLevel.rank != childLevel.rank + 1L) {
            findings.add(finding(snapshot, FindingType.INVALID_LEVEL,
                relation.issueId, [], 'Parent is on an invalid hierarchy level'))
        }
    }

    private static void analyzeOccurrence(StructureSnapshot snapshot,
                                          OccurrenceSnapshot occurrence,
                                          Map<Long, IssueRelationSnapshot> relationsByIssue,
                                          List<Finding> findings,
                                          List<String> blockers) {
        IssueRelationSnapshot relation = relationsByIssue.get(occurrence.issueId)
        if (relation == null) {
            blockers.add('jira-relations')
            return
        }
        if (relation.nativeParentId != null &&
            occurrence.parentIssueId != relation.nativeParentId) {
            findings.add(finding(snapshot, FindingType.WRONG_PATH,
                occurrence.issueId, [occurrence.occurrenceId],
                'Occurrence is rendered below a different parent'))
        }
    }

    private static Finding finding(StructureSnapshot snapshot,
                                   FindingType type,
                                   long issueId,
                                   List<String> occurrenceIds,
                                   String summary) {
        Map<String, Object> identity = [
            structureId: snapshot.structureId,
            type: type.name(),
            issueId: issueId,
            occurrences: occurrenceIds
        ]
        new Finding(
            id: CoreCanonical.deterministicId('finding', identity),
            type: type,
            issueId: issueId,
            occurrenceIds: occurrenceIds,
            summary: summary,
            requirements: [new EvidenceRequirement(
                'jira-hierarchy', snapshot.hierarchy.fingerprint, true)],
            blockers: []
        )
    }

    private static HierarchyAnalysis result(List<Finding> findings, List<String> blockers) {
        new HierarchyAnalysis(
            findings: findings,
            complete: blockers.isEmpty(),
            blockers: blockers
        )
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}
