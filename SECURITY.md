# Security

## What this endpoint does

It reads Jira configuration and renders it. The analysis writes nothing and contacts nothing
outside the instance it runs on, in every output format.

There is exactly one outbound path and one write path, and they are the same thing: the
optional Confluence page export. It runs only when an administrator opens it and presses the
button, it travels over a Jira application link that the administrator selects, and it writes
one page in one space that the administrator chooses.

## Access

The endpoint is restricted to `jira-administrators`, enforced by ScriptRunner rather than by
the script. The report shows configuration, which in an administrator's hands is not
sensitive in itself, but it does name groups, users and roles, so the generated Confluence
page inherits the permissions of the space it is written into. Choose that space accordingly.

## What the export cannot do

- It cannot overwrite a page it did not create. Only a page carrying the export marker is
  updated.
- It cannot lose a remark. The Remark column is read back before anything is written, and a
  read that fails stops the write entirely.
- It cannot silently write somewhere else. The target application link, the space and the
  parent page are all chosen by the administrator in the page, one stage at a time.

## Reporting a vulnerability

Write to security@cfcon.org. Please do not open a public issue for a security problem.
