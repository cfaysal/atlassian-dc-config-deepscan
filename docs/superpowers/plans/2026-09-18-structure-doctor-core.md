# Structure Doctor Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an administrator-only Structure Doctor that analyzes one selected Structure against the global Jira hierarchy, Structure generators, Jira relationship data, Automation rules, and available audit evidence, then offers only fully simulated, explicitly selected, fresh, verifiable repairs.

**Architecture:** Keep `jira/structureIssueDoctor.groovy` as a thin ScriptRunner REST controller. Put Jira-free snapshots, analyzers, causal evidence, proposal construction, impact simulation, and repair state in focused classes under `jira/structuredoctor/`. Live Jira, Advanced Roadmaps, Structure, and Automation access stays behind capability-gated adapters. The global Jira hierarchy and Automation rules have read interfaces only and can never enter the mutation boundary.

**Tech Stack:** Groovy 3.0.21, ScriptRunner custom REST endpoints, Jira Data Center and Advanced Roadmaps APIs, Tempo Structure APIs, Jira Automation Data Center read adapters, canonical JSON plus SHA-256, synthetic JSON fixtures, executable Groovy tests, and GitHub Actions.

---

## Evidence and execution rules

- Work item: `OP-1371`, already `In Progress`.
- Approved design: `docs/superpowers/specs/2026-09-18-structure-doctor-core-design.md`.
- Secured baseline: `C:\Users\CFaysal\Qsync\Documents\Coopers\CSS\structure_issue_doctor.groovy`.
- Branch: `op-1371-structure-doctor-dedupe`.
- Every commit starts with `OP-1371 ` and contains no `Co-authored-by` line.
- Use synthetic fixtures only. Never commit customer work-item keys, rule payloads, JQL, hostnames, credentials, or private infrastructure details.
- Tag `pre-op-1371-structure-doctor-core` immediately before the first structural source split.
- Every production behavior begins with a failing test that is observed to fail for the intended reason.
- Keep each new Groovy source file at or below 250 lines. The byte-identical 992-line legacy controller imported in Task 2 is the temporary baseline exception and is split beginning in Task 3.
- Do not install, deploy, or mutate Jira, Structure, the global hierarchy, or Automation during offline implementation.
- A missing, failed, capped, or partial read is never converted to an empty successful result.
- A live writer remains disabled until preview, revision, mutation, restore, lock, journal, and verification capabilities are all proven on an authorized disposable target.

## Target file map

| Path | Responsibility |
| --- | --- |
| `jira/structureIssueDoctor.groovy` | Thin REST declarations, dependency assembly, response conversion |
| `jira/structuredoctor/CoreRead.groovy` | Complete, incomplete, failed, and unavailable read states with coverage |
| `jira/structuredoctor/CoreModels.groovy` | Immutable hierarchy, Structure, issue, finding, evidence, proposal, and operation models |
| `jira/structuredoctor/CoreCanonical.groovy` | Canonical JSON, fingerprints, deterministic IDs |
| `jira/structuredoctor/CoreHierarchyAnalyzer.groovy` | Missing parent, conflicting parent, invalid level, orphan, and wrong-path findings |
| `jira/structuredoctor/CoreDuplicateAnalyzer.groovy` | Duplicate grouping, occurrence validity, retention choices, provenance blockers |
| `jira/structuredoctor/CoreAutomationAnalyzer.groovy` | Normalized rule conflict and scope analysis |
| `jira/structuredoctor/CoreCausalityEngine.groovy` | Bounded causal graph and evidence grades |
| `jira/structuredoctor/CoreProposalPlanner.groovy` | Server-generated repair packages and blockers |
| `jira/structuredoctor/CoreImpactSimulator.groovy` | Complete Structure forest and Jira-data deltas |
| `jira/structuredoctor/CoreRepairCoordinator.groovy` | Freshness, confirmation, locking, idempotency, verification, rollback state |
| `jira/structuredoctor/DoctorContracts.groovy` | Jira-free read gateways and narrowly scoped mutable gateways |
| `jira/structuredoctor/LiveConfigurationDiscovery.groovy` | Product versions, hierarchy read, capability fingerprints |
| `jira/structuredoctor/LiveStructureGateway.groovy` | Complete forest, generator, provenance, revision, preview, Structure mutation |
| `jira/structuredoctor/LiveAutomationProvider.groovy` | Proven live rule and audit reads only |
| `jira/structuredoctor/JsonAutomationProvider.groovy` | Size-limited official rule and audit export parser |
| `jira/structuredoctor/LiveJiraGateway.groovy` | Batched issue, history, permission, native parent read and allowed Jira-data mutation |
| `jira/structuredoctor/LiveRepairInfrastructure.groovy` | Cluster lock, journal, mutation, restore, recalculation verification |
| `jira/structuredoctor/LegacyIssueDoctor.groovy` | Existing issue diagnosis and Parent Link behavior |
| `jira/structuredoctor/DoctorApplication.groovy` | Analyze, Plan, Apply, Status orchestration |
| `jira/structuredoctor/DoctorRenderer.groovy` | Escaped HTML and JSON, identifier-only browser requests |
| `jira/tests/structureIssueDoctor.tests.groovy` | Jira-free behavior suite |
| `jira/tests/structureIssueDoctor.http.tests.groovy` | HTTP, authentication, authorization, and request-contract suite |
| `jira/tests/structureIssueDoctor.integration.groovy` | Authorized disposable-instance verification and restoration |
| `jira/tests/fixtures/structuredoctor/*.json` | Synthetic hierarchy, forest, rule, audit, and history fixtures |
| `tools/structure-doctor-capability-probe.groovy` | Read-only installed API and version report |
| `tools/structure-doctor-mutation-probe.groovy` | Separately authorized reversible mutation proof |
| `tools/jira-typecheck.jsh` | Actual Jira and app classpath compilation check |

## Task 1: Establish the read-only capability boundary

**Files:** Create `tools/structure-doctor-capability-probe.groovy` and `tools/jira-typecheck.jsh`; modify `README.md`.

- [ ] Write a parse test that loads the probe and asserts it declares no method whose name starts with `set`, `update`, `save`, `delete`, `create`, `enable`, `disable`, or `publish`.
- [ ] Run the test and observe failure because the probe does not exist.
- [ ] Add a read-only ScriptRunner probe that reports product versions, resolved service class names, and public signatures matching `hierarchy`, `level`, `forest`, `generator`, `provenance`, `revision`, `rule`, `audit`, `history`, `preview`, `lock`, and `setting`.

```groovy
List<String> publicReadSignatures(Object service) {
    List<String> tokens = ['hierarchy', 'level', 'forest', 'generator', 'provenance',
                           'revision', 'rule', 'audit', 'history', 'preview', 'lock', 'setting']
    service.class.methods
        .findAll { method -> tokens.any { token -> method.name.toLowerCase(Locale.ROOT).contains(token) } }
        .collect { method -> method.name + '(' + method.parameterTypes*.name.join(',') + '):' + method.returnType.name }
        .unique()
        .sort()
}
```

- [ ] Emit `UNAVAILABLE`, `FAILED`, and `NO_MATCHING_METHODS` distinctly. None may be described as global absence.
- [ ] Add `tools/jira-typecheck.jsh` so `-Dtarget=<path>` prints the target, classpath entry count, and unexpected symbol errors without embedding an instance path.
- [ ] Parse-check the probe and scan it for URLs, hostnames, credentials, work-item keys, JQL, and generator payloads.
- [ ] Document that hierarchy and Automation are read-only even if reflective write signatures exist in installed classes.
- [ ] Commit with `OP-1371 test: add Structure Doctor capability probe`.

## Task 2: Import and freeze the secured legacy endpoint

**Files:** Create `jira/structureIssueDoctor.groovy` and `jira/tests/structureIssueDoctor.tests.groovy`; modify `.github/workflows/ci.yml`.

- [ ] Verify the source contains exactly one GET and one POST declaration and both contain `groups: ["jira-administrators"]`.
- [ ] Add failing source-contract tests for authenticated-user checks, HTML escaping, JSON conversion, Structure ID parsing, the legacy required work-item key, the two admin declarations, and `SET_PARENT_LINK`. Optional focused display is introduced later in Task 10, not during the byte-identical import.

```groovy
check('admin declarations', source.count('groups: ["jira-administrators"]'), 2)
ok('anonymous declaration absent', !source.contains('groups: []'))
ok('legacy confirmation retained', source.contains("FIX_CONFIRMATION = 'SET_PARENT_LINK'"))
```

- [ ] Run the suite and observe the expected missing-source or missing-helper failure.
- [ ] Import the baseline byte-for-byte into `jira/structureIssueDoctor.groovy` using `apply_patch`; do not change behavior in the import commit.
- [ ] Parse-check the imported endpoint and compare its SHA-256 content with the authorized source before any later modularization.
- [ ] Add the offline test command to CI using the existing Groovy 3.0.21 setup.
- [ ] Commit with `OP-1371 feat: import secured Structure Doctor baseline`.

## Task 3: Introduce explicit read and coverage semantics

**Files:** Create `jira/structuredoctor/CoreRead.groovy`, `jira/structuredoctor/CoreModels.groovy`, and `jira/structuredoctor/DoctorContracts.groovy`; modify tests and CI.

- [ ] Create tag `pre-op-1371-structure-doctor-core`.
- [ ] Write failing tests that distinguish a complete empty read from incomplete, failed, and unavailable reads.

```groovy
check('complete empty', ReadResult.complete([]).state, ReadState.COMPLETE)
check('incomplete reason', ReadResult.incomplete([], 'row cap reached').reason, 'row cap reached')
check('failed reason', ReadResult.failed('forest read failed').state, ReadState.FAILED)
check('unavailable capability', ReadResult.unavailable('automation audit').state, ReadState.UNAVAILABLE)
ok('failed is not empty', ReadResult.failed('x').state != ReadResult.complete([]).state)
```

- [ ] Run and observe missing-type failures.
- [ ] Implement immutable `ReadState`, `ReadResult<T>`, `Coverage`, and `EvidenceRequirement` types. Require a non-empty reason for every non-complete state.
- [ ] Implement immutable `HierarchyLevel`, `HierarchySnapshot`, `OccurrenceSnapshot`, `GeneratorSnapshot`, `IssueRelationSnapshot`, `AutomationRuleSnapshot`, `AuditCoverage`, `Finding`, `CausalClaim`, `RepairPackage`, `ImpactResult`, and `RepairOperation` types.
- [ ] Define read-only contracts for hierarchy, Structure snapshot, Jira issue/history, Automation rules, and Automation audit.
- [ ] Define separate mutable contracts only for Structure configuration and allowed Jira issue data. Do not place hierarchy or Automation write methods in any interface.

```groovy
interface HierarchyProvider { ReadResult<HierarchySnapshot> readHierarchy() }
interface AutomationDataProvider { ReadResult<List<AutomationRuleSnapshot>> readRules(); ReadResult<List<AutomationAuditSnapshot>> readAudit(AuditRequest request) }
interface StructureMutationGateway { MutationReceipt applyGeneratorPackage(GeneratorPackage packageValue); MutationReceipt restoreGeneratorPackage(GeneratorPackage packageValue) }
```

- [ ] Make CI compile every `Core*.groovy` plus `DoctorContracts.groovy` without Jira, Structure, JAX-RS, or Automation imports.
- [ ] Make CI fail on new Groovy files over 250 lines.
- [ ] Run the source suite and commit with `OP-1371 refactor: establish Doctor Core contracts`.

## Task 4: Canonicalize snapshots and dependency fingerprints

**Files:** Create `jira/structuredoctor/CoreCanonical.groovy`; add synthetic fixtures `complete-snapshot.json`, `incomplete-forest.json`, and `changed-hierarchy.json`; modify models and tests.

- [ ] Write failing tests proving map order is irrelevant, list order is preserved, sets are sorted, unsupported values are rejected, and incomplete snapshots have no planning fingerprint.

```groovy
check('map order stable', CoreCanonical.sha256([b: 2, a: 1]), CoreCanonical.sha256([a: 1, b: 2]))
ok('generator order matters', CoreCanonical.sha256([generators: [11L, 12L]]) != CoreCanonical.sha256([generators: [12L, 11L]]))
check('incomplete cannot plan', incomplete.planningFingerprint(), null)
```

- [ ] Run and observe the missing canonicalizer failure.
- [ ] Normalize lexical map keys, preserve list order, sort sets canonically, normalize integral values to `Long`, encode UTF-8 JSON, and hash with SHA-256 lowercase hex.
- [ ] Fingerprint Structure identity and revision, ordered generators, ordered rows, physical row IDs, ancestry, provenance completeness, global hierarchy, relevant rules, issue revisions, and audit coverage.
- [ ] Exclude display labels, actor names, timestamps, and explanatory prose from deterministic IDs.
- [ ] Build snapshot-bound IDs for occurrences, findings, causal claims, repair packages, and operations.
- [ ] Run under UTF-8 and US-ASCII and require identical fingerprints.
- [ ] Commit with `OP-1371 feat: fingerprint Doctor snapshots`.

## Task 5: Analyze the global hierarchy and Jira relationships

**Files:** Create `jira/structuredoctor/CoreHierarchyAnalyzer.groovy`; add fixtures for valid hierarchy, missing parent, conflicting parents, invalid level, orphan, wrong path, and incomplete hierarchy; modify models and tests.

- [ ] Write one failing test per finding type and a control showing that duplicate-only facts remain available when the hierarchy read is incomplete.

```groovy
HierarchyAnalysis analysis = new CoreHierarchyAnalyzer().analyze(snapshot)
check('missing parent', analysis.findings*.type, [FindingType.MISSING_PARENT])
check('hierarchy blocker', analysis.findings[0].blockedBy*.source, ['jira-hierarchy'])
```

- [ ] Run and observe the missing analyzer failure.
- [ ] Map issue types to ordered global hierarchy levels dynamically. Do not hard-code SAFe names, issue type names, link names, field names, or project keys.
- [ ] Detect missing native parents, leading-relation conflicts, invalid parent levels, competing parent relationships, hierarchy orphans, and wrong Structure paths.
- [ ] Produce configuration-conflict findings when hierarchy and data disagree, without proposing a hierarchy mutation.
- [ ] Traverse the Structure forest once and use pre-batched lookup maps inside the loop.
- [ ] Prove with a counting iterable over 50,000 rows that the analyzer performs one forest traversal and no gateway call per row.
- [ ] Commit with `OP-1371 feat: analyze Jira hierarchy health`.

## Task 6: Analyze duplicate occurrences and generator overlap

**Files:** Create `jira/structuredoctor/CoreDuplicateAnalyzer.groovy`; add fixtures for two and three occurrences, native hierarchy plus Jira link, two inserters, permanent/generated rows, identical rows, unknown provenance, and filter order; modify tests.

- [ ] Write failing tests showing every duplicate group exposes all physical occurrences and exactly one retain choice per occurrence.

```groovy
DuplicateAnalysis result = new CoreDuplicateAnalyzer().analyze(snapshot)
check('one numeric issue group', result.groups*.issueId, [10001L])
check('three retain choices', result.groups[0].occurrences.size(), 3)
```

- [ ] Run and observe the missing analyzer failure.
- [ ] Group by numeric Jira issue ID while preserving forest order and physical row identity.
- [ ] Evaluate each occurrence against the configured hierarchy and native relation. Recommend hierarchy-valid and native-hierarchy occurrences according to the approved decision matrix, but never silently select one.
- [ ] Explain inserter/extender overlap, multiple paths, permanent/generated mixtures, missing or misplaced duplicates filters, and unknown provenance.
- [ ] Block De-Dupe when no occurrence is hierarchy-valid, when required hierarchy evidence is incomplete, when provenance is unknown, or when impact cannot be complete.
- [ ] Keep semantically identical occurrences distinct with stable ordinals.
- [ ] Commit with `OP-1371 feat: analyze duplicate provenance`.

## Task 7: Normalize and analyze Automation inputs

**Files:** Create `jira/structuredoctor/CoreAutomationAnalyzer.groovy` and `jira/structuredoctor/JsonAutomationProvider.groovy`; add synthetic rule and audit fixtures; modify tests.

- [ ] Write failing parser tests for valid exports, unknown export versions, invalid JSON, oversized input, missing fields, capped audit results, and partial time ranges.
- [ ] Write failing analyzer tests for multiple writers, conflicting source fields, overlapping scope, clear-on-empty, asynchronous races, possible chains, hierarchy-invalid target levels, and rules feeding Structure-consumed links.

```groovy
AutomationAnalysis result = new CoreAutomationAnalyzer().analyze(rules, audit, context)
check('two writers', result.findings.find { it.type == FindingType.MULTIPLE_AUTOMATION_WRITERS }.ruleIds.size(), 2)
check('partial coverage', result.auditCoverage.complete, false)
```

- [ ] Run and observe missing parser/analyzer failures.
- [ ] Parse official export JSON as untrusted data with a strict byte limit and schema allowlists. Never execute, import, publish, or echo a full payload.
- [ ] Normalize enabled state, scope, trigger, ordered components, conditions, fields and links read, fields and links written or cleared, asynchronous behavior, chaining, actor metadata, revision, and matching audit entries.
- [ ] Filter analysis to rules overlapping selected Structure projects, issue types, fields, links, and native relations.
- [ ] Record requested and actual audit intervals plus caps. Missing days reduce evidence and selectively block dependent proposals.
- [ ] Ensure the provider exposes no mutation method and add a source test rejecting Automation write verbs.
- [ ] Commit with `OP-1371 feat: analyze Automation conflicts`.

## Task 8: Build bounded causal graphs and evidence grades

**Files:** Create `jira/structuredoctor/CoreCausalityEngine.groovy`; add fixtures for configuration-only, possible, probable, and confirmed causes; modify tests.

- [ ] Write failing tests for all four evidence grades and prove static rule matching never produces `CONFIRMED_CAUSE`.

```groovy
check('static rule is possible', engine.claim(staticContext).grade, EvidenceGrade.POSSIBLE_CAUSE)
check('matching audit confirms', engine.claim(auditedContext).grade, EvidenceGrade.CONFIRMED_CAUSE)
```

- [ ] Run and observe the missing engine failure.
- [ ] Build only this bounded edge chain: Automation action to Jira data change to Structure generator selection to occurrence/path to finding.
- [ ] Require explicit evidence objects for every edge. Missing edges remain visible as missing evidence and never become inferred facts.
- [ ] Grade claims as configuration conflict, possible cause, probable cause, or confirmed cause according to the approved definitions.
- [ ] Include exact evidence coverage and blockers in every claim while excluding full rule payloads and full JQL.
- [ ] Commit with `OP-1371 feat: grade Structure problem causes`.

## Task 9: Generate repair packages and complete simulations

**Files:** Create `jira/structuredoctor/CoreProposalPlanner.groovy` and `jira/structuredoctor/CoreImpactSimulator.groovy`; add fixtures for competing plans, shared generators, Jira-data changes, incomplete preview, unrelated loss, and new finding regression; modify contracts/models/tests.

- [ ] Write failing tests for optional selection, every duplicate retain choice, atomic multi-group generator packages, Jira-data confirmation, and independent findings left unchanged.
- [ ] Write failing blockers for unknown population, unsupported capability, incomplete preview, retained occurrence loss, new unapproved finding, permanent-row removal without explicit selection, and stale dependency fingerprints.

```groovy
RepairPackage packageValue = planner.plan(snapshot, selection, proposalSource)
check('shared generator is atomic', packageValue.findingGroupIds as Set, ['g1', 'g2'] as Set)
ok('jira data needs extra confirmation', packageValue.confirmations.contains(Confirmation.JIRA_DATA_CHANGE))
```

- [ ] Run and observe missing planner/simulator failures.
- [ ] Allow only live `ProposalSource` adapters to construct exact before/after generator configurations or Jira values. Reject browser-supplied JQL, field values, and generator parameters.
- [ ] Support Structure strategies only when proven: duplicates-filter adjustment, general inserter narrowing, extender restriction, generator ordering, and explicitly selected permanent-row removal.
- [ ] Build one atomic package for one shared generator change and list every affected finding group and issue.
- [ ] Simulate the complete before/after forest as a multiset including physical multiplicity, path, provenance, creator, and stable ordinal.
- [ ] For Jira-data changes, show old/new values, hierarchy reasoning, known selected-Structure impact, Jira-wide warning, and the additional confirmation requirement.
- [ ] Set `safeToApply=true` only when all required evidence is complete, the retained row survives, unselected independent findings are unchanged, and no new unapproved finding appears.
- [ ] Commit with `OP-1371 feat: simulate Doctor repair packages`.

## Task 10: Deliver Analyze and Plan without a writer

**Files:** Create `jira/structuredoctor/LiveConfigurationDiscovery.groovy`, `LiveStructureGateway.groovy`, `LiveAutomationProvider.groovy`, `LiveJiraGateway.groovy`, `LegacyIssueDoctor.groovy`, `DoctorApplication.groovy`, and `DoctorRenderer.groovy`; modify controller, README, tests, and CI.

- [ ] Write failing fake-gateway tests for page load without analysis, Structure selection, explicit Analyze, no required issue key, optional display filter, 30-day audit default, JSON fallback provider, and failed source coverage.
- [ ] Write failing Plan tests for selected finding IDs, one retain occurrence per selected duplicate group, unknown identifiers, stale snapshots, extra keys, and browser configuration injection.
- [ ] Run and observe missing application/renderer failures.
- [ ] Implement page load as Structure picker only. Do not run a complete scan until explicit Analyze.
- [ ] Read the hierarchy backing `PortfolioHierarchy.jspa` through a proven service, never by page scraping. Return `UNAVAILABLE` if the installed API is not proven.
- [ ] Read the complete forest, generators, provenance, revision, issues, native relations, relevant history, rules, and audit data in bounded batches.
- [ ] Move legacy issue diagnosis and Parent Link logic behind `LegacyIssueDoctor` without changing its confirmation behavior.
- [ ] Render source coverage, findings, paths, provenance, Jira data, causal claims, grades, missing evidence, blockers, optional selection, retain choices, repair explanations, and warnings.
- [ ] Escape every customer-derived string. Browser JavaScript submits identifiers, requested audit days, upload references, and confirmation constants only.
- [ ] Register Analyze and Plan as POST endpoints with `groups: ["jira-administrators"]` and explicit missing-user rejection.
- [ ] Keep Apply disabled and clearly labeled until Task 11 capabilities are proven.
- [ ] Run source, HTTP, parse, line-count, credential, URL, and outbound-network gates.
- [ ] Commit with `OP-1371 feat: deliver Structure-wide analysis and planning`.

## Task 11: Add the capability-gated transactional repair boundary

**Files:** Create `jira/structuredoctor/CoreRepairCoordinator.groovy`, `LiveRepairInfrastructure.groovy`, and `tools/structure-doctor-mutation-probe.groovy`; modify application, controller, renderer, models, contracts, and tests.

- [ ] Write failing coordinator tests for first apply, exact replay, conflicting replay, stale hierarchy/rule/issue/Structure/generator fingerprints, same-Structure contention, affected-issue contention, pending block, partial mutation, verified result, proven mismatch, rollback, and manual recovery.
- [ ] Test only these state transitions:

```text
ANALYZED -> PLANNED -> CONFIRMED -> APPLIED -> VERIFYING
VERIFYING -> VERIFIED | PENDING | ROLLED_BACK | MANUAL_RECOVERY_REQUIRED
PENDING -> VERIFYING
```

- [ ] Run and observe the missing coordinator failure.
- [ ] Implement `DisabledRepairInfrastructure` as the default. It returns `409 APPLY_UNAVAILABLE` and names only missing capability classes.
- [ ] Add a separate mutation probe requiring an authorized disposable Structure, explicit confirmation, and expected fingerprint. It snapshots, performs the smallest reversible proven mutation, waits for a new revision, restores in `finally`, waits again, and compares the restored target state.
- [ ] Enable a live writer only after preview, revision, mutation, exact restore, cluster lock, cluster-visible journal, recalculation completion, and measured verification all pass.
- [ ] Acquire one Structure lock for generator packages and affected-issue compare-and-set protection for Jira-data packages.
- [ ] Persist bounded before/after state before mutation. Never persist credentials, full rule payloads, full JQL, or unnecessary work-item content.
- [ ] Rebuild every package server-side under the lock, compare fingerprints and package IDs, simulate again, then mutate at most once.
- [ ] Require `CONFIRM_STRUCTURE_CHANGE` for Structure packages and both `CONFIRM_STRUCTURE_CHANGE` plus `CONFIRM_JIRA_DATA_CHANGE` when a package changes Jira data and Structure impact is included.
- [ ] Verify the recalculated forest and actual Jira values. Timeout becomes `PENDING`; proven mismatch triggers exact restore; unverifiable restore becomes `MANUAL_RECOVERY_REQUIRED`.
- [ ] Keep global hierarchy and Automation providers outside the mutable dependency graph and add source tests proving no write path exists.
- [ ] Register Apply and Status endpoints with admin groups and explicit authentication/permission checks.
- [ ] Commit with `OP-1371 feat: add transactional Doctor repairs`.

## Task 12: Enforce HTTP, authorization, and upload boundaries

**Files:** Create or complete `jira/tests/structureIssueDoctor.http.tests.groovy`; modify controller, renderer, application, CI, and README.

- [ ] Write failing tests for anonymous and non-admin callers on every endpoint, invisible Structures, missing Structure write permission, missing issue permission, invalid methods, wrong content type, oversized uploads, invalid JSON, unknown keys, nested executable configuration, forged identifiers, and operation replay.
- [ ] Run and observe each intended failure before changing production code.
- [ ] Require `application/json` for Analyze, Plan, and Apply, except separately bounded multipart export upload handling.
- [ ] Return `400`, `401`, `403`, `404`, `409`, and `202` according to the design. Never turn an invisible object into a global absence claim.
- [ ] Add CI guards for exactly one admin restriction per Doctor declaration, explicit authenticated-user checks, no mutation GET, no empty groups, no hierarchy/Automation write verbs, no product imports in core, no full JQL logging, and no browser configuration keys.
- [ ] Render JSON only through `JsonOutput` and HTML only through the central escaping helper.
- [ ] Run all offline, HTTP, parse, hygiene, and line-count checks.
- [ ] Commit with `OP-1371 test: enforce Doctor security boundaries`.

## Task 13: Verify against installed APIs and a disposable Structure

**Files:** Create `jira/tests/structureIssueDoctor.integration.groovy`; modify README and CI only when a real runner is observed; record evidence in OP-1371.

- [ ] Run the read-only capability probe on the authorized Jira test instance and record sanitized `PASS`, `FAIL`, or `UNTESTABLE` results for hierarchy, Structure, Automation, audit, Jira history, preview, revision, lock, journal, mutation, restore, and verification.
- [ ] Compile and typecheck against the actual installed Jira, ScriptRunner, Advanced Roadmaps, Structure, and Automation JARs.
- [ ] Do not invent a GitHub runner label or upload proprietary product JARs. If no approved runner owns them, mark real-classpath CI `BLOCKED`.
- [ ] Require integration inputs for a disposable Structure identity, synthetic work-item identities, expected initial fingerprint, and explicit run confirmation.
- [ ] Snapshot exact initial Structure configuration, forest, and Jira values and restore them in `finally` with independent readback.
- [ ] Exercise one duplicate, one missing/conflicting-parent finding, one Automation conflict, both duplicate retain choices, one atomic generator package, and one Jira-data repair with the extra confirmation.
- [ ] Prove stale hierarchy, rule, issue, and generator changes return `409` without mutation.
- [ ] Prove one operation mutates once, pending verification resumes, mismatch rolls back, and exact target state is restored.
- [ ] Verify ScriptRunner displays no `Anonymous allowed` label for any Doctor endpoint.
- [ ] Record commands, assertion counts, versions, sanitized fingerprints, state transitions, restoration, and installed artifact identity in OP-1371.
- [ ] Commit with `OP-1371 test: verify Structure Doctor integration`.

## Task 14: Simplify, independently review, and close only on evidence

**Files:** Only files changed by OP-1371.

- [ ] Run the code-simplifier gate on OP-1371 production files only. Accept behavior-preserving reductions and no unrelated refactor.
- [ ] Re-run all offline, HTTP, security, parse, hygiene, line-count, real-classpath, capability, and authorized integration checks.
- [ ] Inspect final scope and history:

```powershell
git status --short
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
git log --format='%s' origin/main..HEAD
rg -n "Anonymous allowed|groups: \[\]" jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests README.md
```

- [ ] Confirm every commit starts `OP-1371 `, no unrelated file changed, no source exceeds 250 lines, and no credential, private hostname, customer key, full JQL, or rule payload entered the diff.
- [ ] Obtain independent read-only review of authorization, read-failure semantics, hierarchy/Automation immutability, stale binding, atomicity, idempotency, confirmation, verification, rollback, and manual recovery.
- [ ] Fix every accepted behavior finding with a failing regression test first.
- [ ] Post the final C1 requested behavior, C2 verification, C3 security/privacy, and C4 scope-integrity receipt to OP-1371.
- [ ] Mark OP-1371 Done only when every acceptance criterion and installed target identity is measured. Otherwise leave it In Progress with precise blockers.

## Acceptance trace

| Approved criterion | Implemented and proven by |
| --- | --- |
| Explicit analysis of selected Structure without issue key | Tasks 2, 10, 12 |
| No scan on page load or mere selection | Tasks 10, 12 |
| Dynamic, immutable global hierarchy | Tasks 1, 3, 5, 10, 11 |
| Duplicate and general hierarchy findings | Tasks 5, 6 |
| Paths, Jira data, provenance, causes, coverage, grade | Tasks 5, 6, 8, 10 |
| Automation rules and audit correlation in Core | Tasks 7, 8, 10 |
| Live and JSON Automation providers | Tasks 1, 7, 10, 13 |
| No Automation mutation path | Tasks 3, 7, 11, 12 |
| Explicit duplicate retention | Tasks 6, 9, 10 |
| Atomic shared-generator packages | Tasks 9, 11, 13 |
| Unknown evidence or impact blocks dependent repair | Tasks 3, 5, 6, 7, 9 |
| Structure confirmation and Jira-data second confirmation | Tasks 9, 11, 12 |
| Unselected work remains unchanged | Tasks 9, 11, 13 |
| Fresh, locked, idempotent, journaled, verifiable repair | Tasks 4, 9, 11, 13 |
| Actual target state proves success | Tasks 11, 13, 14 |
| Jira-administrators only, no anonymous access | Tasks 2, 10, 12, 13 |
| Offline, HTTP, capability, integration, parse, hygiene, classpath | Tasks 1 through 14 |
