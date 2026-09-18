package structuredoctor

import groovy.transform.Immutable

@Immutable(copyWith = true)
class DuplicateOccurrence {
    String occurrenceId
    String retainChoiceId
    int ordinal
    String rowId
    List<Long> parentPath
    Long parentIssueId
    String provenance
    String creatorId
    boolean hierarchyValid
    boolean nativeHierarchy
    boolean recommended
    List<String> explanations
}

@Immutable(copyWith = true)
class DuplicateGroup {
    String id
    long issueId
    List<DuplicateOccurrence> occurrences
    String recommendedOccurrenceId
    boolean selectable
    List<String> explanations
    List<String> blockers
}

@Immutable(copyWith = true)
class DuplicateAnalysis {
    List<DuplicateGroup> groups
    List<Finding> findings
    boolean complete
    List<String> blockers

    boolean clean() {
        complete && groups.isEmpty()
    }
}
