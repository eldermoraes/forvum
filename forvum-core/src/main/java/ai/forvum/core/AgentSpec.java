package ai.forvum.core;

/** Minimal agent spec for MVP demo. See docs/design-rounds/demo-mvp-deferrals.md §D2. */
public record AgentSpec(String id, String systemPrompt, ModelRef primaryModel) {

    public AgentSpec {
        if (id == null || id.isBlank()
            || !id.strip().equals(id)) {
            throw new IllegalStateException(
                "AgentSpec id must be a non-blank token without "
              + "leading/trailing whitespace. Got: '" + id + "'. "
              + "Check agents/<id>.json filename formatting.");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalStateException(
                "AgentSpec systemPrompt must be non-null and non-blank. "
              + "Got: '" + systemPrompt + "'. "
              + "Check the 'systemPrompt' field in agents/<id>.json.");
        }
        if (primaryModel == null) {
            throw new IllegalStateException(
                "AgentSpec primaryModel must be non-null. "
              + "The 'primaryModel' field in agents/<id>.json must parse "
              + "via ModelRef (e.g., \"ollama:qwen3:1.7b\").");
        }
    }
}
