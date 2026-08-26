# Contributing

## The rules this file lives by

- **A failed read is never rendered as an empty result.** If a value could not be read, the
  report says so at that exact node. An empty section and a broken section must never look
  alike, in any output format.
- **A deep link is never guessed.** Every link shape carries its evidence in a comment at the
  method that builds it. If a shape cannot be evidenced against `actions.xml`, an action
  class, or a literal emitted by a shipped plugin, the node gets no link and names the
  navigation path instead.
- **No issue counting.** This report answers how a project is configured. Adding a JQL search
  changes what the endpoint is and what it costs to run.
- **No outbound call outside the transport section.** CI enforces this, and the enforcement is
  positional: application link use above the transport banner fails the build.
- **javax / jakarta neutral.** Neither namespace may be imported. The `Response` class is
  resolved at runtime in `Http`, and that is the only place that knows about it.

## One file, on purpose

The endpoint is a single Groovy file and stays one, however long it gets. Splitting it
into modules over ScriptRunner script roots, or assembling it from parts at build time,
have both been considered and both were declined.

The reason is what this endpoint is for. An administrator opens a customer instance they
do not own, pastes one file into the ScriptRunner console, and gets an answer. No script
root, no distribution across cluster nodes, no deploy rights, no build step. For an audit
tool you run on somebody else's system, that is half of what makes it usable.

So the file is long, and the structure lives inside it: banner comments mark the blocks,
and the Jira-free half is cut out by CI and unit tested. If a section grows unreadable,
make the section smaller. Do not make the file plural.

## Layout

Everything above the `END OF THE JIRA-FREE BLOCK` banner is free of Jira types. CI cuts that
block out of the endpoint and compiles it together with the test suite, so the suite always
exercises the shipped source. Anything you add there is testable offline; anything below it
is not.

## Before opening a pull request

    # parse check
    java -cp "$GROOVY_CP" groovy.ui.GroovyMain tools/parsecheck.groovy jira/jiraDCprojectConfig.groovy

CI runs the parse check, the offline suite, the credential scan and the outbound-call check.
None of that resolves a single Jira symbol, so before a release the file is additionally
compiled against a running instance's own classpath and run through the static type checker
against that same classpath. A change that only passes CI is not verified.
