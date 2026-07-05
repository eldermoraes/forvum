package ai.forvum.tools.web;

import java.util.List;

/**
 * The module-internal seam a {@code web.search} backend implements (#192). Deliberately NOT a public
 * {@code forvum-sdk} SPI — the issue binds this placement: swapping the search provider is a
 * {@code forvum-tools-web} implementation detail (edit {@code tools/web.json}'s {@code "backend"}), not a
 * new Layer-1 contract. So the interface is package-private and carries only JDK types.
 *
 * <p>Two implementations ship in v0.1: {@link DuckDuckGoBackend} (the keyless default) and
 * {@link BraveBackend} (the keyed option). {@link WebSearchTool} selects one per invocation from the live
 * spec (precedence in {@link WebSearchTool#search}) and renders the common {@link SearchResult} list to the
 * model-facing text block, so the output shape is byte-identical across backends. Room for
 * Tavily/Google/SearXNG is a single new class + switch case each (§13 simplicity — not shipped now).
 */
interface WebSearchBackend {

    /**
     * Run a search for {@code query}, returning at most {@code count} results (the caller has already
     * clamped {@code count} to {@code [1, MAX_COUNT]}). A backend that must reach the network runs it
     * blocking on the turn's virtual thread (no Mutiny). An empty list means "no results" (a valid,
     * successful outcome the tool renders as {@code "no results."}).
     *
     * @throws WebSearchException on a runtime failure (non-200, a bot-detection challenge, unparseable
     *                            markup) — the engine audits it {@code error} and renders it back to the
     *                            model as the tool result; the turn still completes.
     */
    List<SearchResult> search(String query, int count);
}
