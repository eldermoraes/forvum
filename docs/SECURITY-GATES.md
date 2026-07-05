# Security gates — committed policy (#174)

This is the single source of truth for Forvum's supply-chain, secret, SAST, SBOM, and provenance gates:
what runs where, the severity thresholds, the SLA/ownership, the suppression process per tool, and the
independent verification commands. It complements the native-first / layering discipline already enforced
by `.github/native-discipline.sh`, `.github/reflection-registration.sh`, `.github/concurrency-guardrails.sh`,
and the per-module `maven-enforcer-plugin` allowlists.

Scope boundary: this issue (#174) covers **repository / build / release inputs and outputs**. The runtime
JVM-plugin artifact-integrity path (`plugins/` checksum policy, owner-only install) is tracked separately by
#171.

---

## 1. Where each gate runs

Placement principle: **per-PR checks are fast, deterministic, parallel ubuntu jobs (seconds–minutes, never
on the critical path); push-to-`main` submits the dependency graph; a weekly schedule runs the
network-sensitive deep scans; release produces the SBOMs, provenance, and the blocking image scan.** The
~37 min CI wall clock (ci.yml's native legs) is untouched, and macOS concurrency — the scarce runner
resource — gains zero jobs.

| Gate | Tool | Trigger | Workflow / file |
|---|---|---|---|
| Dependency diff | `actions/dependency-review-action` | pull_request | `security.yml` → `dependency-review` |
| Dependency graph submission (feeds Dependabot alerts) | `advanced-security/maven-dependency-submission-action` | push `main` | `security.yml` → `dependency-submission` |
| Secret scan | `gitleaks/gitleaks-action` (engine pinned via `GITLEAKS_VERSION`) | PR (PR range) + push `main` (push range) + weekly schedule/dispatch (full history) | `security.yml` → `secrets` + `.gitleaks.toml` |
| Workflow + shell lint | actionlint + shellcheck | pull_request + push + schedule | `security.yml` → `workflow-lint` |
| SAST — Java + Actions | `github/codeql-action` | PR + push + weekly | `codeql.yml` |
| Deep scan — image + full SBOM | `aquasecurity/trivy-action` | weekly (Mon 05:00 UTC) + `workflow_dispatch` | `security.yml` → `scheduled-deep-scan` |
| SBOM (Maven closure + image) | CycloneDX Maven plugin + Trivy | release (tag `v*`) | `release.yml` + parent `pom.xml` |
| Image scan (blocking, pre-push) | `aquasecurity/trivy-action` | release | `release.yml` build leg |
| Provenance attestations | `actions/attest-build-provenance` | release | `release.yml` build leg |

Continuous vulnerability signal for Maven dependencies = **Dependabot alerts** (powered by the
push-to-`main` dependency-graph submission) + Dependabot **security updates** (auto-opened PRs). The weekly
Trivy deep scan is the correlated belt-and-suspenders, plus a bit-rot guard on the CycloneDX goal between
releases.

---

## 2. Severity thresholds & failure policy

| Gate | Placement | Policy |
|---|---|---|
| dependency-review | per-PR | hard-fail on a newly-introduced dependency with severity ≥ HIGH |
| gitleaks | per-PR + weekly full-history | hard-fail on any finding; allowlist = `.gitleaks.toml` (owner + reason + review date) |
| CodeQL (java + actions) | per-PR + push + weekly | SARIF → code scanning; the PR-blocking check fails on new alerts ≥ HIGH; a dismissal requires a stated reason |
| actionlint + shellcheck | per-PR | hard-fail |
| Trivy image + SBOM scan | release (pre-push, blocking) + weekly | hard-fail on CRITICAL/HIGH **fixable**; `ignore-unfixed: true`; suppressions in `.trivyignore.yaml` with `expired_at` |
| SBOM presence | release | `fail_on_unmatched_files: true` fails the release when an SBOM asset is missing |
| Provenance attestations | release | a failed attest step fails the release build job |

`ignore-unfixed: true` on the Trivy gates is deliberate: `ubi9/ubi-micro` routinely carries base CVEs with
no upstream fix; scanning fixable-only keeps the gate **actionable** rather than a permanent red bar. New
CVEs against the shipped image surface in the weekly deep scan.

There is **no warn-then-ratchet phase** — every gate lands green on day one (test fixtures allowlisted,
`ignore-unfixed` on, no known findings to grandfather). If a first full run surfaces a real pre-existing
finding, it is fixed or suppressed-with-expiry in the same change — never silently ignored.

---

## 3. SLA & ownership

The maintainer (@eldermoraes) owns triage and remediation.

| Finding class | Triage | Remediation SLA |
|---|---|---|
| Secret leak (gitleaks / push protection) | immediate | rotate + purge before the branch merges; a leaked secret is treated as compromised |
| CRITICAL vuln (dependency / image) | within 7 days | before the next release, or suppress-with-expiry + a dated remediation plan |
| HIGH vuln | within 14 days | within 30 days |
| CodeQL HIGH+ alert | within 14 days | fix, or dismiss-with-reason in the code-scanning UI (auditable) |

---

## 4. Suppression process (auditable, expiry-bound)

**No finding is silently ignored.** Every suppression carries an owner, a reason, and — where the tool
supports it — an expiry. The four surfaces:

- **gitleaks** — `.gitleaks.toml` `[[allowlists]]`. Today: one entry scoping OUT `**/src/test/**` (the
  deliberate fake-token fixtures — SecretRedactor / OutputGuard / provider api-key / Slack / Copilot
  suites). Every NEW entry needs an owner + reason + review-date comment; reviewed each release. Never widen
  the allowlist to docs or main source.
- **Trivy** — `.trivyignore.yaml`. Each entry needs `id` + `statement` (owner + reason) + a mandatory
  `expired_at` (YYYY-MM-DD); Trivy re-surfaces the finding after expiry.
- **CodeQL** — dismiss the alert in the Security → Code scanning UI with a required reason (auditable in the
  alert timeline). No file-based suppression is used.
- **dependency-review** — no suppression file; a flagged dependency is removed or the advisory is accepted
  by not introducing the dependency. (License checks are not enabled — out of #174's scope.)

---

## 5. Independent verification commands

Anyone can verify a release without trusting the pipeline:

```bash
VER=v0.5.0   # the release tag

# 1. Checksum (unchanged, per-asset .sha256):
curl -fsSLO "https://github.com/eldermoraes/forvum/releases/download/$VER/forvum-linux-x64"
curl -fsSLO "https://github.com/eldermoraes/forvum/releases/download/$VER/forvum-linux-x64.sha256"
shasum -a 256 -c forvum-linux-x64.sha256        # or: sha256sum -c

# 2. SBOM presence — each release carries a Maven-closure SBOM and a container SBOM:
#      forvum-<version>-maven-sbom.cdx.json  (CycloneDX, the whole Maven closure)
#      forvum-<version>-image-sbom.cdx.json  (CycloneDX, the GHCR image: base + native binary)
gh release view "$VER" --repo eldermoraes/forvum --json assets \
  --jq '.assets[].name | select(endswith(".cdx.json"))'

# 3. Provenance — GitHub OIDC-backed build-provenance attestation, tied to repo + workflow + commit + tag:
gh attestation verify forvum-linux-x64 --repo eldermoraes/forvum
gh attestation verify oci://ghcr.io/eldermoraes/forvum:${VER#v}-native --repo eldermoraes/forvum
```

The release job enumerates every expected asset class in its `files:` list (the 4 binaries, their checksums,
the two mutually-exclusive SBOM patterns, the installer), and `fail_on_unmatched_files: true` is checked PER
pattern — so a missing SBOM (or binary/checksum/installer) fails the release, and a published release always
has both SBOMs. `gh attestation verify` checks the predicate identity (repository, workflow path, commit
SHA, ref) against the signed attestation.

---

## 6. Action-pinning policy & the update process

- **Third-party actions are pinned to a full commit SHA** with a trailing `# vX.Y.Z` version comment:
  `graalvm/setup-graalvm`, `softprops/action-gh-release`, `azure/setup-helm`, `gitleaks/gitleaks-action`,
  `aquasecurity/trivy-action`, `advanced-security/maven-dependency-submission-action`.
- **GitHub-authored actions (`actions/*`, `github/*`) stay on major refs** — the ecosystem-standard trust
  cut for the platform owner's own actions: `actions/checkout`, `actions/setup-java`,
  `actions/upload-artifact`, `actions/download-artifact`, `actions/attest-build-provenance`,
  `github/codeql-action` (major tags), and `actions/dependency-review-action` (whose `v5` major ref is a
  BRANCH upstream, not a tag — same GitHub-owned trust class, recorded here for accuracy).
- The one non-action third-party input — the actionlint download script — is commit-pinned in
  `security.yml` (`ACTIONLINT_COMMIT`), and the gitleaks scan engine is version-pinned (`GITLEAKS_VERSION`).
- **Update process = Dependabot** (`.github/dependabot.yml`): weekly PRs for the `github-actions` (bumps the
  SHA-pins + their comments), `maven` (root, minor/patch grouped), and `docker` (the base-image digest)
  ecosystems. Version bumps stay maintainer decisions (`forvum-bom` is the single Maven bump point;
  auto-merge is NOT enabled). Dependabot **security updates** auto-open PRs for vulnerable dependencies.
- **Build-input pin:** `forvum-app/src/main/docker/Dockerfile.native` pins the `ubi9/ubi-micro` base to a
  `9.6@sha256:…` digest.

---

## 7. Secret handling in workflows & release logs

- Fixtures under `**/src/test/**` hold deliberately fake, well-formed provider-shaped tokens (the
  SecretRedactor / OutputGuard suites) and are allowlisted in `.gitleaks.toml`. Main-source hits are prefix
  SHAPES in Javadoc/regex (e.g. `sk-***`), not values, and are NOT allowlisted.
- **GitHub server-side secret scanning + push protection** (repo settings, §8) match VALID provider-shaped
  tokens *everywhere, including tests* — the layered control for a real secret pasted into a fixture. The
  existing fixtures are deliberately invalid-shaped (short/truncated) so they trip neither gate. **If a
  future test needs a realistic-shaped fake token, keep it invalid-shaped** (truncate it) so push protection
  does not block the push; that is working-as-intended.
- No workflow echoes a secret. `GITHUB_TOKEN` is auto-masked; the Telegram/Slack/Discord token-in-URL and
  header handling is redacted at the code level ([P2-13]/[P2-CH]). Release binaries are not gitleaks-scanned
  (grep over a native binary is noise); the release **inputs** are fully scanned, which is the meaningful
  control. This posture satisfies the "no scan prints credentials / private dependency URLs / secret values"
  acceptance criterion.

---

## 8. One-time GitHub-side enablement (admin)

These are repository settings, not YAML — but they are half of the issue's acceptance criteria
("repository history/new commits" server-side scanning). Run once by an admin (recorded in the #174 PR body
with the response status of each call):

```bash
REPO=eldermoraes/forvum

# Secret scanning + push protection (GitHub Advanced Security for public repos):
gh api -X PATCH "repos/$REPO" -f 'security_and_analysis[secret_scanning][status]=enabled'
gh api -X PATCH "repos/$REPO" -f 'security_and_analysis[secret_scanning_push_protection][status]=enabled'

# Dependabot alerts + security updates:
gh api -X PUT  "repos/$REPO/vulnerability-alerts"
gh api -X PATCH "repos/$REPO" -f 'security_and_analysis[dependabot_security_updates][status]=enabled'

# Verify:
gh api "repos/$REPO" --jq '.security_and_analysis'
```

**Code-scanning check-failure threshold = High** is set in the repo UI (Settings → Code security →
Code scanning → *Check failure severity* → **High or higher**) — the API surface for that setting is not
stable, so it is a documented one-time UI step; CodeQL alerts still upload regardless.

`main` has no branch protection today (the merge gate is "CI green" by convention). All new jobs use stable,
non-matrix names, so they are branch-protection-ready if/when protection is enabled — deliberately out of
this issue's scope.

---

## 9. Named follow-ups

Not created as GitHub issues (repo rule: issues are never auto-created) — recorded here so they are not lost:

- **SBOM attestation** (`actions/attest-sbom`): attest the generated SBOMs themselves, in addition to the
  binary/image build-provenance. Deferred because SBOM presence is already release-blocking and the
  binaries + image carry provenance; this is a one-step addition later (simplicity-first).
- **Full JFR virtual-thread pinning gate** (X2/#68) and the full `@RegisterForReflection` enforcer — pre-existing
  tracked follow-ups, unrelated to #174, listed here only for completeness of the CI-discipline picture.
