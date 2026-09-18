package structuredoctor

interface StructureCatalogProvider {
    ReadResult<List<StructureChoice>> listStructures()
}

interface HierarchyProvider {
    ReadResult<HierarchySnapshot> readHierarchy()
}

interface StructureSnapshotProvider {
    ReadResult<StructureSnapshot> readStructure(long structureId)
}

interface JiraDataProvider {
    ReadResult<List<IssueRelationSnapshot>> readIssues(Collection<Long> issueIds)
}

interface AutomationDataProvider {
    ReadResult<List<AutomationRuleSnapshot>> readRules(AnalysisScope scope)
    ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request)
}

interface ProposalSource {
    ReadResult<List<ProposalCandidate>> readCandidates(StructureSnapshot snapshot)
}

interface StructureMutationGateway {
    MutationReceipt applyGeneratorPackage(GeneratorMutation mutation)
    MutationReceipt restoreGeneratorPackage(GeneratorMutation mutation)
}

interface JiraDataMutationGateway {
    MutationReceipt applyIssueData(JiraDataMutation mutation)
    MutationReceipt restoreIssueData(JiraDataMutation mutation)
}

interface RepairJournal {
    RepairOperation find(String operationId)
    void write(RepairOperation operation)
}

interface StructureLock {
    Object withLock(long structureId, Closure<Object> work)
}

interface DoctorClock {
    String now()
}

interface RepairInfrastructure {
    RepairAvailability availability()
    RepairOperation find(String operationId)
    RepairOperation findPending(long structureId, List<Long> issueIds)
    Object withLocks(long structureId, List<Long> issueIds, Closure<Object> work)
    RepairRefresh refresh(RepairApplyRequest request)
    RepairRefresh resumeContext(RepairOperation operation)
    void persist(RepairOperation operation, RepairPackage repairPackage)
    RepairMutationOutcome mutate(RepairPackage repairPackage)
    VerificationOutcome verify(RepairRefresh context, boolean restored)
    RepairMutationOutcome restore(RepairPackage repairPackage)
}
