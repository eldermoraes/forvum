package ai.forvum.tools.web;

/**
 * One web-search result, normalized across backends: a title, an absolute result URL, and a snippet
 * ({@code web.search}, #192). A module-internal value the backends build by hand and {@link WebSearchTool}
 * renders — NEVER JSON-(de)serialized (each backend maps its own wire shape into this record). It is
 * therefore deliberately OUTSIDE the {@code .dto} package and carries NO {@code @RegisterForReflection}
 * (the {@link EgressGuard.Approved} precedent, and it keeps {@code .github/reflection-registration.sh}
 * — which greps {@code .dto.}-package records — clean; native needs no hint for a never-reflected record).
 *
 * @param title   the result title (may be empty; a URL-less result is dropped by the renderer, not here).
 * @param url     the absolute result URL.
 * @param snippet the result snippet/description (may be empty).
 */
record SearchResult(String title, String url, String snippet) {
}
