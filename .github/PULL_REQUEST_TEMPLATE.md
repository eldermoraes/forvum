<!--
Forvum PR checklist. Keep it honest and short. Mark non-applicable rows "n/a".
Review standard: docs/CODE-REVIEW.md. Conventions: CONTRIBUTING.md, CLAUDE.md.
-->

## Summary

<!-- One or two sentences: what this PR does and why. -->

Closes #<issue> · Milestone: M<n>

## Verify

<!-- The milestone's Verify command you ran, and the key result (paste the tail, not the whole log). -->

```
$ <command>
<result tail>
```

## Checklist

**Tests (pillar 1)**
- [ ] `./mvnw verify` is green locally; CI is green (JVM + native, both platforms).
- [ ] A failing test landed before the code that makes it pass (Red → Green → Refactor).

**Decoupling (pillar 2)**
- [ ] No new upward module dependency; the layering enforcer passes (`./mvnw -DskipTests validate`).
- [ ] forvum-core stays pure Java; forvum-engine stays extension-agnostic; plugins depend only on forvum-sdk.
- [ ] New domain types are records / sealed interfaces where the contract is closed.

**Simplicity (pillar 3 — clean code / DDD, no over-engineering)**
- [ ] Minimum code that solves the issue; no speculative abstractions or config that wasn't asked for.
- [ ] Names use the project's ubiquitous language; one bounded context per module/sub-package.

**Concurrency — virtual threads first**
- [ ] No reactive code (Mutiny `Uni`/`Multi`, Reactor, a reactive client pipeline) where
      `@RunOnVirtualThread` + blocking code would work.
- [ ] Any reactive use is at a framework-mandated boundary, bridged to a virtual thread there, and
      justified at the call site.

**Native-first**
- [ ] Records/DTOs carry `@RegisterForReflection` (becomes an enforcer at M2).
- [ ] No `--enable-preview` on the native path; no `StructuredTaskScope`; `ScopedValue` not `ThreadLocal`.
- [ ] No banned imports (`sun.misc.Unsafe`, CGLib, runtime Javassist, runtime reflection).

**Conventions**
- [ ] English-only artifacts, American spelling; Conventional Commits, imperative mood.
- [ ] Project docs kept in sync (README / CONTRIBUTING / CLAUDE / docs) per the docs-sync rule.
