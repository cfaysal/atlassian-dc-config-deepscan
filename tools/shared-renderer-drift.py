#!/usr/bin/env python3
"""Assert that the renderers have not drifted between the shipped endpoints.

Both endpoints must stay single, self-contained files: each one is dropped into a
ScriptRunner script root on its own. That rules out the usual answer, a shared
module, so the renderers are duplicated on purpose. What was missing is the guard
that keeps the duplication honest.

TWO renderers are compared, not one. The table view of the HTML report was already
here. The EXPORT renderer - the one that writes the Confluence page and reads the
administrator remarks back off it - was not covered at all, and that is exactly how
one half could be modernised under OP-1003 and OP-1006 while the other kept the
flattened layout for months without a single check saying so.

The gap is currently WIDER than it was, not narrower. OP-1005 moved the Confluence
export a second step: each member now gets a heading of its own and its name is no
longer a column, which is what the table view has always done. The Jira export is
still on the flattened Path layout, so every declaration below names OP-1009, the
ticket that closes the gap. Four helpers exist on the Confluence side only for the
same reason. They are declared rather than left unlisted: a function nobody named
is a function this gate says nothing about, and silence reads like agreement.

Compared is executable code only. Comments are deliberately excluded: they may and
should differ per product - the Jira file names request types as its example, the
Confluence file does not - and a check that forbade that would produce worse
comments, not better code.

A difference is allowed only when it is declared: as a line pair in ALLOWED, or as
a whole function in DECLARED. Every declaration carries a ticket, a date and a
reason, and every one of them is COUNTED AND PRINTED on a passing run. A silent
exception is worse than no check.

A declaration is self-retiring. When a DECLARED function stops differing - because
the other endpoint caught up - the run FAILS and says to move it into the strict
list. That is what keeps this from decaying into a list of things nobody compares.
"""

import io
import os
import re
import sys
import difflib

ENDPOINTS = [
    ("jira", "jira/jiraDCprojectConfig.groovy"),
    ("confluence", "confluence/confluenceDCspaceConfig.groovy"),
]

# The functions that together render the TABLE VIEW of the HTML report. A function
# added to it belongs in this list, or it is outside the guard without anyone
# saying so.
TABLE_SHARED = [
    "shouldSplit",
    "emitTables",
    "recordOpen",
    "tableHeading",
    "subTable",
    "levelHeader",
    "collectRows",
    "humanKind",
    "camelWords",
]

# The functions that write the exported page and read it back. The reading half is
# the dangerous one: it is the only path in either tool that can destroy text a
# human typed, so it is compared character for character.
EXPORT_SHARED = [
    "linkCell",
    "shareBudget",
    "headerRow",
    "cell",
    "esc",
    "expandOpen",
    "expandClose",
    "headerIndex",
    "cellsOf",
    "plainText",
    "isEmptyCell",
    "isRemarkSeed",
    "hasNestedTableBody",
    "errorDetail",
]

# Intentional differences, each visible and reasoned. A pair is (jira line,
# other line). Keep this list short: every entry is a place the two products
# genuinely diverge, and a long list means the guard has stopped guarding.
ALLOWED = [
    (
        'out.append("<th class=\\"col-link\\">In Jira</th>")',
        'out.append("<th class=\\"col-link\\">In Confluence</th>")',
        "the link column names the product it links into",
    ),
]

# Whole functions that are known to differ, or to exist on one side only. Each is
# still compared: if it stops differing, this run FAILS and asks for it to be moved
# into EXPORT_SHARED, so a declaration cannot outlive its reason.
#
# (function, ticket, date, reason)
DECLARED = [
    ("render", "OP-1009", "2026-08-28",
     "a heading and a table per member here, one flat Path table there; the "
     "member's label, count and link go on its heading; plus the property value "
     "gate, which the Jira report has no counterpart for"),
    ("flatten", "OP-1009", "2026-08-28",
     "carries the level trail and emits no row for a readable container, where the "
     "Jira export emits a row per node and a breadcrumb string"),
    ("rowTable", "OP-1009", "2026-08-28",
     "skips the leading levels the heading accounts for, so the member is no column "
     "at all, against Path plus Item there; and the value cell goes through the "
     "property value gate"),
    ("makePathsUnique", "OP-1009", "2026-08-28",
     "carries the ordinal onto the last level label too, because the reader joins "
     "the level cells back into the key"),
    ("parseRemarks", "OP-1009", "2026-08-28",
     "reads all THREE layouts - Path breadcrumb, member as leading column, member "
     "on the heading - where the Jira reader knows only the first"),
    ("normLabel", "OP-1009", "2026-08-28",
     "writer and reader of the level layout must normalise a label the same way, "
     "or a remark is orphaned on every run"),
    ("pathOf", "OP-1009", "2026-08-28",
     "the identity out of the level labels, and what the reader rebuilds it with"),
    ("levelColumnCount", "OP-1009", "2026-08-28",
     "how many leading columns of a table are level columns"),
    ("identityOfRow", "OP-1009", "2026-08-28",
     "one row of a level layout joined back into its identity, with the leading "
     "segment its heading carries prepended"),
    ("exportLevelHeader", "OP-1009", "2026-08-28",
     "the header of one level column, out of the kinds on that level"),
    ("memberHeading", "OP-1009", "2026-08-28",
     "the heading a member's table sits under: label, then what the table carries. "
     "The Jira export has no per-member heading to build"),
    ("memberLabelOf", "OP-1009", "2026-08-28",
     "the inverse of memberHeading, and the only place the leading segment of an "
     "identity comes from once the member is no longer a column"),
    ("expandHeadings", "OP-1009", "2026-08-28",
     "every heading on the page with its offset. Matched on the expand macro by "
     "NAME, because the remark seed is a status macro with a title of its own"),
    ("headingAbove", "OP-1009", "2026-08-28",
     "which heading a table sits under, which is what tells the reader the member "
     "and therefore the leading segment of every identity in it"),
    ("stateText", None, "2026-08-28",
     "no layout difference and not on OP-1009: this report has a REDACTED state, "
     "for a property value withheld on purpose, which the Jira report has not"),
    ("valueText", None, "2026-08-28",
     "permanent unless Jira grows the same gate: a property value may not reach "
     "the page unless the run declared values=true"),
    ("isPropertyValue", None, "2026-08-28",
     "the node kind the property value gate applies to"),
]


def strip_comments(lines):
    """Executable lines only, whitespace normalised."""
    out = []
    in_block = False
    for raw in lines:
        line = raw.strip()
        if in_block:
            if "*/" in line:
                in_block = False
            continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block = True
            continue
        if line.startswith("//") or line.startswith("*"):
            continue
        line = re.sub(r"\s+", " ", line)
        if line:
            out.append(line)
    return out


def extract(path, name):
    """The body of one method, found by signature and closed by brace balance."""
    src = io.open(path, encoding="utf-8").read().split("\n")
    start = None
    for index, line in enumerate(src):
        if re.search(r"(static|private static).*\b%s\(" % re.escape(name), line):
            start = index
            break
    if start is None:
        return None
    depth = 0
    body = []
    for line in src[start:]:
        body.append(line)
        depth += line.count("{") - line.count("}")
        if depth == 0 and len(body) > 1:
            break
    return strip_comments(body)


def allowed_pair(removed, added):
    for left, right, _ in ALLOWED:
        if removed == left and added == right:
            return True
    return False


def body_diff(left, right):
    """The changed lines of one function body, or an empty list when identical."""
    return [
        line for line in difflib.unified_diff(left, right, lineterm="", n=0)
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    ]


def compare_strict(func, base, other, base_path, other_path):
    """One function that has to be identical. Returns 1 when it is not."""
    base_name, left = base
    other_name, right = other
    if left is None or right is None:
        where = base_name if left is None else other_name
        print("::error::%s() is missing from the %s endpoint. The renderer is shared "
              "by copy, so a function present in one and absent from the other is "
              "drift, not a difference." % (func, where))
        return 1

    diff = body_diff(left, right)
    if not diff:
        return 0

    removed = [line[1:] for line in diff if line.startswith("-")]
    added = [line[1:] for line in diff if line.startswith("+")]
    if len(removed) == len(added):
        unexplained = [(w, n) for w, n in zip(removed, added) if not allowed_pair(w, n)]
    else:
        unexplained = [(w, "") for w in removed] + [("", n) for n in added]
    if not unexplained:
        return 0

    print("::error file=%s::%s() differs from the %s endpoint beyond the declared "
          "exceptions. A change to the renderer has to reach both files."
          % (other_path, func, base_name))
    for was, now in unexplained:
        if was:
            print("    %s only: %s" % (base_name, was))
        if now:
            print("    %s only: %s" % (other_name, now))
    return 1


def check_declared(base, other, base_path, other_path):
    """Every declared exception, verified to still BE one. Returns the failure count."""
    base_name, base_src = base
    other_name, other_src = other
    failures = 0
    for func, ticket, date, reason in DECLARED:
        left = extract(base_src, func)
        right = extract(other_src, func)
        if left is None and right is None:
            print("::error::%s() is declared as an exception in "
                  "tools/shared-renderer-drift.py but exists in neither endpoint. "
                  "Delete the declaration or fix the name." % func)
            failures += 1
            continue
        if left is not None and right is not None and not body_diff(left, right):
            print("::error file=%s::%s() no longer differs between the endpoints, so "
                  "the declared exception (%s) has outlived its reason. Move it into "
                  "EXPORT_SHARED so it is compared strictly from now on."
                  % (other_path, func, ticket or "no ticket"))
            failures += 1
            continue
        where = "differs"
        if left is None:
            where = "%s only" % other_name
        elif right is None:
            where = "%s only" % base_name
        print("  %-8s %s  %-19s %-16s %s"
              % (ticket or "-", date, func + "()", where, reason))
    return failures


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    missing = [p for _, p in ENDPOINTS if not os.path.exists(os.path.join(root, p))]
    if missing:
        # FAIL CLOSED. Both endpoints exist, so a missing one is a rename or a move,
        # and this branch used to answer it with a notice and a zero. A GitHub
        # Actions notice does not fail a run: renaming an endpoint therefore
        # disarmed the only runtime-independent gate this repository has, and CI
        # went on reporting green while the two renderers drifted apart. A check
        # that cannot find what it compares did not run, and a check that did not
        # run is not a passing check.
        for relative in missing:
            print("::error::%s is not in the repository. This gate compares the "
                  "renderers of the shipped endpoints against each other, so an "
                  "endpoint it cannot find is a comparison that did not happen. "
                  "Restore the file, or correct its path in the ENDPOINTS list of "
                  "tools/shared-renderer-drift.py."
                  % os.path.join(root, relative))
        return 1

    base_name, base_rel = ENDPOINTS[0]
    base_path = os.path.join(root, base_rel)
    failures = 0
    strict = TABLE_SHARED + EXPORT_SHARED

    for other_name, other_rel in ENDPOINTS[1:]:
        other_path = os.path.join(root, other_rel)
        for func in strict:
            failures += compare_strict(
                func,
                (base_name, extract(base_path, func)),
                (other_name, extract(other_path, func)),
                base_rel, other_rel)

        print("Declared exceptions, each still verified to be one:")
        failures += check_declared(
            (base_name, base_path), (other_name, other_path), base_rel, other_rel)

    if failures:
        print("\n%d renderer function(s) drifted. Either apply the change to both "
              "files, or declare the difference in tools/shared-renderer-drift.py "
              "with a ticket, a date and a reason." % failures)
        return 1

    print("\nTable renderer: %d function(s) compared." % len(TABLE_SHARED))
    print("Export renderer: %d function(s) compared." % len(EXPORT_SHARED))
    print("Renderer identical across %d endpoint(s), %d function(s) compared, "
          "%d declared exception(s)."
          % (len(ENDPOINTS), len(strict), len(ALLOWED) + len(DECLARED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
