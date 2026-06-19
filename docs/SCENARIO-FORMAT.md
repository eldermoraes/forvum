# Shared scenario format (eval + qa)

A single, simple JSON suite format that BOTH `forvum eval` (P3-10 #58, CAPR-gated evaluation) and
`forvum qa` (#43, QA scenario suite) consume. One format, two consumers — defined here so the two
features do not drift into two incompatible schemas. The super-wave plan mandates one scenario format;
this is it.

## Location

One file per suite, under `$FORVUM_HOME/<dir>/<name>.json`:

- eval suites → `$FORVUM_HOME/eval/<name>.json` (run by `forvum eval <name>`)
- qa suites → `$FORVUM_HOME/qa/<name>.json` (run by `forvum qa <name>`)

Both directories hold the same suite shape; the only difference is which command reads which directory.
The format is hand-parsed from the `JsonNode` (no Jackson reflective databind), so the typed records carry
no `@RegisterForReflection` (the `CronSpecReader` pattern).

## Shape

```json
{
  "agent": "main",
  "floor": 0.8,
  "judge": "llm:ollama:qwen2.5:0.5b",
  "match": "contains",
  "scenarios": [
    { "id": "greeting", "prompt": "Say hello in one word.", "expect": "hello" },
    { "id": "math",     "prompt": "What is 2+2?",           "expect": "4", "match": "contains" },
    { "id": "code",     "prompt": "Return an HTTP error code.", "expect": "\\d{3}", "match": "regex" }
  ]
}
```

### Top-level fields

| Field       | Required | Default     | Meaning |
|-------------|----------|-------------|---------|
| `agent`     | yes      | —           | the agent id the scenarios run as |
| `floor`     | yes      | —           | the minimum CAPR pass-rate, `0.0`–`1.0`, the run must meet (eval gates on it; see below) |
| `judge`     | no       | offline matcher | how each reply is scored — omit for the deterministic offline matcher, or `"llm:<provider>:<model>"` for an opt-in LLM judge (Risk #10) |
| `match`     | no       | `contains`  | the suite-default match mode for scenarios that omit their own |
| `scenarios` | yes      | —           | a non-empty array of cases |

### Scenario fields

| Field    | Required | Default            | Meaning |
|----------|----------|--------------------|---------|
| `id`     | yes      | —                  | the case id (used for the per-scenario session and the report line) |
| `prompt` | yes      | —                  | the turn's user-message content |
| `expect` | yes      | —                  | the expected reply property — a substring, exact string, or regex per `match` |
| `match`  | no       | the suite `match`  | `contains` \| `exact` \| `regex` (case-insensitive) |

### Match modes

- `contains` — case-insensitive substring (the lenient default).
- `exact` — case-insensitive whole-string equality after trimming.
- `regex` — `expect` is a Java regex searched anywhere in the reply.

## Judges (Risk #10)

The default judge is the deterministic, offline **matcher** (the `match` modes above): NO live model, so a
suite runs as a CI quality gate without inference. A suite's explicit `"judge": "llm:<provider>:<model>"`
opts into a pluggable **LLM judge** — a cheap local model asked a PASS/FAIL question about whether the reply
satisfies the expectation. The LLM judge is never the default and is never wired into a normal turn; it lives
only inside the harness. Judge-vs-human agreement should be measured and the judge replaced if it falls
below 0.7 (the deterministic matcher has perfect agreement by construction).

## How `forvum eval` gates (P3-10)

`forvum eval <suite>` runs each scenario as a turn (through the SDK `ChannelTurnDriver`, the same path
`forvum ask` uses), scores each reply with the resolved judge, and computes the CAPR pass-rate (passing
scenarios over total). If the pass-rate falls **below** `floor` it is a regression and the command exits
non-zero — a CI/release quality gate on par with coverage. A reply at or above the floor exits 0. Per-scenario
PASS/FAIL lines and the pass-rate go to stdout; a failure to load/run the suite goes to stderr with exit 1.
Eval computes the pass-rate in memory and persists no eval rows (no migration); each scenario's turn still
writes its own `messages`/`provider_calls`/`capr_events` the normal way.

## Notes for `forvum qa` (#43) alignment

`forvum qa` can read the SAME suite shape from `$FORVUM_HOME/qa/`. If qa needs richer expectations later
(e.g. asserting a tool was called, or a non-error turn), extend the scenario record additively (a new
optional field with a backward-compatible default), so an existing `eval/` or `qa/` suite keeps parsing
— the additive-growth recipe used across the config records. Keep the parser hand-walking the `JsonNode`
(no reflective databind) so the records stay native-clean.
