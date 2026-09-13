# Instructions for contributors and AI agents

Read MAINTENANCE.md and CHANGELOG.md before modifying this fork.

- Inspect git status, current branch, remotes and local diffs first. Preserve other
  developers' changes. Never reset, clean, force-push or discard work to synchronize.
- Work on a task branch based on jl/server-maintenance. The upstream PR branch
  jl/compat-1.21.11 must not receive server-only changes without explicit review.
- Documentation, compilation, commits and publication do not authorize production
  deployment, database edits, grave deletion, reloads or restarts.
- Keep secrets, real player data, databases, runtime configuration and binary
  artifacts out of Git. dependency-reduced-pom.xml is generated, not source.
- Preserve upstream Maven version policy. Identify custom builds by commit and
  SHA-256, not only the plugin version or filename.
- Use IntegrationManager for new integration lifecycle wiring. The existing
  BagOfGold listener initialization is documented technical debt, not a template.
- Use existing scheduler abstractions; respect location/entity thread ownership.
  Do not remove hologram tracking before physical entity cleanup can be resolved.
- Treat inventory, XP, physical money and death attribution as data-sensitive.
  Test for duplication and loss, including BetterRevive finalization.
- Make narrowly scoped commits and update CHANGELOG.md with actual validation.
  A successful build is not an in-game regression test. Never claim unrun tests.
- Publish only the intended branch without force. Production deployment requires
  explicit authorization, consistent backups, a rollback plan and maintenance time.
