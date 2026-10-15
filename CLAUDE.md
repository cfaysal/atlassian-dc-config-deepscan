# CLAUDE.md

## CI and GitHub Actions

- Do not put `[skip ci]` in commit messages. It is no longer used in this repository.
- Do not write it into a commit message as text either, not even to describe this rule.
  GitHub skips every workflow when the commit message contains `[skip ci]`, `[ci skip]`,
  `[no ci]`, `[skip actions]` or `[actions skip]` anywhere, or ends with a
  `skip-checks: true` trailer. Name the rule in words, for example "the CI skip marker".
- This repository is public. GitHub Actions are free for public repositories, so CI may run
  on every push and pull request.
- Only selected private repositories have a GitHub Actions budget to watch. In a private
  repository, ask before relying on or triggering CI runs.
