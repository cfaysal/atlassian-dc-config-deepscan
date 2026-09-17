# Structure Doctor De-Dupe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an administrator-only Structure Doctor that analyzes a selected Structure without requiring a work-item key, explains every duplicate occurrence, offers opt-in and fully simulated repair proposals, and applies only fresh, supported, journaled, idempotent, verifiable, and recoverable generator changes.

**Architecture:** Keep `jira/structureIssueDoctor.groovy` as a thin ScriptRunner REST controller. Put Jira-free immutable models, duplicate analysis, proposal validation, impact verification, and the repair state machine in `structuredoctor` package classes compiled offline by CI. Isolate Jira and Structure calls behind live adapters. A measured capability report decides whether mutation, cluster locking, cluster-visible journaling, preview, and recalculation verification are enabled; an unproven capability produces a blocked proposal, never a guessed API call.

**Tech Stack:** Groovy 3.0.21, ScriptRunner custom REST endpoints, Jira Data Center APIs, Tempo Structure APIs, canonical JSON plus SHA-256, synthetic JSON fixtures, GitHub Actions, and real-instance classpath verification.

---

## Evidence, scope, and completion rules

- Work item `OP-1371` is In Progress; approved design is `docs/superpowers/specs/2026-09-17-structure-doctor-dedupe-design.md`.
- Authorized baseline is `C:\Users\CFaysal\Qsync\Documents\Coopers\CSS\structure_issue_doctor.groovy`.
- Work on branch `op-1371-structure-doctor-dedupe`; every commit begins `OP-1371 `, with no colon after the key.
- Tag `pre-op-1371-structure-doctor-modularization` before splitting the imported endpoint.
- Preserve the existing issue diagnosis and Parent Link repair exactly while adding De-Dupe.
- Keep each new source file below 250 lines; record a measured ScriptRunner exception in OP-1371 before exceeding it.
- Use only synthetic fixture data. Never log credentials, full generator payloads, full JQL, private hostnames, or customer data.
- Do not deploy, install, or mutate any Jira/Structure instance without separate authority for that instance.
- A write response is not completion. The recalculated target forest and installed artifact identity are the final evidence.

## Target file map

| Path | Responsibility |
| --- | --- |
| `jira/structureIssueDoctor.groovy` | Endpoint declarations and dependency assembly only |
| `jira/structuredoctor/CoreModels.groovy` | Immutable read/snapshot/group/proposal/impact/operation types |
| `jira/structuredoctor/CoreCanonical.groovy` | Canonical values, fingerprints, deterministic IDs |
| `jira/structuredoctor/CoreDuplicateAnalyzer.groovy` | Duplicate grouping and provenance blockers |
| `jira/structuredoctor/CoreProposalPlanner.groovy` | Candidate validation, ranking, descriptions |
| `jira/structuredoctor/CoreImpactVerifier.groovy` | Full forest multiset delta and invariants |
| `jira/structuredoctor/CoreRepairCoordinator.groovy` | Idempotent lifecycle, verification, rollback decisions |
| `jira/structuredoctor/DoctorContracts.groovy` | Jira-free gateway, journal, lock, clock interfaces |
| `jira/structuredoctor/LiveStructureReader.groovy` | Forest/generator/provenance/permission/revision reads |
| `jira/structuredoctor/LiveProposalSource.groovy` | Proven mutation candidates and non-mutating previews |
| `jira/structuredoctor/LiveRepairInfrastructure.groovy` | Proven lock, journal, mutation, restore, revision adapter |
| `jira/structuredoctor/LegacyIssueDoctor.groovy` | Existing issue and Parent Link behavior |
| `jira/structuredoctor/DoctorApplication.groovy` | Analyze, Plan, Apply, Status orchestration |
| `jira/structuredoctor/DoctorRenderer.groovy` | Escaped HTML/JSON and identifier-only requests |
| `jira/tests/structureIssueDoctor.tests.groovy` | Jira-free executable suite |
| `jira/tests/structureIssueDoctor.http.tests.groovy` | HTTP/security suite with fakes |
| `jira/tests/structureIssueDoctor.integration.groovy` | Disposable live Structure test and restoration |
| `jira/tests/fixtures/structuredoctor/*.json` | Synthetic forest scenarios |
| `tools/structure-doctor-capability-probe.groovy` | Read-only installed API report |
| `tools/structure-doctor-mutation-probe.groovy` | Confirmed disposable mutation/restore proof |
| `tools/jira-typecheck.jsh` | Actual Jira/Structure classpath typecheck |
| `.github/workflows/ci.yml` | Parse, source-suite, security, hygiene, classpath gates |
| `README.md` and `CONTRIBUTING.md` | Bundle installation and development contract |

The `Core*.groovy` prefix is the offline boundary. CI compiles exactly those files plus `DoctorContracts.groovy` and fails if they import Jira, Structure, or JAX-RS classes.

## Task 1: Prove installed capabilities before designing a writer

**Files:** Create `tools/structure-doctor-capability-probe.groovy`, `tools/structure-doctor-mutation-probe.groovy`, `tools/jira-typecheck.jsh`; modify `README.md`; record the sanitized receipt in OP-1371.

- [ ] Add a read-only probe that prints product versions, concrete service class names, and public signatures matching `forest`, `generator`, `preview`, `revision`, `update`, `save`, `lock`, `transaction`, and `setting`.

```groovy
@WithPlugin('com.almworks.jira.structure')
@PluginModule StructureComponents structureComponents

List<String> signatures(Object service) {
    List<String> tokens = ['forest', 'generator', 'preview', 'revision',
                           'update', 'save', 'lock', 'transaction', 'setting']
    service.class.methods
        .findAll { m -> tokens.any { t -> m.name.toLowerCase(Locale.ROOT).contains(t) } }
        .collect { m -> m.name + '(' + m.parameterTypes*.name.join(',') + '):' + m.returnType.name }
        .unique()
        .sort()
}
```

- [ ] Report unresolved service reads as `UNAVAILABLE` and an empty signature match as `NO_MATCHING_METHODS`. Neither is proof of global absence.
- [ ] Parse-check the probe and scan it for credentials, URLs, hostnames, work-item keys, JQL, and generator payloads.

```powershell
java -Dfile.encoding=UTF-8 -cp "$env:GROOVY_CP" groovy.ui.GroovyMain tools/parsecheck.groovy tools/structure-doctor-capability-probe.groovy
rg -n "https?://|192\.168\.|[A-Z][A-Z0-9]+-[0-9]+|password|api[_-]?key" tools/structure-doctor-capability-probe.groovy
```

- [ ] Add a mutation probe requiring a disposable Structure ID, `confirm=PROBE_DISPOSABLE_STRUCTURE`, and the expected current fingerprint. It snapshots, performs the smallest proven reversible generator change, waits for a proven new revision, restores the exact before-image in `finally`, waits again, and compares the restored forest.
- [ ] Refuse the mutation probe if permanent-row removal, arbitrary JQL input, a revision signal, exact restore, or full preview cannot be proven.
- [ ] With separate test-instance authority, prove generator mutation/restore, non-mutating preview, recalculation completion, one Structure-scoped cluster lock, and bounded cluster-visible persistence across node A and node B. Record each as `PASS`, `FAIL`, or `UNTESTABLE` in OP-1371 without private identifiers.
- [ ] Add `tools/jira-typecheck.jsh` by adapting the existing checker to the verified Jira paths and plugin cache. It accepts `-Dtarget=jira/structureIssueDoctor.groovy`, prints classpath count and target, compiles through `INSTRUCTION_SELECTION`, and exits non-zero on unexpected errors.
- [ ] Read the actual GitHub runner inventory. Do not invent a label or upload proprietary product JARs.

```powershell
gh api repos/cfaysal/atlassian-dc-config-deepscan/actions/runners --jq '.runners[] | {name,labels:[.labels[].name],status}'
```

- [ ] If an approved runner with actual instance JARs is absent, record real-classpath CI as `BLOCKED` and do not claim acceptance criterion 10.
- [ ] Document only measured supported versions/capabilities. Missing preview, mutation, revision, lock, journal, or restore keeps Analyze enabled and Apply disabled.
- [ ] Commit.

```powershell
git add tools/structure-doctor-capability-probe.groovy tools/structure-doctor-mutation-probe.groovy tools/jira-typecheck.jsh README.md
git commit -m "OP-1371 test: probe Structure repair capabilities"
```

**Hard checkpoint:** Tasks 10 and 11 may implement a live writer only if all six write capabilities pass. Otherwise they implement `DisabledRepairInfrastructure` and the product remains read-only.

## Task 2: Import and freeze the secured baseline

**Files:** Create `jira/structureIssueDoctor.groovy` and `jira/tests/structureIssueDoctor.tests.groovy`; modify `.github/workflows/ci.yml`.

- [ ] Verify the source has exactly the `GET structureIssueDoctor` and legacy `POST structureIssueDoctorFix` declarations, both with `groups: ["jira-administrators"]`.

```powershell
rg -n "^structureIssueDoctor(Fix)?\(httpMethod:|groups: \[\"jira-administrators\"\]" "C:\Users\CFaysal\Qsync\Documents\Coopers\CSS\structure_issue_doctor.groovy"
```

- [ ] Add the exact source via `apply_patch`. Change no behavior, names, strings, or Parent Link logic during import.
- [ ] Add the repository's standard `check`/`ok` harness. Write failing tests for HTML escaping, JSON-safe conversion, Structure ID parsing, optional work-item key, both admin declarations, and `SET_PARENT_LINK`.
- [ ] Run the suite and capture the expected failure because the Jira-free helpers have not yet been extracted.
- [ ] Parse-check the imported endpoint and inspect the diff against the authorized source; only the destination path may differ.
- [ ] Commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/tests/structureIssueDoctor.tests.groovy .github/workflows/ci.yml
git commit -m "OP-1371 feat: import secured Structure Doctor baseline"
```

## Task 3: Establish the modular package and source-suite gate

**Files:** Create `jira/structuredoctor/CoreModels.groovy` and `DoctorContracts.groovy`; modify endpoint, test suite, CI, and `CONTRIBUTING.md`.

- [ ] Create the safety tag.

```powershell
git tag pre-op-1371-structure-doctor-modularization
```

- [ ] Add failing tests distinguishing complete empty, incomplete, and failed reads.

```groovy
ReadResult empty = ReadResult.complete([])
ReadResult failed = ReadResult.failed('forest read failed')
ReadResult incomplete = ReadResult.incomplete([], 'row cap reached')
check('empty complete', empty.state, ReadState.COMPLETE)
check('failed reason', failed.reason, 'forest read failed')
check('incomplete not complete', incomplete.complete(), false)
ok('failed differs from empty', failed.state != empty.state)
```

- [ ] Run and observe missing `ReadResult`/`ReadState` compilation errors.
- [ ] Implement immutable `ReadResult`, `OccurrenceSnapshot`, `GeneratorSnapshot`, `StructureSnapshot`, `DuplicateGroup`, `MutationCandidate`, `FixProposal`, `ForestDelta`, `ImpactResult`, and `RepairOperation`. Preserve both physical row ID and semantic selector.
- [ ] Add Jira-free `StructureGateway`, `ProposalSource`, `RepairJournal`, `StructureLock`, and `DoctorClock` contracts.
- [ ] Extract text, input, JSON, and response-independent helpers. Keep endpoint declarations in the entry file.
- [ ] Add a CI `source-suite` mode:

```bash
rm -rf /tmp/doctor-classes
mkdir -p /tmp/doctor-classes
mapfile -t SOURCES < <(find jira/structuredoctor -maxdepth 1 -name 'Core*.groovy' -print | sort)
SOURCES+=(jira/structuredoctor/DoctorContracts.groovy)
test "${#SOURCES[@]}" -gt 1
java -cp "$GROOVY_CP" org.codehaus.groovy.tools.FileSystemCompiler -d /tmp/doctor-classes "${SOURCES[@]}"
java -cp "$GROOVY_CP:/tmp/doctor-classes" groovy.ui.GroovyMain jira/tests/structureIssueDoctor.tests.groovy
```

- [ ] Make CI reject product/JAX-RS imports in core files and new source files above 250 lines.
- [ ] Update `CONTRIBUTING.md`: existing configuration reports remain single files; Structure Doctor is an atomic controller-plus-package bundle copied to every Jira node.
- [ ] Run suite and parse check; require `ALL TESTS PASSED` and `PARSE OK`.
- [ ] Commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests/structureIssueDoctor.tests.groovy .github/workflows/ci.yml CONTRIBUTING.md
git commit -m "OP-1371 refactor: isolate Structure Doctor core"
```

## Task 4: Canonical snapshots and fingerprints

**Files:** Create `CoreCanonical.groovy`, fixtures `two-occurrences.json` and `incomplete-forest.json`; modify models/tests.

- [ ] Add a complete synthetic fixture with ordered generators, rows, ancestry, provenance, parameters, and revision; add an explicitly incomplete fixture.
- [ ] Write failing tests proving map order is irrelevant, generator order and ancestry are relevant, and incomplete snapshots have no planning fingerprint.

```groovy
check('map order canonical', CoreCanonical.sha256([b: 2, a: 1]),
      CoreCanonical.sha256([a: 1, b: 2]))
ok('generator order matters',
   CoreCanonical.sha256([generators: [11L, 12L]]) !=
   CoreCanonical.sha256([generators: [12L, 11L]]))
check('incomplete cannot plan', incomplete.planningFingerprint(), null)
```

- [ ] Implement recursive normalization: lexical map keys, preserved list order, canonically sorted sets, integral numbers as `Long`, exact strings, rejection of unsupported values.
- [ ] Hash UTF-8 canonical JSON with SHA-256 lowercase hex.
- [ ] Include Structure ID/revision, ordered generator configuration, ordered rows, item IDs, parent IDs, creator IDs, and provenance completeness. Exclude labels, actor, display text, and timestamps.
- [ ] Build snapshot-bound occurrence IDs from physical identity and separate semantic selectors from issue ID, full parent path, provenance, creator, and stable ordinal.
- [ ] Run the suite under UTF-8 and US-ASCII; fingerprints must match.
- [ ] Commit.

```powershell
git add jira/structuredoctor/CoreCanonical.groovy jira/structuredoctor/CoreModels.groovy jira/tests
git commit -m "OP-1371 feat: fingerprint Structure snapshots"
```

## Task 5: Analyze every duplicate group and its provenance

**Files:** Create `CoreDuplicateAnalyzer.groovy` and fixtures for three occurrences, inserter/extender overlap, permanent/generated rows, unknown provenance, and misplaced duplicates filters; modify models/tests.

- [ ] Add failing tests for two/three occurrences, same/different parents, inserter plus extender, two inserters, permanent/generated mixtures, unknown provenance, and missing/misplaced duplicate filters.
- [ ] Prove grouping uses numeric Jira issue ID and one issue with three rows becomes one group with three keep choices.

```groovy
DuplicateAnalysis result = new CoreDuplicateAnalyzer().analyze(snapshot)
check('one group', result.groups.size(), 1)
check('three choices', result.groups[0].occurrences.size(), 3)
check('numeric identity', result.groups[0].issueId, 10001L)
```

- [ ] Run and observe the missing analyzer failure.
- [ ] Implement one linear occurrence pass using `LinkedHashMap<Long,List<OccurrenceSnapshot>>` in forest order. Perform no Jira or Structure lookup inside the loop.
- [ ] Store each row ID, selector, full path, immediate parent, depth/position, provenance, creator/type, bounded relevant parameters, and plain-language cause.
- [ ] Explain inserter/extender overlap, root/child overlap, multiple paths/creators, mixed permanence, missing/misplaced filter, and unresolved provenance.
- [ ] Block the group when snapshot, relevant generator, or provenance is incomplete; keep read failure distinct from measured absence.
- [ ] Generate group ID from Structure ID, issue ID, and sorted snapshot-bound occurrence IDs.
- [ ] Block semantically identical occurrences unless Task 1 proves a physical-row selector.
- [ ] Use a counting iterable over 50,000 rows to assert one traversal.
- [ ] Run and commit.

```powershell
git add jira/structuredoctor/CoreDuplicateAnalyzer.groovy jira/structuredoctor/CoreModels.groovy jira/tests
git commit -m "OP-1371 feat: analyze Structure duplicate groups"
```

## Task 6: Generate ranked, individually explained proposals

**Files:** Create `CoreProposalPlanner.groovy` plus `competing-plans.json` and `generator-conflict.json`; modify models/tests.

- [ ] Add failing tests that retain each occurrence in turn and require different expected removals and descriptions.
- [ ] Test strategy order: duplicate filter, narrower JQL inserter, restricted extender, generator reorder.
- [ ] Test blockers: both permanent, permanent must disappear, unsupported API, unknown population, retained row disappears.
- [ ] Run and observe the missing planner failure.
- [ ] Permit only `ProposalSource` to create mutation candidates. Browser and planner never construct generator parameters.
- [ ] Require exact before/after configs, non-empty generator IDs, proven capability, rollback support, and complete preview before ranking.
- [ ] Rank by affected population, unrelated effects, semantic JQL change, hierarchy preservation, recurrence prevention, then stable strategy/generator order.
- [ ] Build one six-part description per proposal: configuration change, retained occurrence, why others disappear, changed generators, other affected work items, exact rollback.
- [ ] Derive proposal ID from group, keep selector, strategy, before/after config fingerprints, impact fingerprint, and snapshot fingerprint. Exclude wording and labels.
- [ ] Prove any server-side plan change alters the ID while wording-only changes do not.
- [ ] Prove unchecked groups yield no proposal and exactly one keep selection is required.
- [ ] Run and commit.

```powershell
git add jira/structuredoctor/CoreProposalPlanner.groovy jira/structuredoctor/CoreModels.groovy jira/tests
git commit -m "OP-1371 feat: plan explained duplicate repairs"
```

## Task 7: Verify the complete before/after forest

**Files:** Create `CoreImpactVerifier.groovy` plus fixtures `new-duplicate-regression.json` and `unrelated-loss-regression.json`; modify models/tests.

- [ ] Write failing multiset tests, including two semantically identical rows so multiplicity loss is observable.
- [ ] Require retained selector present, expected duplicates absent, unselected groups unchanged, no new duplicate group, and all unrelated moves/removals listed.
- [ ] Block incomplete preview, wrong revision/baseline, capped search, or unresolved row.
- [ ] Run and observe the missing verifier failure.
- [ ] Implement multiset keys from issue ID, full parent path, provenance, creator, and identical-row ordinal; compute added, removed, moved, unchanged counts.
- [ ] Return the complete affected population and exact row counts. Pagination may shorten only rendering, never the machine result.
- [ ] Set `safeToApply=true` only for a complete preview satisfying every invariant.
- [ ] Add a naive unique-issue control and prove it misses the identical-row regression that the shipped verifier catches.
- [ ] Run and commit.

```powershell
git add jira/structuredoctor/CoreImpactVerifier.groovy jira/structuredoctor/CoreModels.groovy jira/tests
git commit -m "OP-1371 feat: verify full Structure repair impact"
```

## Task 8: Deliver read-only Structure-wide analysis

**Files:** Create `LiveStructureReader.groovy`, `LegacyIssueDoctor.groovy`, `DoctorApplication.groovy`, `DoctorRenderer.groovy`; modify endpoint, tests, README.

- [ ] Add failing fake-gateway tests for picker, required Structure, optional key, De-Dupe off/on, HTML/JSON, and failed forest read. Failure must not render as zero rows.
- [ ] Run and observe missing application/renderer failures.
- [ ] Implement the live reader only with Task 1 signatures: one forest traversal, physical order retained, IDs collected, supporting reads batched afterward.
- [ ] Map generator order/type/enabled/parameters, ancestry, full paths, creator/provenance, permissions, and revision. Any unproven/capped read returns `INCOMPLETE`.
- [ ] Check Structure read visibility for Analyze and write permission separately for Apply.
- [ ] Move existing work-item diagnosis and Parent Link validation/update into `LegacyIssueDoctor` without behavior or confirmation changes.
- [ ] Analyze the complete Structure when no issue key is supplied. A key filters display after full snapshot construction, never planning scope.
- [ ] Render totals, opt-in group checkbox, exactly-one keep radios, paths, provenance, generator/JQL/extender evidence, explanations, blockers, and explicit Structure-change warning.
- [ ] Escape every customer value. Browser JavaScript submits identifiers and confirmation constants only, never JQL, paths, descriptions, or configs.
- [ ] Keep endpoint file to dependency assembly, declarations, dispatch, and response conversion.
- [ ] Run parse, offline, line-count, ASCII, credential, and outbound-network gates.
- [ ] Commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests/structureIssueDoctor.tests.groovy README.md
git commit -m "OP-1371 feat: analyze selected Structure duplicates"
```

## Task 9: Add the server-generated Plan endpoint

**Files:** Create `LiveProposalSource.groovy`; modify application, renderer, endpoint, and HTTP tests.

- [ ] Add failing `POST structureIssueDoctorPlan` tests for valid IDs, missing/wrong fields, unknown group/row, invalid JSON, and extra `jql`/`generatorParameters` keys.
- [ ] Require unknown keys to return `400` even if ignored by code.
- [ ] Prove Plan rebuilds the snapshot and rejects stale group/occurrence IDs.
- [ ] Implement strict allowlist decoding for `structureId`, `duplicateGroupId`, `keepOccurrenceId`; reject nested data, floats, negative/overlong/invalid identifiers.
- [ ] Build candidates only for Task 1-proven strategies; return explicit unsupported blockers for the rest.
- [ ] Obtain the complete non-mutating after-forest preview from the proven API. No full preview means no enabled proposal.
- [ ] Return deterministic ID, individual description, full impact, rollback description, and current snapshot fingerprint.
- [ ] Register with `groups: ["jira-administrators"]` and reject a missing authenticated user with `401`.
- [ ] Run HTTP/offline/parse tests and commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests/structureIssueDoctor.http.tests.groovy
git commit -m "OP-1371 feat: preview server-generated repairs"
```

## Task 10: Journal, lock, and idempotent state machine

**Files:** Create `CoreRepairCoordinator.groovy` and `LiveRepairInfrastructure.groovy`; modify contracts/models/tests.

- [ ] Add fake cluster journal/lock. Test first apply, exact replay, conflicting replay, same-Structure contention, other-Structure independence, pending block, and manual-recovery block.
- [ ] Test only these transitions: `ANALYZED -> PLANNED -> APPLIED -> VERIFYING`; `VERIFYING -> VERIFIED|PENDING|ROLLED_BACK|MANUAL_RECOVERY_REQUIRED`; and `PENDING -> VERIFYING` when Status resumes a measured recalculation check.
- [ ] Run and observe missing coordinator failures.
- [ ] Lock key is `structure-doctor:<structureId>`; operation ID is canonical lowercase UUID.
- [ ] Exact replay must match actor, Structure, proposal, and fingerprint; otherwise `409 OPERATION_ID_CONFLICT`.
- [ ] Persist before mutation: before/after configs, impact fingerprint, actor key, IDs, timestamps, state history. Never include credentials or work-item content.
- [ ] Implement `DisabledRepairInfrastructure` as default when any Task 1 proof is missing; return `409 APPLY_UNAVAILABLE` with capability names.
- [ ] Implement live adapters only for proven APIs, with namespaced bounded journal records and versioned cluster-visible writes.
- [ ] Prove node-switch reads and `finally` lock release on every exception.
- [ ] Run and commit.

```powershell
git add jira/structuredoctor/CoreRepairCoordinator.groovy jira/structuredoctor/LiveRepairInfrastructure.groovy jira/structuredoctor/CoreModels.groovy jira/structuredoctor/DoctorContracts.groovy jira/tests/structureIssueDoctor.tests.groovy
git commit -m "OP-1371 feat: journal and serialize Structure repairs"
```

## Task 11: Apply, resume, verify, and roll back

**Files:** Modify coordinator, repair infrastructure, application, renderer, endpoint, offline and HTTP suites.

- [ ] Add failing tests for stale fingerprint, forged proposal, changed generator config, failed pre-write journal, partial mutation, delayed calculation, proven mismatch, successful/unverifiable rollback, successful verification.
- [ ] Assert timeout becomes `PENDING` and one operation mutates at most once across retries/status polls.
- [ ] Implement Apply under the Structure lock in this order: authenticate, admin check, write permission, reserve/read operation, pending block, rebuild snapshot, compare fingerprint, rebuild proposal, compare ID, preview again, verify invariants, persist before-image, mutate, persist `APPLIED`, read calculation state.
- [ ] Apply only rebuilt server configuration. The strict decoder has already rejected unknown input.
- [ ] If no newer calculation is proven, persist `PENDING` and return `202` with status URL. Never hold a JVM lock between requests.
- [ ] Status reacquires the lock, reloads journal, and resumes verification/rollback only. Pending/manual operations block new repairs.
- [ ] A newer complete forest matching preview becomes `VERIFIED`.
- [ ] A proven mismatch restores every before-image, waits for another proven calculation, and compares with original.
- [ ] Use `ROLLED_BACK` only after measured exact restoration; otherwise `MANUAL_RECOVERY_REQUIRED` and keep the Structure blocked.
- [ ] Register `POST structureIssueDoctorApply` and `GET structureIssueDoctorStatus` with admin groups and missing-user checks.
- [ ] Require `APPLY_STRUCTURE_CHANGE`, JSON, UUID, proposal ID, Structure ID, and fingerprint; return designed `400/401/403/404/409/202` responses.
- [ ] Run all tests and commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests
git commit -m "OP-1371 feat: apply and verify Structure repairs"
```

## Task 12: Enforce authentication, authorization, and browser boundaries

**Files:** Modify HTTP tests, CI, endpoint, application, renderer.

- [ ] Add failing tests for anonymous/non-admin calls to all five endpoints, missing read/write permission, forged/stale plan, invalid/extra JSON, and operation replay.
- [ ] Assert no declaration/page says `Anonymous allowed` and every endpoint declaration has the admin group.
- [ ] Keep explicit user checks in code; Structure read permission for Analyze/Plan/Status; write permission for Apply/rollback. Invisible Structures return `404`, not proof of absence.
- [ ] Require POST plus `application/json` for Plan/Apply and reject browser-supplied configuration fields.
- [ ] Escape every label/key/path/JQL fragment/error/recovery value and JSON-encode with `JsonOutput`.
- [ ] Add CI gates counting endpoint/admin declarations, rejecting mutation GET, product imports in core, full JQL/payload logs, and browser config keys.
- [ ] Run and commit.

```powershell
git add jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests/structureIssueDoctor.http.tests.groovy .github/workflows/ci.yml
git commit -m "OP-1371 test: enforce Structure Doctor security"
```

## Task 13: Prove the workflow on a disposable Structure

**Files:** Create `jira/tests/structureIssueDoctor.integration.groovy`; modify README and, only if proven, CI; record OP-1371 receipt.

- [ ] Require `RUN_DISPOSABLE_DEDUPE_TEST`, Structure ID/name, synthetic work-item IDs, and initial fingerprint.
- [ ] Snapshot exact forest/config before setup and restore in `finally` with measured verification.
- [ ] Create one duplicate through two proven generator paths, analyze without issue key, and assert one group.
- [ ] Plan both keep choices and assert the selected preview row remains while the other disappears.
- [ ] Apply one proposal, await proven revision, and compare actual full forest with preview.
- [ ] Drift between Plan/Apply and prove `409 STALE_PLAN` without mutation.
- [ ] Exercise rollback with a controlled mismatch, then prove exact forest/config restoration.
- [ ] Replay operation ID and prove mutation count remains one.
- [ ] Compile/typecheck against actual Jira, ScriptRunner, and Structure JARs; only documented dynamic endpoint errors may be classified separately.
- [ ] Add a real-classpath CI job only with an observed approved runner label. Otherwise record `BLOCKED` and do not claim completion.
- [ ] Verify installed ScriptRunner UI shows no `Anonymous allowed` for any Doctor endpoint; retain only a redacted screenshot.
- [ ] Document atomic copy of controller plus `structuredoctor/` subtree to every Jira node, permissions, operation states, recovery, and uninstall.
- [ ] Record versions, commands, assertion counts, revision/fingerprint comparisons, rollback, and artifact identity in OP-1371.
- [ ] Commit.

```powershell
git add jira/tests/structureIssueDoctor.integration.groovy README.md .github/workflows/ci.yml
git commit -m "OP-1371 test: verify live duplicate repair workflow"
```

## Task 14: Simplify, review, and close only on evidence

- [ ] Run the code-simplifier gate on OP-1371 files only; accept behavior-preserving reductions, no unrelated refactor.
- [ ] Re-run parse, offline, HTTP, hygiene, security, line-count, and real-classpath checks.
- [ ] Inspect final scope.

```powershell
git status --short
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
git log --oneline origin/main..HEAD
$unfinished = @('TO' + 'DO', 'T' + 'BD', 'FIX' + 'ME')
foreach ($marker in $unfinished) {
    rg -n $marker jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests README.md CONTRIBUTING.md
}
rg -n "Anonymous allowed|groups: \[\]" jira/structureIssueDoctor.groovy jira/structuredoctor jira/tests README.md CONTRIBUTING.md
```

- [ ] Confirm every commit starts `OP-1371 `, no unrelated files changed, all new source files meet the line cap, and no credential/private-infrastructure string entered the diff.
- [ ] Obtain independent review of mutation atomicity, stale binding, authorization, idempotency, calculation evidence, rollback, and failed-read semantics.
- [ ] Fix each high-confidence finding with a failing regression first, then rerun the relevant full suite.
- [ ] After any separately authorized installation, measure artifact identity on every target node and the actual forest result.
- [ ] Post C1 requested behavior, C2 verification, C3 security/privacy, and C4 scope integrity to OP-1371.
- [ ] Mark Done only when every acceptance criterion and target identity pass. Otherwise leave In Progress with the precise blocker.

## Acceptance trace

| Design criterion | Evidence |
| --- | --- |
| Structure without issue key | Task 8 fake/live tests |
| Complete paths and provenance | Tasks 5 and 8 |
| Optional group and exactly one keep | Tasks 6 and 8 |
| Individual proposal descriptions | Task 6 |
| Complete impact or blocked Apply | Tasks 7 and 9 |
| Fresh server-generated plans | Tasks 4, 6, 9, 11 |
| Locked, idempotent, journaled | Task 10 |
| Actual forest verification/rollback | Tasks 11 and 13 |
| Admin and Structure permissions | Task 12 |
| Parse/offline/HTTP/hygiene/classpath | Tasks 3, 12, 13, 14 |
| Legacy behavior retained | Tasks 2, 8, 14 |
