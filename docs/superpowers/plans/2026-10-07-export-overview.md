# Persistent export overview implementation plan

> Execute inline with one writer. Independent review follows implementation.

**Goal:** Add the OP-1475 overview to both configuration exporters while retaining the detail-page contract.

**Architecture:** Embed identical pure storage/coordination helpers in the existing standalone endpoint files. Capture section metadata while rendering each existing detail page, then maintain the selected parent through the endpoint's existing transport.

**Tech stack:** Groovy, Confluence storage XHTML and Anchor macros; authenticated application-link REST for Jira and the existing PageManager API for Confluence.

## 1. Baseline and failing tests

- [x] Isolate from the active Structure Doctor checkout in a separate worktree based on `origin/main`.
- [x] Create and independently read OP-1475 before repository edits.
- [x] Run the Confluence source-based baseline (513 checks).
- [x] Add a shared overview contract suite in `tools/tests/export-overview.tests.groovy`, invoked by both existing endpoint suites. Test two pages, repeat by name and ID, column growth, native section links, escaping, broken markers, preserved outside content and product separation.
- [x] Run both source-based suites and observe the expected feature-missing failure before implementing helpers.

## 2. Pure helpers and detail anchors

- [x] Add identical `ConfigOverview` and `OverviewExport` classes before the offline extraction boundary of both endpoints. Keep each helper focused and below 250 lines.
- [x] Add `sections` to `ExportOutcome`; collect kind-based anchors and renderer counts while preserving current section tables, Expand titles, Remarks and truncation notices.
- [x] Run the shared contract and existing renderer/Remark suites. Verify shared helper byte parity.

## 3. Existing transport integration

- [x] Confluence: read the selected parent, merge after successful detail save, clone its original state, save through PageManager and independently read back the row.
- [x] Jira: read parent by ID with storage/version/space, PUT only the versioned body/title and independently GET the managed row. Use the same authenticated factory and existing `confluenceCall` function.
- [x] Return and display `overview` outcome/reason while retaining detail success. Explain that the optional parent becomes an overview in both export forms and administrator guides.
- [x] Exercise coordination with in-memory transport callbacks: no parent, failed read, identity mismatch, write conflict, unconfirmed save, normalized readback and successful two-page/repeat flow.

## 4. Verification and delivery

- [x] Run both full offline suites, both endpoint parse checks and the shared-renderer drift gate.
- [x] Inspect source integration and scoped diff; obtain independent review and behavior-preserving simplifier feedback.
- [ ] Record results and live-test limitations on OP-1475; the prepared result comment awaits publication approval.
- [x] Commit only this workstream, with `OP-1475` at the start of the subject. Keep the branch/worktree while acceptance is pending.

The test runner extracts from `class Pc {` to the product's `END OF THE ...-FREE BLOCK` banner, prepends the imports used by CI and appends the real endpoint suite. Run with `groovy -DrepoRoot=<worktree> <generated-suite>`. Parse with `groovy tools/parsecheck.groovy <endpoint>`. Drift with `python3 tools/shared-renderer-drift.py`.

Live installation and public publication are outside this local implementation step. Verify actual page identities, two overview rows and section clicks on the target before calling the feature delivered there.

## Verified local result

The final source-derived suites passed with Groovy 3.0.21 and Java 17.0.20.1, matching the repository's Groovy version and Java major version: Jira 446 existing checks plus 56 overview checks; Confluence 513 existing checks plus 48 overview checks, 1,063 total. Both full endpoint parse checks passed. The shared-renderer drift gate passed, with its 18 existing documented exceptions. The publication/source checks passed across 67 Groovy files, and `git diff --check` passed.

Regression tests first reproduced the missing implementation, ambiguous row page targets, whitespace-sensitive title duplication and incorrect HTTP 500 refusal. The fixes passed the same source-derived contracts. Readback tests distinguish confirmed refusal, timeout after a persisted save and unconfirmed target effects. Independent review and the behavior-preserving simplifier were applied to the scoped changes.

The active primary checkout's unrelated Structure Doctor changes are outside this worktree. No installed endpoint has been changed. Live authentication, permission enforcement, editor normalization, section clicks and concurrent PageManager saves remain untested on an actual instance.

### Target acceptance procedure, not yet run

For each endpoint, select the same test-space parent and export two distinct reports. Read back the parent identity/version and confirm two rows with current counts. Click every populated section link and confirm the corresponding detail section. Edit a detail Remark, export that name again and confirm one updated row and preserved Remarks. Repeat with a replacement detail page under the same title and with a renamed existing page; confirm no duplicate. Verify parent notes and unrelated tables remain intact. Exercise denied parent updates and a concurrent parent edit, confirming detail success and an accurate separate overview outcome.
