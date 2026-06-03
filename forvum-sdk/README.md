# forvum-sdk

Layer 1 — the **only** contract third-party plugins compile against. It depends solely on
`forvum-core`; it has **no** dependency on Quarkus, the engine, or any concrete extension
(`docs/ULTRAPLAN.md` §2.2).

## Provider SPIs

Four sealed interfaces, one per extension kind. Each `permits` a single `non-sealed abstract`
base — third parties extend the base, they cannot implement the interface directly. That keeps the
set of implementations closed and build-time-known (native-image discovery) while staying open to
extension:

| Sealed interface  | Extend this base           | Contributes |
|-------------------|----------------------------|-------------|
| `ChannelProvider` | `AbstractChannelProvider`  | a channel (TUI / Web / Telegram / …) |
| `ModelProvider`   | `AbstractModelProvider`    | an LLM binding for the routing layer |
| `ToolProvider`    | `AbstractToolProvider`     | tools + their `PermissionScope` |
| `MemoryProvider`  | `AbstractMemoryProvider`   | memory retrieval (Select pillar) |

At M3 the interfaces carry only `extensionId()`; the rich transport/resolution/retrieval methods are
added by the consuming milestones (M9–M17), which bring the heavyweight types (e.g. LangChain4j
`ChatModel`).

## Markers

- `@ForvumExtension` — marks a plugin entry point; a build-time `BuildStep` in `forvum-engine` scans
  it together with the manifest to register providers and emit reflection hints.
- `@RegisterForReflection` — Forvum-owned, **re-declared here** (not the Quarkus annotation) so
  plugins can request native reflection on their DTO records without depending on `quarkus-core`.
  Its shape models the subset of Quarkus' annotation that Forvum's build-time mapping needs (the
  targets-holder form); the engine `BuildStep` translates each occurrence into the native hint.

## Plugin manifest

Every plugin ships `META-INF/forvum/plugin.json`. The v0.1 schema is documented in
[`src/main/resources/META-INF/forvum/plugin-schema.json`](src/main/resources/META-INF/forvum/plugin-schema.json).
Example:

```json
{
  "id": "ollama",
  "version": "0.1.0",
  "provides": ["model"]
}
```

Required fields: `id` (matches each provider's `extensionId()`), `version`, and `provides` (one or
more of `channel`, `model`, `tool`, `memory`).
