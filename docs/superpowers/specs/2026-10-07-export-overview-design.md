# Persistent export overview (OP-1475)

The Jira `projectConfig` and Confluence `spaceConfig` endpoints retain their existing detail-page exports. Selecting or creating a parent also makes that page the persistent overview. An export without a parent keeps its current behavior.

The selected parent receives one managed table per product. Its first column links the project or space name to the detail page. Section columns link to invisible Anchor macros immediately before the existing Expand macros. Counts come from the detail renderer, including its truncation wording. Jira columns use section kinds rather than project-specific scheme names.

Rows are matched by the linked export page title, with page ID as an additional identity check. A replacement page with the same title updates the existing row; ambiguous matches are refused. Creating another detail page appends a row; regenerating the same page replaces its row in place. Additional section kinds add columns without losing older rows. Native Confluence page links resolve section anchors; URLs and title-derived fragment identifiers are not guessed.

Invisible Anchor macros identify the managed table, columns and rows. Parent content outside that region is preserved verbatim. Invalid, incomplete or duplicate markers, malformed rows and a detail export used as an overview are refused. Existing unmarked parent content may receive a new managed table. Product markers let Jira and Confluence tables coexist on the same parent.

The overview update follows the successful detail export. It reads fresh parent identity, body and version, merges the row, saves a new version, then reads back the actual row. Conflicts are reported rather than blindly retried. The response distinguishes skipped, updated, failed and unknown overview outcomes. A failed or unconfirmed overview never becomes a claim that no detail page was written.

Both endpoints keep their existing authentication, administrator gates, Remark parser, property-value guards, row budget and single-file installation. The shared pure helper classes are embedded identically in both deployable scripts and tested for parity. No unrelated code is refactored.

Alternatives considered: a separate overview selector adds another destination decision; a page-properties report does not maintain the requested ordinary table. Reusing the selected parent is the smallest extension of the existing workflow.

Offline acceptance covers append, repeat, changed columns, anchors, counts, escaping, malformed content, editor normalization, conflicts, readback and partial success, plus all existing suites. Live application-link authentication, Confluence editor rendering, section navigation and page-save concurrency require an authorized instance test and are not inferred from offline results.

Primary references: [Anchor macro](https://confluence.atlassian.com/doc/anchor-macro-182682072.html), [storage format links and tables](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html), [versioned REST updates](https://developer.atlassian.com/server/confluence/expansions-in-the-rest-api/).
