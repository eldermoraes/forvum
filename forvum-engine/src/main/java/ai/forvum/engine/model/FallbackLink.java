package ai.forvum.engine.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import ai.forvum.core.ModelRef;

/**
 * One entry in a fallback chain: a {@link ModelRef} (for ledger attribution) paired with its resolved
 * LangChain4j model(s). Engine-local on purpose — the public {@code forvum-core.FallbackChain} is still
 * TBD (ULTRAPLAN section 4.3.5.3 / Group 4c); when it is ratified, only the decorator constructors
 * adapt. {@code chat} is used by {@link FallbackChatModel}, {@code streaming} by
 * {@link FallbackStreamingChatModel}; either may be {@code null} for a link used in only one mode.
 */
public record FallbackLink(ModelRef ref, ChatModel chat, StreamingChatModel streaming) {
}
