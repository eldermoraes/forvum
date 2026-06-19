package ai.forvum.engine.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import ai.forvum.core.ModelRef;

/**
 * One entry in a fallback chain: a {@link ModelRef} (for ledger attribution) paired with its resolved
 * LangChain4j model(s). Engine-local on purpose — the resolved-runtime twin of the declared
 * {@code forvum-core.FallbackChain} (ULTRAPLAN section 4.3.5.3 / DR-4c): {@code LlmSelector} resolves
 * each {@code FallbackChain.links()} ref to a {@code FallbackLink} the M8 decorators walk.
 * {@code chat} is used by {@link FallbackChatModel}, {@code streaming} by
 * {@link FallbackStreamingChatModel}; either may be {@code null} for a link used in only one mode.
 */
public record FallbackLink(ModelRef ref, ChatModel chat, StreamingChatModel streaming) {
}
