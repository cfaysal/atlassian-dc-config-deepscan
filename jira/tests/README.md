# Offline tests for jiraDCprojectConfig

The endpoint needs Jira classes and cannot run outside an instance. About half of what it
does, however, is product-independent: escaping and formatting, the deep-link builders, the
node and report arithmetic, the whole HTML and CSV rendering, and the entire Confluence page
export including the remark parser, the write gate and the row budget. This suite tests
exactly those, with no Jira on the classpath and no running instance.

The classes free of every Jira type are `Pc`, `Http`, `Dl`, `Nd`, `Report`, `Render`, `Cx`,
`RemarkRead` and `ExportOutcome`. They are the block between `class Pc {` and the
`END OF THE JIRA-FREE BLOCK` banner in the endpoint file.

## What is covered

### The one distinction the whole report rests on

An empty section and a section that could not be read must never look alike, in any output
format. The suite carries a **control implementation** of the naive renderer that cannot
tell them apart, and asserts on every run that the control collapses the two cases while the
shipped renderer keeps them separate. A suite that has never been red proves nothing, so the
discriminating power is measured rather than assumed.

Alongside it: a measured absence is not a failed read, and an observation is not a failure.
Those are three different states with three different renderings, and the third exists
because mixing observations into the diagnostics box once made the report announce suppressed
reads that never happened.

### The deep links

Every link shape the endpoint can emit is asserted against the exact string its evidence
says it produces. A builder handed a null id returns null rather than a broken URL, so a node
without an id ends up with no link and a navigation path in words, which is the rule the
whole file is written to.

### The remark column, which is the only thing on the exported page that is not reproducible

The Confluence export carries a **Remark** column that belongs to the administrator. It has
to survive every later run of the export, so the parser gets the most attention here.

- The three distinguishable outcomes `NONE`, `PARSED` and `FAILED`. `PARSED` with zero
  remarks is a legitimate result for a page nobody has annotated. `NONE` and `PARSED` allow
  a write, `FAILED` never does.
- Columns are located by their header name, never by position, so an inserted column does
  not orphan an administrator's notes.
- A page that does not carry the export marker is `FAILED`, whatever its title, so the
  export can never overwrite a page it did not create.
- Cell content survives verbatim, including nested markup and HTML entities.
- Two sibling nodes can carry the same label, and the path is the carry-over key. A repeated
  path therefore gets an ordinal. Without it one administrator's remark was stamped onto
  every row sharing the path, and on the run after that the parser found the same path twice
  with text in both and refused to write anything, ever again. Fail-closed, so nothing was
  lost, but the export bricked itself two runs after the first remark.
- A table pasted **into** a remark cell is refused rather than obeyed. The read works on the
  page markup, and markup nesting is the one thing a regular expression cannot see: the
  enclosing table ended early and every remark below the pasted table was dropped and
  re-seeded. The test builds that page out of the real export output and puts a remark below
  the pasted table, so the loss it prevents is a measured one.
- The path a node produces is identical in the tree, the CSV and the export. A carry-over key
  that differs between channels is a remark that cannot be found again.

### The collapsed page, and what collapsing must not change

Each section table sits inside Confluence's bundled Expand macro. The tests assert one macro
per section, that no table is left outside a macro body, that a section name carrying markup
cannot break out of the macro parameter, and that a remark written into each of two collapsed
tables comes back out of both.

One trap is kept as its own case: with one table per section, a report with no sections
produces no table at all, and a page with no table cannot be read back, which fails closed
and refuses every later write. An empty report would have bricked its own export. The empty
case writes the header row alone.

### The row budget

The exported page is capped. The cap is a budget shared between the sections rather than
spent in section order, and the tests cover the share itself: a budget nobody exhausts is
handed out in full, what a small section does not want reaches a greedy one, two equally
greedy sections split it evenly, and a zero budget offers nobody rows there are none of.

The case that gave the rule its reason is kept: a section that wants more than the whole
budget, followed by a small one. Spent in order, the small section received nothing at all,
not even a heading, and a reader could not tell that from a project that has no such
configuration. Both sections are now on the page, and the one that was cut says so on its
heading, above its table, and in the notice at the top.

### One sentence about four hundred items

A note that is true of one item is a finding. The same sentence repeated for every custom
field on the instance is a property of the instance, and a real run produced 858 observations
of which almost all were the same line. The suite builds a report with four hundred nodes
carrying one shared note plus a single distinct one, and asserts the card renders one line
per distinct text with a multiplier and example paths, that the total and the distinct count
are both named, and that the single finding is not buried. The count is measured **inside the
rendered card**, because the note is deliberately also written at each of the four hundred
items and counting the whole page would measure that instead.

### Everything else

- HTML escaping, CSV quoting, URL path and query encoding, base URL trimming.
- Query parameter evaluation, including the default taken when the input is garbage.
- The JAX-RS response builder, driven against a fake response class, because the real one is
  resolved by name at runtime and neither namespace may be imported.
- Node behaviour and tree arithmetic: node counts, unreadable counts, unlinked counts,
  descendant counts, and the id deduplication that falls back to the label.
- Long values are clamped and marked as shortened, never cut in silence.
- The project picker, its count line and its search.
- The write gate: every refusal path returns a reason and writes nothing.

## What is not covered

Nothing that needs a running instance. That means the scan against Jira itself, every Jira
API call in it, the application link call that writes the Confluence page, the space and
parent page lookups, the permission gate and both HTTP entry points.

This is the larger half of the endpoint. Test against a real instance before trusting a
change to any of it: a parse check and a green suite do not resolve a single Jira symbol.

## Requirements

A JDK and a Groovy 3 jar. Nothing else, no Maven build, no Jira, no ScriptRunner.

Download the jar from Maven Central if you do not already have one, for example
`groovy-3.0.21.jar` from `org/codehaus/groovy/groovy/3.0.21/`.

## Running the suite

The Jira-free classes are cut out of the endpoint file, so the tests always run against the
shipped source rather than a copy that can drift. The boundaries are derived with `grep`
rather than hard-coded, because the file keeps growing.

```bash
cd jira/tests

F=../jiraDCprojectConfig.groovy
START=$(grep -n '^class Pc {' "$F" | cut -d: -f1)
END=$(( $(grep -n '^ \* END OF THE JIRA-FREE BLOCK$' "$F" | cut -d: -f1) - 2 ))

{ echo 'import groovy.json.JsonOutput'
  echo 'import org.codehaus.groovy.runtime.InvokerHelper'
  echo 'import java.time.ZonedDateTime'
  echo 'import java.time.format.DateTimeFormatter'
  sed -n "${START},${END}p" "$F"
  cat jiraDCprojectConfig.tests.groovy
} > /tmp/testsuite.groovy

GROOVY=/path/to/groovy-3.0.21.jar
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/testsuite.groovy
```

A green run ends with `ALL TESTS PASSED`.

## Parse check

The parse check compiles the whole endpoint file to the CONVERSION phase. That validates
syntax without resolving any Jira symbol, so it runs anywhere:

```bash
java -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain \
     ../../tools/parsecheck.groovy ../jiraDCprojectConfig.groovy
```

A green run prints `PARSE OK` followed by the parsed class names.

Note what this proves and what it does not. The parse check finds syntax errors. It does not
resolve symbols, so a misspelled Jira method name passes it and still fails inside the
instance.

## Compiling against a real instance

Neither the suite nor the parse check resolves a Jira symbol, so before a release the file is
compiled against a running instance's own classpath and run through the Groovy static type
checker against that same classpath. On the reference instance the type check reports exactly
two errors, both of them the `projectConfig(Map, Closure)` entry point that
`CustomEndpointDelegate` provides through `methodMissing` and that no static type checker can
see. More than those two is a real finding.

## Last recorded run

2026-08-27: 353 assertions green, parse check green, compiled clean against Jira 11.3.8 with
ScriptRunner 10.14.0, static type check at the expected two entry-point errors.
