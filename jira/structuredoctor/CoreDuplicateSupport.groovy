package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreDuplicateSupport {
    private CoreDuplicateSupport() {
        throw new UnsupportedOperationException('utility class')
    }

    static List<String> groupExplanations(List<OccurrenceSnapshot> occurrences,
                                          Map<String, GeneratorSnapshot> generators,
                                          List<GeneratorSnapshot> filters) {
        List<String> values = []
        Set<String> creators = occurrences*.creatorId.findAll {
            String value -> !blank(value)
        } as Set<String>
        Set<List<Long>> paths = occurrences*.parentPath as Set<List<Long>>
        Set<String> provenance = occurrences*.provenance.findAll {
            String value -> !blank(value)
        } as Set<String>
        if (creators.size() > 1) {
            values.add('Multiple generators render this work item')
        }
        if (paths.size() > 1) {
            values.add('Occurrences follow multiple paths')
        }
        if (provenance.contains('PERMANENT') && provenance.size() > 1) {
            values.add('Permanent and generated occurrences overlap')
        }
        if (provenance.contains('ADVANCED_ROADMAPS') && provenance.contains('JIRA_LINK')) {
            values.add('Native hierarchy and Jira link paths overlap')
        }
        if (filters.isEmpty()) {
            values.add('No enabled duplicates filter is present')
        } else if (filterRunsBeforeSource(creators, generators, filters)) {
            values.add('Duplicates filter runs before an occurrence source')
        }
        if (paths.size() == 1 && creators.size() <= 1) {
            values.add('Semantically identical occurrences remain separate physical rows')
        }
        values
    }

    static Map<Long, IssueRelationSnapshot> relationIndex(List<IssueRelationSnapshot> relations) {
        Map<Long, IssueRelationSnapshot> values = [:]
        for (IssueRelationSnapshot relation : relations ?: []) {
            values.put(relation.issueId, relation)
        }
        values
    }

    static Map<Long, HierarchyLevel> hierarchyIndex(HierarchySnapshot hierarchy) {
        Map<Long, HierarchyLevel> values = [:]
        for (HierarchyLevel level : hierarchy?.levels ?: []) {
            for (Long issueTypeId : level.issueTypeIds ?: []) {
                values.put(issueTypeId, level)
            }
        }
        values
    }

    static boolean validHierarchyMapping(HierarchySnapshot hierarchy) {
        Set<Long> assigned = [] as Set<Long>
        for (HierarchyLevel level : hierarchy?.levels ?: []) {
            for (Long issueTypeId : level.issueTypeIds ?: []) {
                if (issueTypeId == null || !assigned.add(issueTypeId)) {
                    return false
                }
            }
        }
        !assigned.isEmpty()
    }

    static Map<String, GeneratorSnapshot> generatorIndex(List<GeneratorSnapshot> generators) {
        Map<String, GeneratorSnapshot> values = [:]
        for (GeneratorSnapshot generator : generators ?: []) {
            values.put(String.valueOf(generator.generatorId), generator)
        }
        values
    }

    private static boolean filterRunsBeforeSource(Set<String> creators,
                                                  Map<String, GeneratorSnapshot> generators,
                                                  List<GeneratorSnapshot> filters) {
        List<Integer> sourceOrders = creators.collect {
            String creator -> generators.get(creator)?.order
        }.findAll { Integer order -> order != null }
        sourceOrders.isEmpty() ? false : filters.any { GeneratorSnapshot filter ->
            filter.order <= sourceOrders.max()
        }
    }

    private static boolean blank(String value) {
        value == null || value.trim().isEmpty()
    }
}
