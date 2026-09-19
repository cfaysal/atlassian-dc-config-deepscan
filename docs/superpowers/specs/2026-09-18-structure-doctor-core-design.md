# Structure Doctor Core Design

Status: approved

Date: 2026-09-18

Work item: OP-1371

Supersedes: `docs/superpowers/specs/2026-09-17-structure-doctor-dedupe-design.md`

## Decision summary

The Structure Doctor is a Jira-administrator-only SAFe hierarchy health, root-cause analysis, and transactional repair tool. An administrator selects one Structure and explicitly starts a read-only analysis. The Doctor reads the complete Structure, the globally configured Jira Advanced Roadmaps hierarchy, relevant Jira issue data and history, Structure generators, relevant Jira Automation rules, and available Automation audit data. It correlates those sources into findings, causal explanations, evidence grades, and safe repair proposals.

The Doctor is not only a duplicate remover. Duplicate analysis is one part of a general hierarchy health analysis that also detects missing or conflicting parents, invalid levels, orphaned items, field divergence, overlapping generators, and Automation conflicts.

The following configuration is always read-only:

- the global Jira hierarchy configured through `PortfolioHierarchy.jspa`;
- every Jira Automation rule.

The Doctor may change only:

- the selected Structure and its generator configuration, after complete simulation and explicit selection;
- explicitly selected Jira issue data such as Parent Link, after complete simulation and an additional final confirmation.

## Problem

The existing Structure Issue Doctor analyzes one Jira issue at a time. It can explain Structure placement and selected hierarchy-field mismatches, and it can repair Parent Link in a narrow, instance-specific case. It does not provide a complete health assessment for a selected Structure and cannot reliably explain how Jira hierarchy configuration, Jira issue data, Automation rules, Jira links, and Structure generators combine to create a visible problem.

The observed failure mode demonstrates why a Structure-only view is insufficient. The same issue can enter a Structure through both an Advanced Roadmaps hierarchy generator and a Jira-link extender. Automation rules can populate, clear, or overwrite Parent Link or related fields. Two enabled rules can use different source fields or scopes. The visible duplicate is therefore an effect. A durable diagnosis must trace the chain that produced it.

Removing a visible row is not necessarily a repair because a generator can recreate it. Changing a generator can affect multiple duplicate groups. Changing Parent Link affects Jira data globally and may change other Structures, plans, and reports. Changing an Automation rule or the global Jira hierarchy would have even broader effects and is outside the Doctor's mutation authority.

## Observed evidence that motivates the design

The current problem report is based on the Structure `Enterprise Portfolio Roadmap`. The observed Structure contains 1,878 displayed items, with 34 items reported as having duplicates. Its visible generator chain includes:

```text
Add issues linked by Teil: parent beinhaltet children
Extend with Advanced Roadmaps (Portfolio) child issues
Insert issues: project = "Enterprise Portfolio" and issuetype = "Strategisches Ziel"
```

This establishes a concrete overlap candidate: a general Jira-link extender and the native Advanced Roadmaps child extender can render the same child through separate relationship systems. It does not by itself prove the cause for every duplicate. Confirmation requires both occurrence paths, their generator provenance, and the relevant Jira relationship data.

The observed global Jira hierarchy is configured as:

```text
Level 3  Ebene 0 Ziel        -> Strategisches Ziel
Level 2  Ebene 1 Aktionsfeld -> Strategische Ziele KPI
Level 1  Initiative          -> SAFe-Enabler-Epic, SAFe-Business-Epic
         Epic                -> Epic
         Story               -> all other standard issue types
         Sub-task            -> all sub-task issue types
```

The current instance policy established for the existing Doctor is that the field named `Strategische Ziele KPI` is the leading business relationship where that field applies, while Parent Link is derived Jira hierarchy data. This is an instance-wide policy, not a project-specific hierarchy and not a hard-coded generic-core assumption. A generic repair may use this policy only when its source and target are unambiguous; otherwise the repair is blocked.

Two exported Jira Automation rules were inspected:

- Rule 541 is enabled across seven projects, triggers asynchronously when `Strategisches Thema` changes on `SAFe-Business-Epic` or `SAFe-Enabler-Epic`, clears Parent Link when the source is empty, and otherwise writes the source value to Parent Link.
- Rule 562 is enabled for one project, triggers asynchronously when `Strategisches Ziel` changes on issue type `Strategisches Thema`, clears Parent Link when the source is empty, and otherwise writes the source value to Parent Link.
- Both rules have rule-chaining disabled.
- Neither inspected rule derives Parent Link from the field named `Strategische Ziele KPI`.

These facts establish configuration-conflict candidates, not confirmed execution causes. Audit execution and issue-history correlation are required to raise the evidence grade.

## Goals

1. Analyze a complete administrator-selected Structure without requiring an issue key.
2. Read the global Jira hierarchy dynamically and use it as the authority for hierarchy levels and issue-type assignments.
3. Detect duplicate and non-duplicate hierarchy health problems.
4. Analyze relevant Jira Automation rules and available audit evidence as part of the Doctor Core.
5. Explain the causal chain from configuration or data to the visible Structure finding.
6. Distinguish configuration conflicts, possible causes, probable causes, and confirmed causes.
7. Offer only repairs whose complete impact can be computed and whose prerequisites are still current.
8. Keep every repair opt-in. Everything not selected is ignored.
9. Make generator-wide repairs atomic when one change affects multiple finding groups.
10. Journal, verify, and, where supported, roll back every mutation.

## Non-goals

- Do not modify the global Jira hierarchy.
- Do not modify, enable, disable, import, or publish Jira Automation rules.
- Do not scrape `PortfolioHierarchy.jspa` or an Automation administration page.
- Do not couple the core directly to Automation Active Objects database tables.
- Do not treat every repeated issue occurrence as an accidental duplicate.
- Do not accept free-form JQL, generator configuration, field values, or executable Automation
  configuration from the browser. The explicit exception is bounded, read-only Automation
  evidence JSON parsed as inert data for analysis only.
- Do not silently remove permanent rows or generated occurrences.
- Do not execute a repair whose complete effect is unknown.
- Do not deploy or install the endpoint as part of design or implementation planning.

## Fixed safety invariants

1. Every endpoint is restricted to `jira-administrators` and also rejects a missing authenticated user.
2. Analysis is read-only.
3. Jira hierarchy configuration is an immutable runtime reference.
4. Jira Automation configuration is read-only.
5. A failed or incomplete read is not an empty result.
6. Missing evidence blocks every repair that depends on that evidence.
7. Missing evidence does not block a repair proven independent of it.
8. A browser submits identifiers and confirmations only. It never submits executable configuration.
9. Every repair is rebuilt and resimulated on the server immediately before execution.
10. Stale configuration, data, or fingerprints invalidate the dependent repair.
11. Jira issue data changes require a separate final confirmation after fix selection.
12. A write response is not proof of success. The measured target state is the proof.

## Terminology

- **Jira hierarchy configuration:** the global Advanced Roadmaps level and issue-type configuration administered through `PortfolioHierarchy.jspa`.
- **Native hierarchy relation:** the actual Jira parent relationship represented by Parent Link or its installed-version equivalent.
- **Occurrence:** one physical appearance of a Jira issue in a Structure forest.
- **Finding group:** one or more related observations that share a cause or repair, including duplicate groups.
- **Evidence coverage:** which required sources and time ranges were successfully read.
- **Repair package:** one atomic repair that may cover multiple finding groups when they share one generator or Jira-data mutation.
- **Structure fix:** a mutation to the selected Structure or its generators.
- **Jira data fix:** a mutation to an issue field or native parent relation.

## User workflow

1. An administrator opens the Doctor. Authentication and group membership are checked. The page loads selectable Structures but does not run a full analysis.
2. The administrator selects one Structure. An issue key is not required.
3. The administrator explicitly selects **Analyze Structure**.
4. The Doctor creates a dated, immutable analysis snapshot and reads all required sources.
5. The Doctor displays source coverage, summary counts, findings, causal explanations, evidence grades, and repair proposals.
6. The administrator selects only the finding groups or atomic repair packages to address. Unselected findings are ignored.
7. For a duplicate group, the administrator selects which occurrence to retain unless the group is blocked.
8. The Doctor displays the complete simulated impact of each selected repair.
9. Structure fixes require explicit confirmation that the selected Structure will change.
10. Jira data fixes additionally display old and new values, the derived hierarchy reasoning, and a warning that the change can affect every Structure, plan, and report consuming that Jira data.
11. Jira data fixes require a second, final confirmation after selection.
12. The Doctor rereads all dependencies, rebuilds the plan, checks fingerprints, acquires the appropriate lock, and applies only fresh repairs.
13. The Doctor verifies the measured result and rolls back a proven mismatch where rollback is supported.

## Core architecture

```text
REST and UI
  -> Configuration Discovery
  -> Snapshot Engine
  -> Analysis Engine
  -> Causality Engine
  -> Repair Planner
  -> Impact Simulator
  -> Repair Executor
  -> Live Verifier and Rollback
```

### REST and UI

Owns authentication, authorization, request validation, Structure selection, response negotiation, HTML escaping, and identifier-only request contracts. It renders analysis coverage and disabled states explicitly. It does not interpret browser-provided text as Jira data, JQL, generator configuration, or Automation configuration.

### Configuration Discovery

Discovers and fingerprints:

- installed Jira, Advanced Roadmaps, Structure, ScriptRunner, and Jira Automation versions;
- the global Jira hierarchy level and issue-type mapping;
- the selected Structure's generator definitions, ordering, parameters, and relevant revision markers;
- available Automation read providers and their coverage;
- available lock, journal, preview, mutation, recalculation, and rollback capabilities.

Configuration Discovery reports capabilities. It does not guess an API when a capability cannot be proven.

### Snapshot Engine

Builds an immutable `DoctorSnapshot` containing:

- selected Structure identity and revision;
- complete forest rows and physical occurrence identifiers;
- complete hierarchy paths, parents, depth, and position;
- permanent or generated provenance;
- generator identities, module keys, types, order, parameters, and scopes;
- relevant Jira issue types, native parent relations, hierarchy-related fields, links, and field history;
- global Jira hierarchy levels and issue-type assignments;
- relevant Automation rule definitions;
- available Automation audit entries for the requested window;
- explicit source errors, caps, time windows, and completeness states;
- deterministic fingerprints for every dependency that can invalidate a repair.

The Snapshot Engine never converts a failed read into an empty collection.

### Analysis Engine

Produces typed findings without mutation. It detects at least:

- duplicate occurrences;
- missing parents despite an available leading relationship;
- native parent values that conflict with a leading relationship;
- parents on an invalid hierarchy level;
- competing parent relationships;
- issues orphaned from the configured hierarchy;
- issues rendered below the wrong path;
- generators that render the same relationship more than once;
- overlapping inserters, extenders, permanent rows, and duplicates filters;
- Automation rules that overwrite or clear valid relationships;
- multiple enabled rules that write the same hierarchy field in overlapping scope;
- mismatches among the configured hierarchy, issue data, Automation behavior, and Structure rendering.

Field names, issue-type names, project names, and link types are runtime facts or adapter inputs. They are not hard-coded into the generic core.

### Causality Engine

Builds a bounded causal graph for each finding:

```text
Automation trigger and conditions
  -> Automation field or link write
  -> Jira issue data and native parent relation
  -> Structure generator selection
  -> Structure occurrence and path
  -> finding group
```

The graph contains only relations supported by the captured rule definition, issue history, audit record, generator definition, and Structure snapshot. It distinguishes correlation from confirmed execution.

### Repair Planner

Generates one or more server-side repair proposals and their blockers. A proposal includes:

- finding-group IDs;
- occurrence to retain where applicable;
- occurrences expected to disappear or move;
- repair type and strategy;
- affected issues and generators;
- configuration or field values before and after;
- expected complete forest and Jira-data delta;
- user-facing explanation and warnings;
- required evidence and its coverage;
- rollback description;
- dependency fingerprints;
- deterministic proposal ID.

### Impact Simulator

Computes the complete expected effect before a repair becomes selectable. For Structure changes it computes the before and after forest. For Jira-data changes it computes the native parent and field delta and every known affected occurrence in the selected Structure. It also states the broader Jira-wide effect that cannot be reduced to the selected Structure.

If the simulator cannot determine the full mutation population or prove that the retained occurrence survives, the proposal is blocked.

### Repair Executor

Accepts proposal identifiers, operation identifiers, and explicit confirmations only. It rereads dependencies and reconstructs the canonical proposal. It enforces permissions, evidence coverage, freshness, locking, idempotency, and confirmation requirements before any write.

The executor has no method for writing Jira hierarchy configuration or Automation rules.

### Live Verifier and Rollback

Reads the actual recalculated Structure and actual Jira issue data after mutation. It marks an operation verified only when the measured result matches the approved simulation. A proven mismatch restores the exact saved mutable configuration or issue data and verifies the restoration. A timeout is `PENDING`, not success and not proof of mismatch.

## Jira hierarchy provider

The Doctor reads the backing configuration represented by `PortfolioHierarchy.jspa`; it does not scrape the page. The provider returns an ordered level model with the issue types assigned to each level and a version or deterministic fingerprint.

The Jira hierarchy provider is strictly read-only. The Doctor contains no endpoint, UI control, service method, or data-access path that can create, delete, reorder, rename, or reassign hierarchy levels.

If the hierarchy cannot be read completely:

- the Doctor may display Structure duplicates and other facts that do not require hierarchy interpretation;
- the analysis is marked incomplete;
- every hierarchy-dependent repair is disabled;
- independent repairs may remain selectable if their independence is proven.

If the Doctor detects a possible hierarchy-configuration problem, it reports that Jira-administrator review is required. It never offers an executable hierarchy fix.

On the probed Jira 11.3.11 and Advanced Roadmaps 11.3.11 installation, the read-only
`ExportedHierarchyLevelApi` is proven with `count()` and `findAll(int,int)`. It provides the
ordered levels and their issue-type assignments. Other installed versions remain unsupported
until their read path is proven. An unsupported version results in explicit incomplete
coverage, not UI scraping or a database write path.

## Automation data providers

Automation analysis is part of the Doctor Core.

```text
AutomationDataProvider
  -> LiveAutomationProvider
  -> JsonExportProvider
```

### LiveAutomationProvider

The preferred provider detects the installed Jira Automation version and uses only proven
read capabilities. The OP-1371 probe confirmed the Automation app is installed, but it did
not expose a complete read-only rule-listing and audit-listing service. Live Automation reads
therefore remain `UNAVAILABLE` on this installation rather than returning an empty result or
using partial internal caches.

### JsonExportProvider

The fallback provider accepts official Jira Automation rule-export JSON and normalized
Structure Doctor audit-evidence JSON. It records export version, requested interval, actual
interval, entry limits, and parse completeness. Import is bounded and read-only, and it never
executes or republishes supplied JSON. Rule evidence can establish a configuration conflict;
missing audit evidence prevents promotion to a confirmed historical cause.

### Selection and coverage

The Doctor first determines the projects, issue types, fields, links, and native parent relations relevant to the selected Structure findings. It then filters Automation analysis to rules capable of reading or writing those values in overlapping scope.

Audit correlation uses 30 days by default and allows a configurable window. The result always shows actual coverage, for example `7 of requested 30 days`. Missing days reduce the evidence grade and selectively block dependent repairs.

Direct Automation database coupling is excluded from the core. Official diagnostic database queries may inform an administrator, but they are not the Doctor's runtime provider contract.

## Automation rule analysis

For each relevant rule, the Doctor extracts and normalizes:

- enabled state and scope;
- trigger and changed fields;
- issue-type, project, field, JQL, and branch conditions;
- fields and links read;
- fields and links written or cleared;
- asynchronous execution behavior;
- whether other rules may trigger it;
- actor and permission-relevant metadata where available;
- component order and rule revision;
- matching audit executions and associated issues.

The Doctor detects at least:

- multiple writers for one hierarchy field;
- conflicting source fields for one target;
- overlapping project and issue-type scopes;
- clear-on-empty behavior that removes a valid relationship;
- possible loops or rule chains;
- asynchronous races and last-writer ambiguity;
- writes whose target issue type conflicts with the configured hierarchy;
- rule logic that can generate a link or parent relation consumed by a Structure generator.

The Doctor may explain how to change a rule, but it never changes, enables, disables, imports, or publishes a rule.

## Evidence grades

Every causal claim has one grade:

| Grade | Meaning |
| --- | --- |
| Configuration conflict | Current configurations contradict each other or the global Jira hierarchy. Execution is not proven. |
| Possible cause | A relevant enabled rule or generator can produce the observed state in overlapping scope. |
| Probable cause | Rule, scope, issue state, field history, generator, and observed finding align, but direct execution evidence is incomplete. |
| Confirmed cause | A matching Automation audit execution or equivalent direct evidence links the rule action to the relevant issue/data change and resulting finding. |

The UI states which evidence is present and which is missing. It never upgrades a static rule match to a confirmed cause.

## Duplicate decision logic

For every Jira issue with more than one occurrence, the Doctor displays every occurrence with its row ID, complete path, immediate parent, depth, position, provenance, generating rule, generator configuration, and hierarchy evaluation.

| Situation | Recommendation and repair state |
| --- | --- |
| Exactly one occurrence follows the configured Jira hierarchy | Recommend retaining that occurrence. User confirmation remains required. |
| Both are level-valid, one follows the native Advanced Roadmaps parent relation and one follows a general Jira link | Recommend retaining the native hierarchy occurrence. User confirmation remains required. |
| Both are equally valid | Do not select automatically. The administrator chooses which occurrence to retain. |
| No occurrence is hierarchy-valid | Report a data or configuration error and block De-Dupe repair. |
| Hierarchy data is incomplete | Display duplicates but block hierarchy-dependent repair. |
| Provenance or complete impact is unknown | Display the finding and explanation, but do not provide an executable repair. |

## Repair types and boundaries

### Structure repairs

Supported strategies may include:

1. add, move, or correct a duplicates filter;
2. narrow an overlapping JQL inserter with a general structural rule;
3. restrict an extender scope or starting point;
4. reorder generators or filters when order alone determines the result;
5. remove a permanent row only when the administrator explicitly selects a supported permanent-row proposal and complete impact is proven.

Deleting one generated row without changing its source is not a durable repair and is not offered as a successful fix.

### Atomic generator packages

One generator change can affect multiple finding groups. Such a change is one atomic repair package:

- every affected group and issue is listed;
- the package is selected or ignored as a whole;
- partial execution is forbidden;
- simulation, write, verification, and rollback cover the whole package;
- failure of one required step fails the package.

### Jira data repairs

The Doctor may repair Jira issue data such as Parent Link only when the leading relationship and target are unambiguous and the complete direct mutation is known. Each proposal shows:

- issue identity;
- old and new values;
- configured hierarchy levels and issue types involved;
- the evidence used to derive the new value;
- known effects in the selected Structure;
- an explicit warning that Jira data is global and can affect other Structures, plans, and reports.

Jira data repair requires both ordinary proposal selection and an additional final confirmation.

### Never writable

The following have no executable repair path:

- global Jira hierarchy configuration;
- Jira Automation rule configuration.

## Selective fail-closed policy

Each proposal declares its required evidence set. Examples include a complete Structure forest, complete generator provenance, a hierarchy fingerprint, a specific issue revision, an Automation rule revision, or an audit interval.

If one requirement is missing or stale, only proposals depending on it are blocked. The analysis summary remains visible and independent proposals may remain enabled. The UI must never present an incomplete analysis as a clean Structure.

## HTTP contracts

Exact endpoint names may preserve compatibility with the existing ScriptRunner endpoint, but the logical contracts are:

### Analyze

```text
POST structureIssueDoctorAnalyze
  structureId
  optional issueKey filter for focused display only
  requestedAuditDays, default 30
  optional uploaded rule-export reference
  optional uploaded audit-export reference
```

The request starts the explicit read-only analysis. It returns snapshot ID, fingerprints, coverage, findings, causal graphs, and summary counts. Uploads are parsed as data and never executed or republished.

### Plan

```text
POST structureIssueDoctorPlan
  snapshotId
  selected findingGroupIds
  keepOccurrenceId per selected duplicate group
```

The server rebuilds proposal candidates. Shared-generator changes are returned as atomic packages. Browser-supplied JQL, field values, and generator parameters are rejected.

### Apply

```text
POST structureIssueDoctorApply
  snapshotId
  repairPackageId
  operationId
  confirmStructureChange
  confirmJiraDataChange when required
```

The server rereads and reconstructs every package before writing. Stale state returns `409 STALE_PLAN` and requires a new analysis.

### Status

```text
GET structureIssueDoctorStatus
  operationId
```

Status is read-only and returns the cluster-visible journal state. Retrying the identical POST Apply resumes verification for a pending Structure recalculation or Jira-data verification under the same locks. Closing the browser does not discard a pending operation.

All endpoint declarations use `groups: ["jira-administrators"]` and all handlers independently require an authenticated user.

## Concurrency, idempotency, and journaling

- A cluster-wide Structure lock serializes generator mutations for one Structure.
- Jira-data repair locks or compare-and-set preconditions are scoped to the affected issues.
- An operation ID makes Apply idempotent.
- Shared dependencies prevent concurrently executing repair packages from invalidating each other.
- Before mutation, the Doctor writes a cluster-visible repair journal record.
- Journal records contain bounded before and after state required for recovery, but no credentials or unnecessary issue content.

An operation follows this lifecycle:

```text
ANALYZED -> PLANNED -> CONFIRMED -> APPLIED -> VERIFYING
                            |                    |-> VERIFIED
                            |                    |-> ROLLED_BACK
                            |                    |-> PENDING
                            |                    |-> MANUAL_RECOVERY_REQUIRED
                            |-> MUTATION_FAILED
                            |-> MANUAL_RECOVERY_REQUIRED
```

If no supported cluster-visible journal or lock capability can be proven, the affected repair type remains disabled.

## Error handling

- Invalid input returns `400`.
- Missing authentication returns `401`.
- Missing administrator or target permission returns `403`.
- Invisible Structure or issue returns `404` without claiming global absence.
- Stale dependencies return `409`.
- A source failure produces incomplete coverage and dependent blocked proposals.
- An empty successful source and a failed source are separate states.
- A timeout produces `PENDING` or incomplete coverage, never false success.
- An explicit writer receipt proving that nothing was applied produces terminal `MUTATION_FAILED`.
- A missing writer receipt or writer exception has an unknown target effect and produces `MANUAL_RECOVERY_REQUIRED` without an automatic blind retry.
- A rollback failure produces `MANUAL_RECOVERY_REQUIRED` and blocks further conflicting repairs.

## Performance constraints

- Structure traversal is linear in forest rows.
- Jira, Structure, history, Automation, and audit reads are batched and bounded. No per-row remote lookup is allowed.
- Initial analysis builds findings and causal candidates.
- Full repair impact simulation may be lazy, but all selected atomic packages are simulated before confirmation.
- Audit queries are restricted by relevant rule, issue, scope, and requested time range.
- Coverage caps and truncation are visible and participate in repair blocking.

## Security and privacy

- Only `jira-administrators` may access analysis or repair endpoints.
- Target permissions are checked separately from group membership.
- Uploaded JSON is treated as untrusted data, size-limited, schema-validated, and never executed.
- The browser cannot inject JQL, Groovy, field values, generator parameters, or rule configuration.
- Logs exclude credentials, full private rule payloads, full JQL, and unnecessary issue content.
- The repair journal stores only the bounded material required for verification and recovery.
- All examples and offline fixtures use synthetic data.

## Verification strategy

### Offline tests

Fixtures cover:

- two and three duplicate occurrences;
- valid, invalid, and equally valid retention choices;
- native hierarchy and Jira-link overlap;
- missing and malformed hierarchy coverage;
- missing parents, conflicting parents, orphans, and wrong levels;
- inserter, extender, permanent-row, and duplicates-filter interactions;
- multiple Automation writers and conflicting source fields;
- clear-on-empty rules, asynchronous overlap, and rule chains;
- full, partial, capped, and missing Automation audit windows;
- live-provider and JSON-provider normalization;
- independent and evidence-dependent repair proposals;
- atomic multi-group generator packages;
- Jira-data confirmation and global-impact warning;
- stale hierarchy, rule, issue, Structure, and generator fingerprints;
- idempotency, verification, rollback, and manual recovery.

### Required invariants

- The global Jira hierarchy never has a write path.
- Jira Automation rules never have a write path.
- The selected retained occurrence survives a verified De-Dupe repair.
- Unselected independent finding groups remain unchanged.
- An atomic package is never partially applied.
- A repair creates no new unapproved finding.
- Incomplete evidence cannot yield an enabled dependent proposal.
- A Jira-data fix cannot execute without the additional confirmation.
- Stale state returns `409`.
- One operation mutates at most once.
- Failed reads never render as empty results.
- Verification uses measured target state.

### HTTP and security tests

- Anonymous callers are rejected.
- Authenticated non-administrators are rejected.
- `jira-administrators` can analyze.
- Missing Structure or issue permission blocks the related repair.
- Invalid or oversized JSON export input is rejected.
- Forged snapshot, finding, proposal, and operation identifiers are rejected.
- Browser-submitted JQL, field values, and generator parameters are rejected.
- Changed fingerprints invalidate the plan.
- Repeated operation IDs do not apply twice.
- ScriptRunner does not display `Anonymous allowed` for any Doctor endpoint.

### Live capability and integration verification

Before enabling a capability, read-only probes must prove the installed APIs for:

- global hierarchy reads;
- complete Structure forest, generator, provenance, and revision reads;
- live Automation rule and audit reads, or explicit `UNAVAILABLE` coverage plus the bounded
  JSON evidence fallback when no complete live interface is proven;
- Jira issue history and native parent reads;
- Structure preview or equivalent complete simulation;
- generator mutation and restoration;
- cluster-visible lock and journal persistence;
- recalculation completion and measured verification.

A disposable test Structure exercises duplicate and non-duplicate findings, both retention choices, one atomic generator package, one Jira-data repair, stale-plan rejection, verification, and rollback. Automation remains read-only throughout testing.

## Repository placement

The shipped ScriptRunner entry point remains:

```text
jira/structureIssueDoctor.groovy
```

Focused helper classes belong under `jira/structuredoctor/`. Tests and synthetic fixtures belong under `jira/tests/`. Development-only capability probes belong under `tools/`. The entry point remains a thin REST and rendering controller.

## Acceptance criteria

1. An administrator can select and explicitly analyze a complete Structure without an issue key.
2. No full analysis starts merely because the page opened or a Structure was selected.
3. The Doctor reads the globally configured Jira hierarchy dynamically and contains no hierarchy write path.
4. The Doctor detects duplicate and general hierarchy health findings.
5. Every finding shows paths, relevant Jira data, generator provenance, cause candidates, evidence coverage, and evidence grade.
6. Automation rule analysis and audit correlation are part of the Core, with 30 days as the default requested window.
7. The hybrid Automation provider prefers proven live reads and supports official JSON exports as fallback.
8. Automation rules contain no executable mutation path.
9. Duplicate retention follows the approved hierarchy decision matrix and always requires explicit user selection or confirmation.
10. Shared-generator repairs are presented and executed as atomic packages covering every affected group.
11. Unknown impact or missing required evidence blocks the dependent repair.
12. Structure repairs require explicit Structure-change confirmation.
13. Jira-data repairs show old and new values, hierarchy reasoning, known impact, Jira-wide warning, and require an additional final confirmation.
14. Unselected findings and repair packages are ignored.
15. Every mutation is fresh, server-generated, permission-checked, locked, idempotent, journaled, verified, and recoverable where the installed APIs support rollback.
16. The actual recalculated Structure and Jira issue data, not write responses, prove success.
17. Anonymous access is absent and all Doctor endpoints are restricted to `jira-administrators`.
18. Offline, HTTP, security, capability, integration, parse, hygiene, and real-classpath checks pass before deployment.

## Implementation gate

### Report usability correction, 2026-09-20

The reported LIVE analysis displayed finding codes without identifying the affected work
items. The presentation now carries the key, summary and type from the existing
permission-checked Jira read through to the report. Each hierarchy observation compares
Structure parent/path, native Jira parent, configured parent level, source and physical row.
Its own explanation and investigation step must remain distinct from an executable repair.
An absent parent alone is not proof of invalid Jira data or a mandatory-parent rule.

Hierarchy observations have no selection checkbox until an actual planner consumes those
selections. Duplicate selection remains opt-in with an explicit retained occurrence;
disabling a group clears every dependent choice. Retained permanent rows cannot be selected
for removal. Unknown Automation sources must read as not checked, not as no conflicts.
Display metadata does not participate in the repair planning fingerprint.

This correction is not acceptance of the full Core. The live Automation readers, live
repair-proposal discovery and Core mutation infrastructure remain unwired/disabled. The
JSON Automation evidence fallback remains available. Actual target-classpath compilation,
live end-to-end acceptance and the separate repair safety proofs are still required.

Local evidence: synthetic mapper/analyzer/renderer regression, complete offline Core suites,
single-file generation/security checks, and browser selection/payload verification. No
customer Jira or Structure configuration is modified by this correction.

The earlier De-Dupe implementation plan does not cover this approved Core scope and must not be executed. A replacement plan may be written only after this specification is reviewed. No implementation, deployment, Jira mutation, Structure mutation, hierarchy mutation, or Automation mutation is authorized by this document.
