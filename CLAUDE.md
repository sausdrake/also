# AutoAuction — Claude Code Working Agreement

Security-sensitive Java/Spring Boot auction platform (Clean/Hexagonal Architecture).
Stack: Java 17, Spring Boot 3.1.5, Spring Security (JWT), Spring Data JPA, PostgreSQL,
Flyway, WebSocket/STOMP, Bucket4j. Frontend: React/TypeScript under `client/`.

This file governs HOW Claude Code works in this repo. It overrides default behavior.

## 0. Non-negotiables

- Never commit without explicit user approval (see Commit Policy).
- Never run destructive git commands (`reset --hard`, `clean -fd`, `checkout -- <file>`,
  `stash` without permission, `push --force`) unless the user explicitly authorizes that
  exact action in that turn.
- Never touch `.env`, `docker-compose.yml`, or Flyway migration history without an
  explicit request to do so.
- Never modify unrelated files. The working tree has legitimate pending changes from
  other in-progress work — treat everything not related to the current task as
  off-limits.

## 1. Architecture Rules

- Layering is strict: `web (Controllers/DTOs) → application (Services/Use Cases) →
  domain (Entities/Ports) → infrastructure (JPA/Adapters)`.
- Domain must not depend on infrastructure or web. Ports live in `domain`, adapters
  implement them in `infrastructure`.
- Each business module (`admin`, `auction`, `auth`, `deposit`, `diagnostic`, `user`,
  `vehicle`) keeps this layering internally. Don't reach across modules' internals —
  go through their public ports/services.
- Don't introduce new architectural patterns (new module structure, new persistence
  strategy, new cross-cutting abstraction) without flagging it to the user first.

## 2. Security-Sensitive Areas

Treat these as security-sensitive: authentication, JWT issuance/validation,
authorization (`@PreAuthorize`, role checks), WebSocket/STOMP auth, deposits,
bidding, optimistic locking, and anything touching Flyway migrations.

- Never print JWT secrets, passwords, bcrypt hashes, or `.env` contents in output,
  logs, or reports — not even partially, not even for debugging.
- Prefer fail-closed behavior (deny by default) over fail-open.
- When changing an authorization boundary, write or point to a test that proves the
  boundary holds (e.g., a non-owner/non-admin request is rejected).
- Never weaken a security control (broaden a role check, disable a filter, skip auth)
  just to make a test or a feature pass. If a security control blocks a legitimate
  fix, stop and explain the conflict instead of loosening it.

## 3. Change Isolation

- Touch only the files required for the current task's scope. No opportunistic
  refactors, renames, formatting sweeps, or "while I'm here" cleanup.
- If you notice an unrelated issue, mention it at the end of your report instead of
  fixing it.
- Before editing, check `git status` for that file — if it already has pending
  changes unrelated to your task, read the diff first so you don't clobber it or
  misattribute lines to yourself.

## 4. Testing Discipline

- Run targeted tests for the changed class(es) first
  (`./mvnw -Dspring.profiles.active=test -Dtest=ClassNameTest test`).
- Tests require `JWT_SECRET` to be set in the environment for the `test` profile.
- Only run the full suite (`./mvnw -Dspring.profiles.active=test test`) when the
  change plausibly has cross-cutting impact, or the user asks for it.
- Never reduce test coverage or delete/skip a failing security test to get a green
  build.

## 5. Git / Commit Discipline

- Claude never stages or commits automatically. Commits happen only when the user
  explicitly asks, and only for the specific files relevant to the current task —
  stage by exact path, never `git add .` or `git add -A`.
- **Exception with no override:** `target/`, `.metals/`, `.bloop/`, `logs/`, `.env`,
  and other generated/build/runtime/secret paths are never committed, even if the
  user asks to commit "everything" or one of these appears staged — this
  prohibition is absolute and is not lifted by an explicit request, unlike the
  general rule above.
- Before proposing a commit: run `git diff --cached` on the exact staged files and
  confirm nothing unintended is included.
- **Never run `git commit` yourself.** Always stop and present the commit candidate
  (see Commit Policy) and wait for explicit approval.

## 6. Handling Pre-Existing Working-Tree Changes

The working tree already contains legitimate uncommitted work (e.g. JWT hardening
changes to `JwtService.java`, `application.properties`, test resources). These are
NOT yours to modify, revert, or fold into your own change unless the task explicitly
concerns them.

- `git status` at the start of any non-trivial task to record the baseline.
- If your task touches a file that already has pending changes, work on top of the
  current file content — never revert it to HEAD to get a "clean" diff.
- Never `git stash` these changes without explicit permission.

## 7. Database Migration Policy (Flyway / PostgreSQL)

- Flyway is the source of truth for the production schema. `ddl-auto` must never be
  relied on to silently shape production schema.
- Never write a migration by inferring columns/types/constraints from JPA entity
  annotations alone when the task requires matching an existing production schema —
  request or read authoritative schema evidence first (`pg_dump --schema-only`,
  `\d table_name`, or equivalent).
- Never invent production columns, types, indexes, constraints, or foreign keys.
- Never delete or renumber historical Flyway migrations without first checking
  `flyway_schema_history` implications, and only with explicit user approval.
- **Finding #3 (`ddl-auto=update` vs Flyway) is explicitly NOT ready for
  implementation.** The audit concluded migration work must happen first. Do not
  start implementing it even if it looks small — surface it as blocked instead.

## 8. STOP Conditions

Stop and ask before proceeding when:
- A plan would require production data/schema information that isn't available.
- A task would touch `.env`, `docker-compose.yml`, or delete/renumber Flyway
  migrations.
- A "fix" would require weakening an authZ/authN check to pass a test.
- The scope of a request is ambiguous enough that two reasonable interpretations
  would touch different files.
- You're about to run a destructive or irreversible git command.

## 9. Token Efficiency Policy

- Don't reread a file already inspected this session unless: it changed, the task
  now needs a different section of it, or prior evidence was insufficient.
- Search (Grep/Glob) before opening large files; prefer targeted line ranges over
  whole-file reads for files over ~200 lines.
- Don't re-derive the architecture or module layout from scratch each task — it's
  documented above and in `README.md`.
- Maintain a compact in-conversation task state as you go: confirmed facts, relevant
  files, decisions made, tests run, unresolved questions. Reuse it instead of
  rediscovering.
- If a prior audit/report is available (in conversation or memory), treat it as the
  starting point and verify only the claims relevant to the current task — don't
  redo the full audit.
- Don't run the full test suite after every small edit — targeted tests first.
- Match report length to the task: a one-file fix gets a few sentences, not a
  structured report.

## 10. Commit Policy

When a change is complete and verified, do not commit. Instead say exactly:

> **READY TO COMMIT — STOPPING BEFORE COMMIT**

and provide:
- Exact files changed (paths).
- Exact tests run and their results.
- Proposed commit message.
- Any known pre-existing failures (with evidence, see below) that are unrelated to
  this change.

Only run `git commit` if the user explicitly asks for it in that turn.

## 11. Pre-Existing Failure Policy

If a test fails:
- Determine whether it failed before your change by checking it against `HEAD` or
  the state before your edit (e.g., `git stash` only with permission, or reasoning
  from the diff) — don't assume.
- Never label a failure "pre-existing" without that evidence. If you can't establish
  a baseline, say so explicitly rather than guessing.
- Never modify unrelated code just to force the suite green.

## 12. Workflow for Non-Trivial Tasks

0. **Working tree protection** — `git status`; note pre-existing changes; never
   overwrite unrelated work; no destructive git commands without explicit ask.
1. **Scope** — state the exact finding/problem and the modules/files it touches.
   Don't read the whole repo unless the task genuinely requires it.
2. **Investigation** — search first, read only what's relevant, read in parallel
   where possible, keep a running note of discovered facts.
3. **Threat model / root cause** — separate confirmed facts from assumptions; note
   severity, dependencies, regression risk.
4. **Plan** — minimal implementation plan, exact files expected to change. STOP if
   it needs unavailable production data/schema info.
5. **Implementation** — scoped changes only, no opportunistic refactoring.
6. **Tests** — targeted first, then relevant integration tests, full suite only if
   justified; separate pre-existing failures from regressions (with evidence).
7. **Security / diff audit** — `git diff` on changed files only; check for secrets,
   SQL risk, authZ boundaries; `git diff --check`; confirm no unrelated files staged.
8. **Stop** — never auto-commit; report per the Commit Policy; wait for approval.
