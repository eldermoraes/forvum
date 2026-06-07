# Contributing to Forvum

Forvum is a local-first, open-source personal AI assistant on the JVM (Java 25, Quarkus,
LangChain4j, LangGraph4j), built to ship as a single native binary. It is in active design with
implementation following the milestone roadmap. Both **design** and **code** contributions are
welcome, and the project is small and early — the processes below scale with it.

The full architectural vision and the M1–M20 roadmap live in
[docs/ULTRAPLAN.md](docs/ULTRAPLAN.md). **When in doubt, that file is the source of truth.** The
conceptual foundation is in [docs/CONTEXT-ENGINEERING.md](docs/CONTEXT-ENGINEERING.md) and its
mapping onto Forvum in
[docs/CONTEXT-ENGINEERING-MAPPING.md](docs/CONTEXT-ENGINEERING-MAPPING.md). The per-step issue index
is [docs/ISSUES.md](docs/ISSUES.md).

## Ways to contribute

- **Design / architecture** — propose or refine a contract, an SPI, a build tier, or any part of the
  roadmap. These shape the project most, and they come first (see [Contribution flow](#contribution-flow)).
- **Code** — implement a milestone slice, a first-party extension (channel / provider / tool), or a
  bug fix.
- **Docs** — improve `docs/`, this guide, the README, or in-code JavaDoc.
- **Bug reports** — open a GitHub issue with steps to reproduce, expected vs. actual behavior, and
  your environment (OS, Java version, JVM vs. native).
- **Testing** — add or strengthen tests, reproduce reported bugs with a failing test, or expand
  native-mode coverage.

## Contribution flow

**For architectural changes — a contract, an SPI, a build tier, or anything in
[docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) — open a GitHub issue or discussion FIRST** to align on the
design before you write a PR. This keeps the architectural narrative coherent and avoids reviewers
arguing about scope inside a code PR.

**For purely additive leaf changes** — a new test, a typo fix, a small bug fix in already-merged
code — skip the discussion and open the PR directly.

PRs target `main`, unless the change is demo-specific, in which case target `demo/conference-mvp`
(the branch carrying the working vertical slice: one agent vs. local Ollama via an interactive CLI).

## Prerequisites

- **Java 25** (LTS).
- **Maven 3.9+**, or just use the bundled wrapper `./mvnw` (recommended — it pins the Maven version
  for everyone).
- **GraalVM CE 25 / Mandrel 25.0.x-Final** for native builds (or use the container build, below).
- **Ollama** to run the demo slice locally.

## Build & run

Always invoke the committed wrapper `./mvnw`. Native is the **primary acceptance path**; fast-jar is
the inner dev loop.

```bash
# JVM fast-jar — fast inner dev loop
./mvnw -pl forvum-app -am package
java -jar forvum-app/target/quarkus-app/quarkus-run.jar

# Dev mode — Dev UI + live reload, for developing Forvum itself
./mvnw -f forvum-app quarkus:dev          # Dev UI at /q/dev/

# Native single-binary — PRIMARY target
./mvnw -f forvum-app -Pnative package
# No local GraalVM? Build the native image in a container:
./mvnw -f forvum-app -Pnative package -Dquarkus.native.container-build=true

# Reactor verify — runs the full test suite (JaCoCo coverage gates are planned, not yet wired — see #69)
./mvnw verify
```

## Testing

- **TDD is the process commitment.** Each milestone's `Verify` script is the test that lands before
  the implementation passes it (Red → Green → Refactor).
- **Test pyramid:**
  - **Unit** (`*Test`) — fast, no Quarkus boot, no I/O.
  - **Integration** (`*IT`) — `@QuarkusTest` against real SQLite via `@TempDir`.
  - **E2E** — scripts under `forvum-app/.../e2e/`.
- Run everything with `./mvnw verify`.
- **Coverage gates (JaCoCo):** the target is 80% line at the parent, 75% branch — but JaCoCo is **not yet wired** into the build, so it is not enforced today (tracked in #69 / X3).
- **Live-provider tests** are tagged `live` and are **default-off** (they hit real model providers);
  they run in nightly CI only.

## Native-first rules (MUST follow)

GraalVM native is the primary, mandatory build target. Write every contribution as if native is the
only target.

- **No runtime reflection** outside framework-managed paths.
- **Every JSON-serialized / DTO type is a record carrying `@RegisterForReflection`** — a build
  enforcer (from M2) fails the build if one is missing.
- **No `--enable-preview` on the native path.** Preview features are prohibited there.
- **`StructuredTaskScope` is NOT used in v0.1** (it is still preview in JDK 25).
- **No `sun.misc.Unsafe`, CGLib, or runtime Javassist** — they break the native binary and are
  CI-banned.
- **No `synchronized` in `forvum-engine` / `forvum-channel-*` hot paths** — use `ReentrantLock`,
  `java.util.concurrent`, or atomics.
- **Virtual threads first.** Blocking, imperative code on virtual threads is the default concurrency
  model — not reactive programming. Use `@RunOnVirtualThread` (inbound handlers, scheduled jobs) and
  `Executors.newVirtualThreadPerTaskExecutor()` (engine fan-out). Reactive types (Mutiny `Uni`/`Multi`,
  Reactor) are allowed **only** at a framework-mandated boundary with no VT-friendly API, bridged to a
  virtual thread there and justified at the call site. **Reactive code anywhere a virtual thread would
  have worked is grounds to reject the PR.**

## Coding conventions

- **Java 25 idioms:** records, sealed interfaces, pattern matching.
- **Validate in canonical constructors** with triage-oriented error messages (say what was wrong and
  what was expected).
- **`ScopedValue` over `ThreadLocal`** for context propagation.
- **CDI-first.**
- **Respect the module layering:** `forvum-core` (pure-Java, no Quarkus) → `forvum-sdk` (the only
  extension contract) → `forvum-engine` (extension-agnostic; never compile-depends on a concrete
  channel/provider/tool) → first-party extensions (depend only on `forvum-sdk`) → `forvum-app` (the
  only runnable artifact).
- **Match the surrounding style** and keep edits surgical — touch only what the task requires.

## Commit & PR conventions

- **Conventional Commits, imperative mood.** Examples: `feat(core): add domain records`,
  `docs: refresh README`, `fix(engine): correct fallback ordering`.
- **Co-author trailers welcome** for AI-assisted or pair-programmed commits — add a `Co-Authored-By` line.
- **English only for every repository artifact:** code, identifiers, comments, docs, commit
  messages, config keys, and log messages. Use American spelling (`color`, `behavior`, `analyze`).
- **Keep PRs focused and surgical** — one logical change per PR, with tests.

### Stacked pull requests (dependent branches)

Prefer **non-stacked** PRs: branch each change from `main` so it can merge independently. Only stack
when there is a real **code** dependency (e.g. a milestone that won't compile without the previous one).

When you must stack, follow this recipe — it avoids a footgun where deleting a branch that is still the
**base** of an open PR **closes** that child PR (GitHub can't reopen or retarget it once the base is gone):

1. Open each child PR with its parent branch as base (e.g. `feat/b` → `feat/a`).
2. **Do not** pass `--delete-branch` while merging during the sequence.
3. Merge **bottom-up**. Before merging a parent, **retarget its children to `main` first**
   (`gh pr edit <child> --base main`) — don't rely on GitHub auto-retargeting.
4. `main` requires *branch up to date*, so each PR needs `gh pr update-branch <pr>` + a fresh green CI
   run before it can merge.
5. **Delete the merged branches only at the very end**, once no open PR references them as a base.

A local guardrail hook (PreToolUse on Bash) asks for confirmation before any branch deletion as a
backstop; it's a personal `~/.claude/settings.json` hook, not part of this repo.

## Code review

Every PR is reviewed AI-assisted and approved by the maintainer before merge — the procedure and the
full rubric live in [docs/CODE-REVIEW.md](docs/CODE-REVIEW.md).

- Run `/code-review` on the branch diff (`/code-review ultra` for milestone PRs), then walk the rubric.
- **Merge gate:** CI green (JVM + native, both platforms) **and** the rubric walked **and** maintainer
  approval. The checklist in `.github/PULL_REQUEST_TEMPLATE.md` maps to the three review pillars —
  tests pass, maximum decoupling, simplest possible code — plus the virtual-threads-first and
  native-first invariants.

## License

Forvum is licensed under **Apache 2.0**. By contributing, you agree that your contributions are
licensed under the same terms.
