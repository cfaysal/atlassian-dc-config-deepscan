# Atlassian Data Center Project Configuration

A ScriptRunner REST endpoint that answers one question about a Jira Data Center project:
**how is this project actually configured, all the way down?**

Pick a project, press OK, and the report expands every configuration item of that project
down to its own inner configuration. Not "this project uses screen scheme X" but the whole
tree underneath it: issue type, operation, screen, tab, field. Every node carries a deep
link to the exact administration screen where it is maintained.

| Script | Platform | Version |
| --- | --- | --- |
| [`jira/jiraDCprojectConfig.groovy`](jira/jiraDCprojectConfig.groovy) | Jira Data Center | 0.1 |

Typical uses: handing a project over to a new administrator, documenting a project before a
migration, finding out why two projects behave differently, or producing the configuration
appendix of an audit.

## What it reports

Every section is expanded, not summarised:

- **Issue type scheme** with its issue types and the default one.
- **Issue type screen scheme**, per issue type, per operation, down to the screen, its tabs
  and the fields on each tab.
- **Field configuration scheme**, per issue type, down to whether each field is required,
  hidden and which renderer it uses. Issue types that resolve to the same configuration are
  grouped under it instead of repeating it.
- **Custom fields** in scope for the project, with the contexts that actually apply to it,
  their issue types, their option lists and their defaults.
- **Workflow scheme** with every layer, the default layer included, and for each layer the
  workflow, its statuses, and per status every transition with its target, its counts of
  conditions, validators and post functions, and its transition screen.
- **Permission scheme**, grouped by permission, down to the resolved grant.
- **Notification scheme**, grouped by event, down to the resolved recipient.
- **Issue security scheme** with every level and who holds it.
- **Project roles** with their actors, **versions** and **components**.
- **Jira Service Management**, on a service project and only on one: the customer portal,
  every request type with the issue type it raises, its portal groups and the fields of its
  customer form, the queues with the filter that defines each one, and the SLA time metrics.
  A project that is not a service project gets no such section rather than an empty one, and
  on an instance without the app the section says so instead of being empty.

Output is HTML by default, and also JSON and CSV. The CSV is the flattened tree, one row per
node, which is the shape that goes into a spreadsheet or an audit record.

## Properties

These are the reason the output is worth trusting.

- **Read-only.** The analysis never writes. The single exception is the Confluence page
  export, which runs only when an administrator explicitly asks for it.
- **No issue counting.** This report answers how a project is configured, never how much
  data it holds. There is no issue search and no JQL, so a run is cheap enough to be
  harmless on a production instance and needs no time budget.
- **No outbound network call during analysis.** Producing a report contacts nothing outside
  your instance, in every format. The only outbound path in the file is the Confluence
  export, and CI proves that it stays inside its own section rather than trusting it to.
- **Admin-gated**, restricted to `jira-administrators` and enforced by ScriptRunner, not by
  the script.
- **A failed read is never rendered as an empty result.** A node with no children and a node
  whose children could not be read look different in the report, and the reason travels with
  the node. An empty section and a broken section never look alike.
- **A deep link is never guessed.** Every link shape in the file is backed by primary
  evidence, recorded at the method that builds it. The one shape that could not be evidenced
  carries no link and says in words where to find the item instead.
- **Grants are resolved through Jira's own scheme type registry**, not through a table of
  type strings kept in the script. What the registry cannot name keeps its raw type and
  parameter: an unresolved id is still true, an invented name would not be.

## Requirements

- Adaptavist ScriptRunner, licensed and installed.
- A member of `jira-administrators`.
- Jira Data Center. The file is **javax / jakarta neutral**: the JAX-RS `Response` class is
  resolved at runtime, so the same file runs on ScriptRunner 8.x and 9.x, which use
  `javax.ws.rs.*`, and on 10.x and above, which use `jakarta.ws.rs.*`.

## Installation

1. ScriptRunner, **REST Endpoints**, **Add New**, **Custom endpoint**.
2. Paste the contents of `jira/jiraDCprojectConfig.groovy`.
3. Restrict it to `jira-administrators`.
4. Open `/rest/scriptrunner/latest/custom/projectConfig`.

Without a `project` parameter you get the project picker. Choosing a project takes you to a
bookmarkable URL.

## Parameters

| Parameter | Default | Effect |
| --- | --- | --- |
| `project` | none | Project key. Without it, the picker is rendered. |
| `format` | `html` | `html`, `json` or `csv`. |
| `depth` | `full` | `top` collapses everything below the first level of each section. |
| `includeInactive` | `true` | Set to `false` to leave out released and archived versions. |
| `numbers` | `de` | Thousands separator style. |

## Deep links, and where they come from

Every link shape was read out of Jira itself on 2026-08-26, not out of documentation and not
out of memory. Two sources, both named at the method that uses them:

- `actions.xml` from `jira-core` gives the path segment, and the URL parameters of an XWork
  action **are** the setters of its action class, so `javap` on that class is the parameter
  evidence.
- For the project administration pages, the literal URL emitted by the shipped
  `jira-admin-project-config-plugin` of a running instance. A literal that Jira itself emits
  outranks any documentation page.

One shape could not be evidenced: addressing a single issue type scheme. No parameter for
`ManageIssueTypeSchemes` appears anywhere in `jira-core` or in the shipped plugins, so that
node carries no link and names the navigation path instead.

## Export to Confluence

The report can write itself into a Confluence page and update that same page on every later
run. Confluence is a separate instance from Jira, so this travels over a Jira application
link, which is the only outbound path in the file.

- Nothing is read from Confluence until the export button is pressed. Rendering the report
  performs no lookup at all.
- The export is staged: the click lists the Confluence application links, choosing a target
  loads that target's spaces, choosing a space opens the parent page search, and only then
  can a page be written.
- **The Remark column belongs to the administrator.** It is read back from the existing page
  and carried over verbatim. If that read fails for any reason, nothing is written at all.
- A page that does not carry this export's marker is never overwritten, whatever its title.
- A remark whose configuration item has disappeared is kept in a second table rather than
  dropped.
- Each section gets its own table inside Confluence's bundled Expand macro, so the page opens
  closed rather than as one wall of rows. Confluence indexes the body of an expand, so the
  page stays searchable while it is collapsed, and the remark read is unaffected: it scans
  every table on the page, whatever is wrapped around it.

## Verification

The offline test suite is compiled together with the Jira-free classes cut out of the
endpoint itself, so it always tests the shipped source rather than a copy that can drift.
CI runs a parse check, that suite, a credential scan, and a check that application links are
used only inside the declared transport section.

What CI cannot do is resolve a Jira symbol. Before release the file is additionally compiled
against a running instance's own classpath, and run through the static type checker against
that same classpath.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
