# Agent contributions

- Preserve the configured Git author. Do not replace the user's name or email with an agent identity.
- If GitHub rejects a private email address, use the same user's verified GitHub noreply address from existing repository history. Do not invent an address or numeric user ID.
- Only when Codex is executing inside a managed Codex cloud session, add this Git trailer to commits containing its work:

  ```text
  Co-authored-by: Codex <codex@openai.com>
  ```

- This rule does not apply to Codex running locally in the desktop app, CLI or IDE, or to Claude and other agents. They must not add the Codex trailer under this rule.
- Determine cloud execution from explicit session/runtime context identifying a managed Codex cloud environment. Do not infer it from the agent name, repository location, available network access or the presence of this file. If cloud execution cannot be confirmed, omit the trailer.
- Within confirmed Codex cloud sessions, apply the trailer to each applicable commit, including squash commits created there. Do not copy this trailer into a new local/desktop commit merely because it appeared in earlier commit messages.
- Do not rewrite existing published commits to change attribution, and leave unrelated attribution rules and other agents' trailers unchanged.
- Do not claim that a commit was signed or produced by a particular model unless verified.
- Keep existing endpoints unchanged when adding a separate tool unless the task explicitly includes them.
