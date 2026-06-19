# Forvum Helm chart — team-assistant mode

A Helm chart that deploys [Forvum](https://github.com/eldermoraes/forvum) on Kubernetes as a
**per-namespace team assistant**. Each Helm release is one isolated Forvum instance with its own
persistent SQLite state — so a namespace gets a private assistant whose memory no other namespace can
read. (Issue [#55](https://github.com/eldermoraes/forvum/issues/55), P3-7.)

> **Scope.** This is a Helm chart only — there is **no Kubernetes operator** (descoped). The chart runs
> the Forvum native single-binary container image (published to GHCR by the release pipeline; see
> [`docs/ULTRAPLAN.md` §6.4](../../docs/ULTRAPLAN.md)) as a long-lived Web-channel server.

## Per-namespace memory isolation (the headline property)

Forvum's operational state — the SQLite ledger at `$FORVUM_HOME/state/forvum.sqlite` (conversations,
memory, metrics) — lives on a `PersistentVolumeClaim` created by the release. Helm installs are
**namespace-scoped**, so:

```
namespace team-a                         namespace team-b
┌─────────────────────────────┐          ┌─────────────────────────────┐
│ Deployment  forvum          │          │ Deployment  forvum          │
│ Service     forvum          │          │ Service     forvum          │
│ PVC         forvum-state ───┼──▶ vol-a  │ PVC         forvum-state ───┼──▶ vol-b
│ state/forvum.sqlite (A's)   │          │ state/forvum.sqlite (B's)   │
└─────────────────────────────┘          └─────────────────────────────┘
        private memory A                          private memory B
```

There is **no shared volume and no cross-namespace mount**. `team-a` cannot read `team-b`'s memory.
The isolation is structural — it comes from Kubernetes namespacing plus a per-release PVC, not from any
in-app tenancy logic. **Run one release per team/namespace.**

> Keep `replicaCount: 1`. The state store is a single-writer SQLite database on a `ReadWriteOnce`
> volume; the Deployment uses the `Recreate` strategy so an upgrade never runs two pods against one
> volume. Scaling out is not supported in v0.1.

## Install

```bash
# Each team/namespace gets its own isolated assistant:
helm install forvum deploy/helm/forvum --namespace team-a --create-namespace
helm install forvum deploy/helm/forvum --namespace team-b --create-namespace
```

Pin an immutable image in production (a tag or digest):

```bash
helm install forvum deploy/helm/forvum -n team-a --create-namespace \
  --set image.tag=0.1.0-native
# or, by digest:
  # --set image.digest=sha256:<...>
```

Reach the assistant (the bundled Web channel serves HTTP on the Service):

```bash
kubectl -n team-a port-forward svc/forvum 8080:8080
```

## Configuration (`~/.forvum/` from a ConfigMap)

The human-editable Forvum config tree (`config.json`, `agents/`, `channels/`, …) is rendered into a
ConfigMap and projected read-only over the writable state volume. State stays on the PVC; config stays
declarative and GitOps-friendly. Edit values, `helm upgrade`, and the pod restarts onto the new config
(a config checksum annotation triggers the rollout; Forvum also hot-reloads files at runtime).

```yaml
config:
  configJson: |
    {}
  agents:
    main.json: |
      { "primaryModel": "ollama:qwen3:1.7b", "persona": "...", "allowedTools": [] }
  channels:
    web.json: |
      {}
  # Any other file under $FORVUM_HOME, keyed by its relative path:
  extraFiles:
    roles/admin.json: |
      { "scopes": ["FS_READ"] }
```

### Model provider + credentials

A real conversation needs a model provider. The default agent points at a local Ollama model — run an
Ollama service reachable from the pod, or switch `config.agents.main.json` to a cloud provider. Cloud
keys are read from `QUARKUS_LANGCHAIN4J_<PROVIDER>_API_KEY` env vars; wire them from a Kubernetes Secret
(do **not** inline secrets in values):

```yaml
env:
  - name: QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY
    valueFrom:
      secretKeyRef:
        name: forvum-provider-keys
        key: anthropic-api-key
```

## Key values

| Key | Default | Purpose |
|---|---|---|
| `replicaCount` | `1` | Keep at 1 (single-writer SQLite). |
| `image.repository` | `ghcr.io/eldermoraes/forvum` | GHCR image (built by the release pipeline). |
| `image.tag` | `""` → `<appVersion>-native` | Image tag; pin in production. |
| `image.digest` | `""` | Pin by digest (wins over tag). |
| `containerPort` / `service.port` | `8080` | Web channel HTTP port. |
| `persistence.enabled` | `true` | Per-namespace state PVC (the isolation). Disable = emptyDir (memory lost on restart). |
| `persistence.size` | `2Gi` | State PVC size. |
| `persistence.existingClaim` | `""` | Reuse a PVC instead of creating one. |
| `forvumHome` | `/forvum` | `$FORVUM_HOME` inside the container. |
| `config.*` | sample agent + web channel | The `~/.forvum/` tree. |
| `env` | `[]` | Extra env (provider keys from Secrets). |
| `otelExporterOtlpEndpoint` | `""` | Enable OTLP telemetry to a collector. |

See [`forvum/values.yaml`](forvum/values.yaml) for the full reference.

## Validation

This chart has been validated with `helm lint` and `helm template` (the same checks the
`.github/workflows/helm.yml` CI workflow runs on every PR touching `deploy/**`):

```bash
helm lint deploy/helm/forvum
helm template deploy/helm/forvum
```

It has **not** been deployed to a live Kubernetes cluster as part of this work — the chart's correctness
bar here is lint/template-clean plus an explicit, documented isolation model.
