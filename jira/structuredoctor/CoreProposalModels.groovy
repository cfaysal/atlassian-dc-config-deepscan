package structuredoctor

import groovy.transform.Immutable

@Immutable(copyWith = true)
class RepairSelection {
    List<String> findingGroupIds
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedPermanentRowIds
}

@Immutable(copyWith = true)
class ProposalCandidate {
    String sourceId
    RepairStrategy strategy
    RepairKind kind
    List<String> findingGroupIds
    List<Long> affectedIssueIds
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
    List<String> permanentRowIds
    String explanation
    List<String> warnings
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class ProposalPlan {
    List<RepairPackage> packages
    boolean complete
    List<String> blockers
}

@Immutable(copyWith = true)
class SimulationRequest {
    StructureSnapshot beforeSnapshot
    StructureSnapshot afterSnapshot
    RepairPackage repairPackage
    Map<String, String> retainOccurrenceByGroup
    List<String> selectedFindingIds
    List<String> approvedNewFindingIds
    List<Finding> beforeFindings
    List<Finding> afterFindings
    Map<String, String> currentFingerprints
    List<String> selectedPermanentRowIds
    boolean capabilityAvailable
}
