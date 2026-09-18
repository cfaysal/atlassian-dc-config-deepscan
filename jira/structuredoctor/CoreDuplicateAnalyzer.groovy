package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreDuplicateAnalyzer {
    DuplicateAnalysis analyze(StructureSnapshot snapshot) {
        if (snapshot == null || snapshot.occurrences == null) {
            return new DuplicateAnalysis(
                groups: [], findings: [], complete: false,
                blockers: ['structure-forest'])
        }

        Map<Long, List<OccurrenceSnapshot>> byIssue = new LinkedHashMap<>()
        for (OccurrenceSnapshot occurrence : snapshot.occurrences) {
            List<OccurrenceSnapshot> values = byIssue.get(occurrence.issueId)
            if (values == null) {
                values = []
                byIssue.put(occurrence.issueId, values)
            }
            values.add(occurrence)
        }

        Map<Long, IssueRelationSnapshot> relations = CoreDuplicateSupport.relationIndex(snapshot.relations)
        Map<Long, HierarchyLevel> levels = CoreDuplicateSupport.hierarchyIndex(snapshot.hierarchy)
        Map<String, GeneratorSnapshot> generators = CoreDuplicateSupport.generatorIndex(snapshot.generators)
        List<GeneratorSnapshot> duplicateFilters = (snapshot.generators ?: []).findAll {
            GeneratorSnapshot generator ->
                generator.enabled && generator.type == 'DUPLICATES_FILTER'
        }

        List<DuplicateGroup> groups = []
        List<Finding> findings = []
        for (Map.Entry<Long, List<OccurrenceSnapshot>> entry : byIssue.entrySet()) {
            if (entry.value.size() < 2) {
                continue
            }
            DuplicateGroup group = buildGroup(snapshot, entry.key, entry.value,
                relations, levels, generators, duplicateFilters)
            groups.add(group)
            findings.add(buildFinding(snapshot, group))
        }

        List<String> blockers = groups.collectMany { DuplicateGroup group -> group.blockers }
            .unique().sort()
        Set<String> evidenceBlockers = [
            'structure-snapshot',
            'structure-generators',
            'jira-hierarchy',
            'jira-relations',
            'occurrence-provenance',
            'physical-occurrence-identity'
        ] as Set<String>
        new DuplicateAnalysis(
            groups: groups,
            findings: findings,
            complete: snapshot.complete && !blockers.any {
                String blocker -> evidenceBlockers.contains(blocker)
            },
            blockers: blockers
        )
    }

    private static DuplicateGroup buildGroup(StructureSnapshot snapshot,
                                             long issueId,
                                             List<OccurrenceSnapshot> occurrences,
                                             Map<Long, IssueRelationSnapshot> relations,
                                             Map<Long, HierarchyLevel> levels,
                                             Map<String, GeneratorSnapshot> generators,
                                             List<GeneratorSnapshot> duplicateFilters) {
        List<String> blockers = []
        if (!snapshot.complete) {
            blockers.add('structure-snapshot')
        }
        if ((snapshot.generators ?: []).any { GeneratorSnapshot generator -> !generator.complete }) {
            blockers.add('structure-generators')
        }
        if (snapshot.hierarchy == null || levels.isEmpty() ||
            blank(snapshot.hierarchy.fingerprint) ||
            !CoreDuplicateSupport.validHierarchyMapping(snapshot.hierarchy)) {
            blockers.add('jira-hierarchy')
        }

        IssueRelationSnapshot relation = relations.get(issueId)
        HierarchyLevel childLevel = relation == null ? null : levels.get(relation.issueTypeId)
        if (relation == null) {
            blockers.add('jira-relations')
        } else if (childLevel == null) {
            blockers.add('jira-hierarchy')
        }

        List<DuplicateOccurrence> choices = []
        Set<String> occurrenceIds = [] as Set<String>
        int ordinal = 0
        for (OccurrenceSnapshot occurrence : occurrences) {
            ordinal++
            if (blank(occurrence.occurrenceId) || !occurrenceIds.add(occurrence.occurrenceId)) {
                blockers.add('physical-occurrence-identity')
            }
            if (!occurrence.provenanceComplete || blank(occurrence.provenance) ||
                (occurrence.provenance != 'PERMANENT' && blank(occurrence.creatorId))) {
                blockers.add('occurrence-provenance')
            }
            boolean hierarchyValid = hierarchyValid(
                occurrence, relation, childLevel, relations, levels)
            boolean nativeHierarchy = relation != null &&
                relation.nativeParentId != null &&
                relation.nativeParentId == occurrence.parentIssueId
            choices.add(new DuplicateOccurrence(
                occurrenceId: occurrence.occurrenceId,
                retainChoiceId: 'retain:' + occurrence.occurrenceId,
                ordinal: ordinal,
                rowId: occurrence.rowId,
                parentPath: occurrence.parentPath,
                parentIssueId: occurrence.parentIssueId,
                provenance: occurrence.provenance,
                creatorId: occurrence.creatorId,
                hierarchyValid: hierarchyValid,
                nativeHierarchy: nativeHierarchy,
                recommended: false,
                explanations: occurrenceExplanations(hierarchyValid, nativeHierarchy)
            ))
        }

        List<DuplicateOccurrence> valid = choices.findAll {
            DuplicateOccurrence choice -> choice.hierarchyValid
        }
        if (valid.isEmpty()) {
            blockers.add('no-hierarchy-valid-occurrence')
        }
        String recommendation = recommendation(valid)
        if (recommendation != null) {
            choices = choices.collect { DuplicateOccurrence choice ->
                choice.copyWith(recommended: choice.occurrenceId == recommendation)
            }
        }

        List<String> explanations = CoreDuplicateSupport.groupExplanations(
            occurrences, generators, duplicateFilters)
        String groupId = CoreCanonical.deterministicId('duplicate-group', [
            structureId: snapshot.structureId,
            issueId: issueId,
            occurrenceIds: choices*.occurrenceId
        ])
        new DuplicateGroup(
            id: groupId,
            issueId: issueId,
            occurrences: choices,
            recommendedOccurrenceId: recommendation,
            selectable: blockers.isEmpty(),
            explanations: explanations,
            blockers: blockers.unique().sort()
        )
    }

    private static boolean hierarchyValid(OccurrenceSnapshot occurrence,
                                          IssueRelationSnapshot relation,
                                          HierarchyLevel childLevel,
                                          Map<Long, IssueRelationSnapshot> relations,
                                          Map<Long, HierarchyLevel> levels) {
        if (relation == null || childLevel == null) {
            return false
        }
        long topRank = levels.values().collect { HierarchyLevel level -> level.rank }.max()
        if (childLevel.rank == topRank) {
            return occurrence.parentIssueId == null
        }
        if (occurrence.parentIssueId == null) {
            return false
        }
        IssueRelationSnapshot parent = relations.get(occurrence.parentIssueId)
        HierarchyLevel parentLevel = parent == null ? null : levels.get(parent.issueTypeId)
        parentLevel != null && parentLevel.rank == childLevel.rank + 1L
    }

    private static String recommendation(List<DuplicateOccurrence> valid) {
        if (valid.size() == 1) {
            return valid[0].occurrenceId
        }
        List<DuplicateOccurrence> nativeChoices = valid.findAll {
            DuplicateOccurrence choice -> choice.nativeHierarchy
        }
        nativeChoices.size() == 1 ? nativeChoices[0].occurrenceId : null
    }

    private static List<String> occurrenceExplanations(boolean valid, boolean nativeHierarchy) {
        List<String> values = [valid ? 'Path follows a configured hierarchy level' :
            'Path does not follow a configured hierarchy level']
        if (nativeHierarchy) {
            values.add('Path follows the native Jira parent relation')
        }
        values
    }

    private static Finding buildFinding(StructureSnapshot snapshot, DuplicateGroup group) {
        List<EvidenceRequirement> requirements = []
        if (snapshot.hierarchy != null && !blank(snapshot.hierarchy.fingerprint)) {
            requirements.add(new EvidenceRequirement(
                'jira-hierarchy', snapshot.hierarchy.fingerprint, true))
        }
        if (!blank(snapshot.fingerprint)) {
            requirements.add(new EvidenceRequirement(
                'structure-snapshot', snapshot.fingerprint, true))
        }
        new Finding(
            id: CoreCanonical.deterministicId('finding', [groupId: group.id]),
            type: FindingType.DUPLICATE,
            issueId: group.issueId,
            occurrenceIds: group.occurrences*.occurrenceId,
            summary: 'Work item has ' + group.occurrences.size() + ' physical occurrences',
            requirements: requirements,
            blockers: group.blockers
        )
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}
