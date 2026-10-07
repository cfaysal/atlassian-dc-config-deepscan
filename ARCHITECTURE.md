# Configuration page export architecture

This document covers the Jira `projectConfig` and Confluence `spaceConfig` page-export paths. Other endpoints have separate responsibilities described by their administrator guides.

Each endpoint remains a standalone ScriptRunner file. `Cx.render` builds the existing detail tables, Expand macros, Remark carry-over and truncation notices. It also collects section identities and counts and adds an invisible Anchor macro immediately before each section's Expand macro. `ExportOutcome.sections` passes those summaries to the overview updater.

`ConfigOverview` owns the managed table's storage format, identity parsing, title/ID matching and native page/anchor links. Start/end anchors delimit the managed region; column anchors hold stable section kinds and row anchors hold page IDs. Existing content outside the region is retained verbatim. Jira and Confluence have separate product markers, so both tables may coexist on one parent. New section kinds extend the columns without discarding older rows.

`OverviewExport.maintain` runs after successful detail export. Its read/write callbacks keep platform APIs out of the pure helper classes. It reads a fresh parent body and version, upserts by linked title with ID fallback, saves, then verifies the actual row and newer version at the target. Its outcome is independent of the detail export: `skipped`, `updated`, `failed` or `unknown`.

```mermaid
flowchart LR
    Report --> Detail[Cx detail renderer and anchors]
    Detail --> Save[Existing detail save]
    Save --> Merge[ConfigOverview title and ID upsert]
    Merge --> Parent[Versioned parent save]
    Parent --> Verify[Read back overview row]
    Save --> Result[Detail result]
    Verify --> Result
```

Jira retains its administrator gate and authenticated application-link factory. `confluenceOverviewRead` expands body storage, version and space through `confluenceCall`; `confluenceOverviewWrite` sends a versioned body update without changing ancestors. Confluence retains its administrator gate and existing PageService/PageManager APIs, cloning the original parent before saving with notifications suppressed. No new credentials, scopes or alternate transport are introduced.

Confluence's `PageExportTransaction` runs the persistence reads, typed-parent creation, detail save/move and overview update in one SAL unit of work. This follows [Atlassian's transaction guidance](https://developer.atlassian.com/server/confluence/hibernate-sessions-and-transaction-management-guidelines/): related manager mutations must share a transaction to avoid inconsistent Hibernate objects between calls. SAL uses `PROPAGATION_REQUIRED`, joining an existing transaction when present. Its types are resolved at runtime like the existing database adapter. The prepared HTTP response is returned only after `execute` completes. Error responses abort the unit of work; commit exceptions produce a transaction error rather than a successful detail/overview result. Pure overview parsing or verification can still report an independent problem when the transaction completes successfully; an ORM error that marks the transaction for rollback cannot retain a partial-success claim.

The overview adds names, section counts and links, not configuration property values. Detail property-value guards and Remark parsing remain in their existing locations. Page restrictions and transport permissions remain platform responsibilities; live behavior is verified on the target instance rather than inferred from offline tests.

The embedded pure helper classes are byte-identical in both endpoint files. `tools/tests/export-overview.tests.groovy` runs against each endpoint's actual extracted classes and checks their parity. `python3 tools/run-config-tests.py` runs the two source-based suites, endpoint parse checks and `tools/config-overview-typecheck.groovy`. The latter statically checks `ConfigOverview` and `OverviewExport` against their actual platform-free dependencies, loaded dynamically, and fails on diagnostics. CI runs the same static gate for both configuration endpoints. The Confluence transaction suite executes the actual export branch and statically checks its transaction adapter against documented API contract stubs. Its synthetic persistence lifecycle covers first-run overview creation, repeat exports, rollback and guards; it does not prove the particular Hibernate mechanism or live instance behavior. These checks do not replace instance-classpath checking of platform-dependent code. `python3 tools/shared-renderer-drift.py` checks existing shared renderer contracts.
