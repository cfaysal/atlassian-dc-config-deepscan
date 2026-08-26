# Changelog

All notable changes to this project are documented here. The version of the endpoint is
declared once, as `Pc.VERSION`, and printed by every output channel.

## 0.1 - unreleased

First working version.

### Added

- Project picker and a bookmarkable per-project report at
  `/rest/scriptrunner/latest/custom/projectConfig`.
- Full recursive scan of a project: issue type scheme, issue type screen scheme down to
  screen, tab and field, field configuration scheme down to per-field behaviour, custom
  field contexts with options and defaults, workflow scheme with every layer and each
  workflow's statuses and transitions, permission, notification and issue security schemes
  down to the resolved grant, project roles with actors, versions and components.
- Priority scheme of the project with its priorities in picker order and the default for new
  issues, and the project properties apps have written, which no administration screen shows.
- The details a project screen does not put in one place: project email, avatar, creation and
  last change with who made it, and whether the project lead still holds a licensed
  application role.
- Per configuration item, what was missing before: custom field default values per context,
  the default issue security level of the project, an unpublished workflow scheme draft with
  who left it and when, workflow status and transition properties, tab and field positions on
  a screen, renderer names instead of plugin keys, custom events told apart from Jira's own,
  and the members of a group a scheme grants to.
- Jira Service Management on a service project: the customer portal, every request type
  with the issue type it raises, its portal groups and the fields of its customer form, the
  queues with the filter that defines each one, and the SLA time metrics. The section is
  left out entirely on a project that is not a service project, and on an instance without
  the app installed it says so rather than being empty. Every Service Management type is
  reached by name, so this file still loads where the app was never installed.
- HTML, JSON and CSV output. The HTML page carries a tree view and a flat table view of the
  same data and switches between them without another request.
- Confluence page export over a Jira application link, staged behind its button, with a
  Remark column that belongs to the administrator and is carried over verbatim. If that
  read fails, nothing is written. Each section gets its own table inside the bundled Expand
  macro, so the page opens closed; the remark read is unaffected, because it scans every
  table on the page whatever is wrapped around it.
- Deep link per node, every link shape backed by primary evidence read out of a running
  instance. The one shape that could not be evidenced carries no link and says so.
- Offline test suite compiled together with the Jira-free classes cut out of the endpoint
  itself, plus CI: parse check, suite, credential scan, and a check that application links
  are used only inside the declared transport section.
