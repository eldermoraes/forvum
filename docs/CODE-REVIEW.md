# Forvum Code Review

How a Forvum pull request is reviewed and merged. Forvum is an AI-assisted, solo-maintainer project;
this guide makes the review repeatable without ceremony. The architectural source of truth is
[ULTRAPLAN.md](ULTRAPLAN.md); the contributor guide is [../CONTRIBUTING.md](../CONTRIBUTING.md).

The three review pillars:

1. **Tests pass.**
2. **Maximum decoupling** between APIs and modules.
3. **Simplest possible code** (clean code / DDD) — without over-engineering.

The strategy is to **automate the mechanical** (tests, layering) and **reserve human/AI judgment** for
what automation cannot check (design decoupling, simplicity, the ubiquitous language, the concurrency
model). The PR author fills in `.github/PULL_REQUEST_TEMPLATE.md`; the reviewer walks this rubric.

---

## 1. Procedure

Every PR is reviewed locally with an AI pass, then approved by the maintainer. There is **no CI review
bot** — CI only builds and tests.

1. **AI pass.** From the PR branch, run the `/code-review` Claude Code skill on the diff.
   - Standard PRs: `/code-review` (high-confidence findings).
   - Milestone PRs (an M1–M20 slice, a new SPI, a build-tier change): `/code-review ultra` (deep
     cloud multi-agent review).
2. **Walk this rubric.** Confirm the automated gates are green (section 2), then apply judgment to the
   judgment dimensions (section 3) and the anti-over-engineering checks (section 4).
3. **Record the result** as a PR comment (template in section 6).
4. **Merge gate.** Merge only when **all three** hold: CI is green (JVM + native, both platforms)
   **and** this rubric was walked **and** the maintainer approves. Squash-merge with a
   Conventional-Commit subject.

---

## 2. Automated gates (CI + enforcer already prove these — the reviewer just confirms green)

These are machine-checked. If any is red, stop: the PR is not reviewable yet.

| Gate | Proven by | Status |
|---|---|---|
| JVM build + tests, linux-amd64 + macos-arm64 | `.github/workflows/ci.yml` (`jvm`) | active |
| Native compile + native smoke, both platforms | `.github/workflows/ci.yml` (`native`) | active |
| Module layering (core pure-Java; engine extension-agnostic) | `maven-enforcer-plugin` `bannedDependencies` | **active (M1)** |
| Maven ≥ 3.9, Java ≥ 25 | `maven-enforcer-plugin` `requireMavenVersion`/`requireJavaVersion` | active |
| `@RegisterForReflection` on every DTO/record | custom enforcer rule | planned: **M2** |
| 80% line / 75% branch coverage | JaCoCo gates | planned: **M2** |
| No thread pinning · no `synchronized` in engine/channel hot paths · no reactive-where-VT-suffices | CI grep + allowlist | planned: **M5/M6** |
| 200 ms cold-start | native smoke latency assertion | planned: **M20** |

The reviewer does not re-derive these; a green check mark is the evidence. Note: the two native legs
use different builders — Mandrel on `linux-amd64`, GraalVM CE on `macos-arm64` (Mandrel ships no arm64
build) — so "green on both" is two toolchains, not one; a builder-specific native regression must still
turn a leg red to be caught.

---

## 3. Judgment dimensions (human review — what CI cannot do)

### 3.1 Decoupling quality (pillar 2)
- Look for: new types placed in the lowest layer that can hold them — value contracts in `forvum-core`,
  the plugin SPI in `forvum-sdk`, runtime wiring in `forvum-engine`, concrete extensions in Layer 3.
- Look for: dependencies point only downward; a plugin's only Forvum dependency is `forvum-sdk`.
- Red flag: an extension ID, provider name, or channel string hardcoded in `forvum-core`/`forvum-engine`.
- Red flag: a public method leaking a framework type (Quarkus, Mutiny, Jackson) across an SPI boundary
  that should expose only `forvum-core`/`forvum-sdk` types.

### 3.2 Simplicity / clean code / DDD (pillar 3)
- Look for: the smallest change that closes the issue; each class/record has one reason to change.
- Look for: closed hierarchies modeled as `sealed` + records; exhaustive `switch` over the permits set
  (no `default` that hides a missing case).
- Red flag: an interface with a single implementation and no second caller on the horizon (YAGNI).
- Red flag: a layer of indirection (factory, strategy, wrapper) introduced "to be flexible later."

### 3.3 Naming / ubiquitous language
- Look for: domain vocabulary (Agent, Turn, Event, Fallback, Budget, Scope, Persona, Channel, Provider,
  Tool, Ledger) used consistently with ULTRAPLAN.
- Look for: one bounded context per module or `forvum-engine` sub-package (routing, memory, tools…).
- Red flag: generic names (`Manager`, `Helper`, `Util`, `data`, `info`) where a domain term exists.

### 3.4 Test quality (beyond coverage)
- Look for: the Verify-script test landed first (Red → Green); tests assert behavior and error
  messages, not implementation detail.
- Look for: property-based tests (jqwik) for parsers/records (`ModelRef.parse` roundtrip, event Jackson
  roundtrip, budget/scope invariants) — mandatory once those types exist.
- Look for: native parity — anything risky on native has an `*IT` that runs under `-Pnative`.
- Red flag: happy-path-only tests; canonical-constructor validation with no failing test for the
  rejected input.
- Red flag: coverage padded with assertion-free tests to clear the JaCoCo gate.

### 3.5 Concurrency model — virtual threads first (REJECTION-GRADE)

Virtual threads are Forvum's default concurrency model, across every layer. This dimension is an
**explicit reason to reject a PR**.

- Look for: blocking, imperative code carried on a virtual thread — `@RunOnVirtualThread` on inbound
  REST/WebSocket handlers and `@Scheduled` jobs; `Executors.newVirtualThreadPerTaskExecutor()` for
  engine fan-out.
- Look for: any reactive use sits at a **framework-mandated boundary** with no VT-friendly API, is
  **bridged to a virtual thread there** (e.g. `await()` inside a VT scope), and carries a one-line
  justification at the call site.
- **Red flag (reject):** `Uni`/`Multi`/Reactor/`@NonBlocking`/reactive chains where
  `@RunOnVirtualThread` + blocking code would work; Mutiny types leaking into `forvum-engine` or the
  SPI; choosing a reactive client (e.g. a reactive REST client) when a blocking-on-VT alternative
  exists.
- Not "reactive programming" in this sense: `java.util.concurrent.Flow.Publisher` used to bridge a
  token stream is permitted.

Why a grep, not a `bannedDependencies` rule: Mutiny ships as a transitive of Quarkus ArC, so banning
the dependency would break the build. The boundary is a **source-level** concern, enforced by review
now and by an import grep with an allowlist when the concurrency greps land (M5/M6).

### 3.6 Native-first correctness
- Look for: every JSON/DTO type is a record carrying `@RegisterForReflection`.
- Look for: `ScopedValue` (not `ThreadLocal`); fan-out via `newVirtualThreadPerTaskExecutor()`, not
  `StructuredTaskScope` (still preview in JDK 25).
- Red flag: `--enable-preview` on the native path; `sun.misc.Unsafe`, CGLib, runtime Javassist, ad-hoc
  reflection or classpath scanning.

---

## 4. Avoid over-engineering (YAGNI)

The simplicity pillar cuts both ways: under-engineering ships bugs; over-engineering ships complexity
nobody asked for. This mirrors the behavioral guidelines in CLAUDE.md §13. Reject, in review:

- **Abstractions for single-use code.** No interface/factory/generic until there is a second concrete
  use. The sealed SPI in `forvum-sdk` is the *intended* abstraction; speculative internal ones are not.
- **Config that wasn't requested.** No new `application.properties` keys, feature flags, or
  `~/.forvum/` options unless the issue/milestone calls for them.
- **Error handling for impossible states.** Don't catch what cannot be thrown; don't null-check what a
  canonical constructor already validated; let an exhaustive `switch` over a sealed type stand without
  a defensive `default`.
- **Premature performance work.** No caching/pooling/async without a measured need; the latency gates
  (M5/M6/M20) are where performance is proven, not guessed.
- **Scope creep.** One logical change per PR. "While I was here" refactors of untouched code are
  surgical-edit violations — split them out.

---

## 5. Enforcement that lands later (documented now)

### 5.1 Layer-3 plugin layering

When a Layer-3 module is created (`forvum-channel-*`, `forvum-provider-*`, `forvum-tools-*`), it carries
this `maven-enforcer-plugin` execution so it compiles only against `forvum-sdk` — never against engine
or core internals (CLAUDE.md §12):

```xml
<bannedDependencies>
    <excludes>
        <exclude>ai.forvum:forvum-engine</exclude>
        <exclude>ai.forvum:forvum-core</exclude>
    </excludes>
    <searchTransitive>true</searchTransitive>
    <message>A Forvum extension (Layer 3) compiles only against forvum-sdk; it must not depend on forvum-engine or forvum-core. See CLAUDE.md section 12.</message>
</bannedDependencies>
```

### 5.2 Virtual-threads-first grep (M5/M6)

Alongside the planned thread-pinning and `synchronized` greps, a source grep flags reactive imports
where a virtual thread would do, with an allowlist of justified framework boundaries:

```bash
grep -rnE 'io\.smallrye\.mutiny|reactor\.core|org\.reactivestreams' forvum-engine/src/main forvum-channel-*/src/main \
  | grep -vf .github/vt-allowlist.txt
```

Each allowlist entry names the boundary and the reason it cannot use a virtual thread.

---

## 6. Review record (paste as a PR comment)

```
### Review — <PR title>

**AI pass:** /code-review[ ultra] — <n findings, resolved / accepted-as-is>
**Automated gates:** CI JVM ✅ / native ✅ (both platforms) · enforcer ✅
**Judgment:**
- Decoupling: <notes / n/a>
- Simplicity & DDD: <notes>
- Naming / ubiquitous language: <notes>
- Test quality: <notes>
- Concurrency (virtual threads first): <notes / n/a>
- Native-first: <notes>
- Over-engineering check: <none / list>

**Decision:** approve / changes requested
```
