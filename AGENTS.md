# Agent contributions

- Preserve the configured Git author. Do not replace the user's name or email with an agent identity.
- If GitHub rejects a private email address, use the same user's verified GitHub noreply address from existing repository history. Do not invent an address or numeric user ID.
- For commits containing work generated or edited by Codex, add this Git trailer:

  ```text
  Co-authored-by: Codex <codex@openai.com>
  ```

- Apply the trailer to each applicable commit, including when creating a squash commit.
- Do not claim that a commit was signed or produced by a particular model unless verified.
- Keep existing endpoints unchanged when adding a separate tool unless the task explicitly includes them.
