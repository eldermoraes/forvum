# Contributing to Forvum

Forvum is in active design with implementation following the plan documented in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md). Both design and code contributions are welcome. The project is small and early — processes described below scale with it.

## Design contributions come first

Architectural decisions are proposed, debated, and recorded as **design rounds** in [docs/design-rounds/](docs/design-rounds/). Before opening a pull request that changes a contract, an SPI, a build tier, or anything in [ULTRAPLAN.md](docs/ULTRAPLAN.md), open a design round first — even a short one. This keeps the architectural narrative coherent and avoids reviewers and contributors arguing about scope inside a code PR.

If the change is purely additive at the leaf level (a new test, a typo fix, a small bug fix in already-merged code), skip the design round and open the PR directly.

## Code contributions

Match the conventions of the surrounding code. Maven reactor builds, Java 25 syntax, records for value types, validation in canonical constructors with triage-oriented error messages (see [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §4.3 for examples of the pattern). The `main` branch ships the bootstrap; the `demo/conference-mvp` branch carries the conference MVP slice. Open PRs against `main` unless the change is demo-specific, in which case target `demo/conference-mvp`.

## Reference

The full architectural vision and milestone roadmap live in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md). When in doubt, that file is the source of truth.
