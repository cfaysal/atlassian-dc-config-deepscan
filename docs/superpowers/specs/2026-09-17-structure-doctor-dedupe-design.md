# Structure Issue Doctor De-Dupe Design

> **Superseded on 2026-09-18 by
> `docs/superpowers/specs/2026-09-18-structure-doctor-core-design.md`.**
> This document is retained as design history. Do not use it as the implementation authority for OP-1371.

Status: superseded

Date: 2026-09-17

Work item: OP-1371

## Problem

The existing Structure Issue Doctor diagnoses one Jira work item at a time. It can explain Structure placement, duplicate occurrences, generator provenance, and a mismatch between `Strategische Ziele KPI` and Advanced Roadmaps Parent Link. Its only automatic repair sets Parent Link after Jira validates the update.

It cannot analyze an entire selected Structure for duplicate occurrences or repair the generator configuration that recreates those duplicates. A duplicate can be caused by overlapping JQL inserters, extenders, permanent rows, generator order, or a missing or misplaced duplicates filter. Removing one visible row is not a durable repair because the generator can recreate it. Changing a generator without computing its full effect can also remove or move unrelated work items.

## Goal

Add an optional structure-wide De-Dupe mode. An administrator selects a Structure without being required to enter a work-item key, reviews every duplicate group, optionally chooses one occurrence to retain, and receives individually explained fix proposals.

The Doctor may adjust duplicates filters, JQL inserters, extender scopes, and generator order. It may apply a proposal only when the complete Structure-wide effect is computable, the plan is still current, the required Structure APIs are proven available, and the administrator explicitly confirms that the Structure configuration will change.

## Non-goals

- Do not silently remove duplicate rows.
- Do not treat every occurrence under a different parent as accidental.
- Do not accept arbitrary JQL or generator parameters from the browser.
- Do not automatically remove permanent rows.
- Do not apply a proposal whose complete impact is unknown.
- Do not turn the first release into a Structure-wide optimizer that combines unrelated repairs.
- Do not deploy or install the endpoint as part of the design work.

## Repository placement

The endpoint belongs in this repository because it is a Jira Data Center ScriptRunner administration and diagnostic tool.

The shipped entry point is:

```text
jira/structureIssueDoctor.groovy
```

Tests belong under:

```text
jira/tests/structureIssueDoctor.tests.groovy
```

The repository `tools/` directory remains limited to development and verification utilities. It is not a home for shipped endpoints.

The current endpoint is already too large to absorb the feature safely. The entry point remains a thin REST and rendering controller. Analysis, planning, simulation, execution, journaling, and verification are placed in focused Groovy classes under `jira/structuredoctor/`, mapped to the same `structuredoctor` package in the Jira script-root payload. The installation copies the endpoint and that helper subtree together. Each source file should remain below 250 lines unless a measured ScriptRunner constraint makes that impossible and the exception is recorded in OP-1371.

## User workflow

The page requires a Structure and makes the work-item key optional.

Controls:

- Structure: required.
- Work-item key: optional targeted filter.
- De-Dupe: enables complete duplicate analysis for the selected Structure.
- Analyze: performs a read-only analysis.

The summary reports:

- total Structure rows;
- unique Jira work items;
- duplicate groups;
- automatically repairable groups;
- blocked groups;
- generator conflicts.

Each duplicate group has an opt-in checkbox. A disabled group remains unchanged. An enabled group requires exactly one occurrence to be selected as the occurrence to retain. Radio buttons enforce that only one occurrence can be retained even when a work item has three or more occurrences.

Each occurrence displays:

- row ID;
- complete hierarchy path;
- immediate parent;
- depth and position;
- permanent or generated provenance;
- creator generator and generator type;
- relevant JQL, extender rule, and scope;
- a plain-language explanation of why the occurrence exists.

Selecting an occurrence reveals every supported plan that retains it. Every plan has its own description and explicitly states that applying it changes the selected Structure.

## Architecture

```text
REST and UI
  -> Structure Snapshot
  -> Duplicate Analyzer
  -> Fix Planner
  -> Impact Simulator
  -> Fix Executor
  -> Live Verifier and Rollback
```

### REST and UI

Owns authentication, request validation, Structure selection, response negotiation, HTML escaping, and JSON contracts. It never interprets browser-provided text as generator configuration.

### Structure Snapshot

Reads the complete current forest, generator definitions, generator order, parameters, row provenance, and hierarchy paths. It creates a deterministic fingerprint from the state that can affect planning.

### Duplicate Analyzer

Groups occurrences by Jira issue ID. It records all paths, parents, generator sources, permanent rows, and provenance gaps. It distinguishes a complete analysis from an incomplete read.

### Fix Planner

Builds proposals for one duplicate group and one selected occurrence to retain. It ranks supported strategies and records blockers without mutating Structure.

### Impact Simulator

Computes the complete before and after forest for a proposal. It lists every affected work item, occurrence, parent path, and row-count change. If it cannot compute the complete effect, the proposal is blocked.

### Fix Executor

Rebuilds and verifies a server-generated proposal immediately before applying it. It accepts identifiers and explicit confirmation, not free-form generator configuration.

### Live Verifier and Rollback

Reads the actual recalculated forest. It marks the operation verified only when the measured result matches the approved simulation. A proven mismatch restores the exact prior generator configuration and verifies that restoration.

## Duplicate-group analysis

For every Jira work item with more than one occurrence, the Doctor analyzes:

1. Issue identity and occurrence count.
2. Row IDs, positions, depth, immediate parents, and complete paths.
3. Permanent versus generated provenance.
4. Creator generator IDs, module keys, types, order, parameters, and scope.
5. Overlap between inserters, extenders, and permanent rows.
6. Presence, position, and scope of duplicates filters.
7. Whether JQL filters include or exclude the issue.
8. Whether Jira hierarchy fields explain a path, including Parent Link and `Strategische Ziele KPI` where applicable.
9. Whether generator provenance is complete enough to produce a durable plan.

Recognized explanations include insert and extend overlap, root and child paths, multiple hierarchy paths, multiple generators, mixed permanent and generated provenance, missing duplicates filters, and unresolved provenance.

## Fix proposal model

Every proposal contains:

- duplicate-group ID;
- occurrence to retain;
- occurrences expected to disappear;
- strategy and ranking;
- affected generators;
- configuration before and after;
- affected Jira work items;
- expected forest delta;
- user-facing description;
- warnings and blockers;
- rollback description;
- snapshot fingerprint;
- deterministic proposal ID.

The description answers:

- What changes in Structure?
- Which occurrence remains?
- Why do the other occurrences stop being generated?
- Which generators change?
- Which other work items are affected?
- What will the resulting forest look like?
- How is the prior configuration restored?

## Supported fix strategies

Strategies are ranked by smallest complete impact.

1. Add, move, or correct a duplicates filter while preserving the underlying hierarchy rules.
2. Narrow an overlapping JQL inserter with a structural rule. Avoid issue-key exclusions when a general rule is available.
3. Restrict an extender scope or starting point.
4. Reorder generators or filters when order alone determines the duplicate path.

Ranking criteria, in order:

1. smallest affected population;
2. no effect on unrelated work items;
3. no semantic JQL change;
4. preserved intended hierarchy;
5. durable prevention of recurrence;
6. complete simulation and rollback support.

A proposal is blocked when:

- occurrence provenance is unknown;
- the required operation is unsupported by the installed Structure API;
- the complete affected population cannot be computed;
- both occurrences are permanent;
- retaining a generated occurrence would require removal of a permanent row;
- the retained occurrence would also disappear;
- another active operation owns the same Structure;
- any required read failed or was capped.

## HTTP contracts

### Analyze

```text
GET structureIssueDoctor
  structureId=<id>
  dedupe=true
  issueKey=<optional key>
  format=html|json
```

The response includes the snapshot fingerprint and duplicate groups. Analysis is read-only.

### Plan

```text
POST structureIssueDoctorPlan
  structureId
  duplicateGroupId
  keepOccurrenceId
```

The server rebuilds the snapshot and returns all supported plans for that selection. The browser cannot submit JQL or generator parameters.

### Apply

```text
POST structureIssueDoctorApply
  structureId
  proposalId
  snapshotFingerprint
  operationId
  confirm=APPLY_STRUCTURE_CHANGE
```

Before writing, the server rechecks authentication, group membership, Structure write permission, proposal identity, snapshot freshness, simulation completeness, and conflicts. Changed state returns `409 STALE_PLAN` and requires a new analysis.

### Status

```text
GET structureIssueDoctorStatus
  operationId
```

The status endpoint resumes verification of a pending Structure recalculation. It never treats a timeout as a mismatch.

All endpoints are registered with `groups: ["jira-administrators"]` and also reject a missing authenticated user.

## Trust boundaries

- The browser selects identifiers only.
- Proposal IDs are derived from canonical server-side plans.
- Apply rebuilds the plan rather than trusting a prior response body.
- A snapshot fingerprint binds the plan to the generator and forest state used by the simulation.
- Structure write permission is checked independently of Jira group membership.
- Incomplete or failed reads travel with explicit error state and cannot produce an enabled Apply action.

## Concurrency and idempotency

A cluster-wide, Structure-scoped lock serializes generator mutations. Other Structures remain available.

Every Apply request carries an operation ID. Repeating the same operation returns its current status and never applies the generator change twice.

If multiple selected groups would change the same generator, they are not combined in the first release. Each group is reanalyzed after the previous operation completes.

## Repair journal

Before mutation, the Doctor writes a cluster-visible repair record containing:

- operation ID and proposal ID;
- actor and timestamps;
- Structure and duplicate group;
- snapshot fingerprint;
- exact generator configuration before and after;
- approved impact summary;
- status history;
- verification and rollback result.

The journal stores no credentials and avoids full JQL text in ordinary logs. The implementation must use a Jira Data Center cluster-visible persistence mechanism proven against the installed Jira and ScriptRunner versions. If no supported bounded persistence mechanism is available, Apply remains disabled rather than falling back to node-local state.

## Operation lifecycle

```text
ANALYZED -> PLANNED -> APPLIED -> VERIFYING
                                  |-> VERIFIED
                                  |-> ROLLED_BACK
                                  |-> PENDING
                                  |-> MANUAL_RECOVERY_REQUIRED
```

- `VERIFIED`: the actual forest matches the approved simulation.
- `PENDING`: Structure has not published a new forest yet. No rollback occurs merely because time elapsed.
- `ROLLED_BACK`: a proven mismatch caused restoration of the prior configuration, and restoration was verified.
- `MANUAL_RECOVERY_REQUIRED`: restoration could not be verified. Further fixes on that Structure remain blocked and the journal exposes the saved configuration to administrators.

Closing the browser does not discard a pending operation. The next page load lists pending journal entries and resumes verification.

## Error handling

- Invalid input returns `400`.
- ScriptRunner rejects anonymous and unauthorized callers before the script runs. If the request still reaches the code without an authenticated user, the endpoint returns `401`.
- Missing Jira or Structure permission returns `403`.
- Invisible Structure or issue returns `404` without proving global absence.
- Stale plans and changed preconditions return `409`.
- Incomplete impact returns a blocked proposal, not a writable plan.
- Read failure and empty result are distinct response states.
- A timeout produces `PENDING` or an explicit incomplete analysis, never a false success or false mismatch.

## Audit

Audit entries include actor, Structure, duplicate group, retained occurrence, affected generator IDs, proposal ID, operation ID, state transitions, verification, and rollback outcome.

Normal logs exclude credentials, full generator payloads, full JQL strings, and unnecessary work-item content. The repair journal contains the bounded configuration material required for recovery and is accessible only through the administrator-gated endpoint.

## Verification strategy

### Offline fixtures

Tests cover:

- two and three occurrences;
- same and different parents;
- inserter plus extender overlap;
- overlapping inserters;
- permanent and generated rows;
- unknown provenance;
- missing and misplaced duplicates filters;
- incomplete reads;
- competing plans;
- generator conflicts.

Each test compares the complete forest before and after simulation.

### Required invariants

- The selected occurrence remains.
- Unselected groups remain unchanged.
- A fix creates no new duplicate group.
- Incomplete simulation cannot yield an enabled proposal.
- Browser input cannot inject JQL or generator parameters.
- Stale state returns `409`.
- One operation mutates at most once.
- Failed reads never render as empty results.
- Rollback restores the exact saved generator configuration.

### HTTP and security tests

- Anonymous callers are rejected.
- Authenticated non-administrators are rejected.
- `jira-administrators` can analyze.
- Missing Structure write permission blocks Apply.
- Invalid JSON returns `400`.
- A forged proposal ID is rejected.
- A changed fingerprint returns `409`.
- A repeated operation ID does not apply twice.
- The installed ScriptRunner page does not show `Anonymous allowed` for any Doctor endpoint.

### Jira and Structure integration test

A disposable test Structure deliberately creates a duplicate through two generator paths. The test measures both retention choices, applies one proposal, waits for recalculation, compares the actual forest with the simulation, exercises rollback, and restores the original test configuration. A deliberate drift between Plan and Apply proves stale-plan rejection.

### Repository gates

CI receives a dedicated matrix entry for the endpoint and its tests. It runs Groovy parse checks, the offline suite, credential and internal-reference scans, network-call guards, namespace-neutral JAX-RS checks, and a Jira and Structure typecheck against the actual test-instance JARs.

The implementation must first prove the exact generator mutation, cluster lock, journal persistence, and recalculation APIs against the installed Structure, Jira, and ScriptRunner versions. Unsupported capabilities remain blocked in the product instead of being guessed.

## Performance constraints

Snapshot construction performs one linear forest traversal and batches supporting reads. It must not execute a Jira or Structure lookup per row.

The initial analysis identifies groups and provenance. Full impact simulation is lazy and starts only after an administrator selects a duplicate group and an occurrence to retain. One duplicate group is applied at a time.

## Acceptance criteria

1. The endpoint and tests live under `jira/`; development helpers remain under `tools/`.
2. A Structure can be analyzed for duplicates without a work-item key.
3. Every duplicate group shows complete occurrence provenance and paths.
4. A group is opt-in and requires exactly one retained occurrence.
5. Every proposal has an individual explanation and complete impact preview.
6. Unknown or unsupported impact blocks Apply.
7. Only fresh server-generated plans can mutate generator configuration.
8. Mutations are Structure-locked, idempotent, journaled, verified, and recoverable.
9. All endpoints are administrator-gated and permission-aware.
10. Offline, HTTP, security, integration, parse, hygiene, and real-classpath checks pass.
11. The actual recalculated forest, not the API write response, proves delivery.
