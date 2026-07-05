package ai.forvum.tools.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, Quarkus-free HTML parsing for the DuckDuckGo HTML endpoint ({@code html.duckduckgo.com/html},
 * #192) — a faithful port of OpenClaw's {@code ddg-client.ts} contract ([P2-11] reproduce-behavior). No
 * network, no CDI, no reflection: it turns a fetched page String into {@link SearchResult}s, so it is
 * fully unit-testable against saved fixtures.
 *
 * <p>The endpoint returns server-rendered HTML: each result is an {@code <a class="result__a" href=...>}
 * anchor (title + the DDG redirect URL) followed by an {@code <a class="result__snippet">}. Titles/snippets
 * carry inline tags ({@code <b>}) and HTML entities; the redirect URL wraps the real target in a
 * {@code uddg} query param (sometimes scheme-relative {@code //duckduckgo.com/l/?uddg=...}). This class
 * strips tags, decodes entities, and unwraps {@code uddg}. When the page carries NO {@code result__a}
 * anchor and matches DDG's anomaly markers, {@link #isBotChallenge} flags a bot-detection challenge (the
 * caller throws {@link WebSearchException}); a genuine no-results page simply parses to an empty list.
 */
final class DuckDuckGoHtmlParser {

    private DuckDuckGoHtmlParser() {
    }

    // A result anchor: <a ... class="...result__a..." ...>title</a>. The lookahead makes the class-attribute
    // order-independent (class may precede or follow href), mirroring the OpenClaw regex.
    private static final Pattern RESULT_ANCHOR = Pattern.compile(
            "<a\\b(?=[^>]*\\bclass=\"[^\"]*\\bresult__a\\b[^\"]*\")([^>]*)>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // The opening tag of the NEXT result anchor — used to scope a result's snippet search to its own block.
    private static final Pattern NEXT_RESULT_ANCHOR = Pattern.compile(
            "<a\\b(?=[^>]*\\bclass=\"[^\"]*\\bresult__a\\b[^\"]*\")[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SNIPPET_ANCHOR = Pattern.compile(
            "<a\\b(?=[^>]*\\bclass=\"[^\"]*\\bresult__snippet\\b[^\"]*\")[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern HREF = Pattern.compile(
            "\\bhref=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHALLENGE = Pattern.compile(
            "g-recaptcha|are you a human|id=\"challenge-form\"|name=\"challenge\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a DDG HTML page into results. A well-formed page with no results yields an empty list; a page
     * whose markup has drifted (no {@code result__a}) also yields empty — the caller distinguishes drift
     * from a genuine no-results page and from a challenge (via {@link #isBotChallenge}).
     */
    static List<SearchResult> parse(String html) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return results;
        }
        Matcher m = RESULT_ANCHOR.matcher(html);
        while (m.find()) {
            String rawAttributes = m.group(1) == null ? "" : m.group(1);
            String rawTitle = m.group(2) == null ? "" : m.group(2);

            String rawUrl = firstHref(rawAttributes);

            // Scope the snippet search to the text BEFORE the next result anchor, so a result gets its own
            // snippet (not the following result's).
            String trailing = html.substring(m.end());
            Matcher next = NEXT_RESULT_ANCHOR.matcher(trailing);
            String scoped = next.find() ? trailing.substring(0, next.start()) : trailing;
            String rawSnippet = "";
            Matcher snip = SNIPPET_ANCHOR.matcher(scoped);
            if (snip.find()) {
                rawSnippet = snip.group(1) == null ? "" : snip.group(1);
            }

            String title = decodeEntities(stripTags(rawTitle));
            String url = unwrapUddg(decodeEntities(rawUrl));
            String snippet = decodeEntities(stripTags(rawSnippet));

            if (!title.isEmpty() && !url.isEmpty()) {
                results.add(new SearchResult(title, url, snippet));
            }
        }
        return results;
    }

    /**
     * Whether {@code html} is a DuckDuckGo bot-detection challenge rather than a results page. Conservative
     * (OpenClaw's rule): if ANY {@code result__a} anchor is present it is NOT a challenge (real results
     * won); only when there are none AND an anomaly marker matches do we treat it as a challenge.
     */
    static boolean isBotChallenge(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        if (RESULT_ANCHOR.matcher(html).find()) {
            return false;
        }
        return CHALLENGE.matcher(html).find();
    }

    /** The first {@code href="..."} attribute value in a tag's attribute text, or empty when absent. */
    private static String firstHref(String attributes) {
        Matcher m = HREF.matcher(attributes);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Unwrap DuckDuckGo's {@code /l/?uddg=<encoded target>} redirect to the real target URL. Handles the
     * scheme-relative form ({@code //duckduckgo.com/l/?uddg=...}) by prefixing {@code https:}. When the URL
     * is already direct (no {@code uddg} param) it is returned unchanged.
     */
    static String unwrapUddg(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return "";
        }
        String normalized = rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl;
        try {
            URI uri = new URI(normalized);
            String uddg = queryParam(uri.getRawQuery(), "uddg");
            if (uddg != null && !uddg.isEmpty()) {
                return java.net.URLDecoder.decode(uddg, StandardCharsets.UTF_8);
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            // A DDG-direct link (or an odd value) — keep the original.
        }
        return rawUrl;
    }

    /** Extract a single raw (still percent-encoded) query-parameter value from a raw query string. */
    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Replace inline tags with a space and collapse whitespace (the OpenClaw {@code stripHtml}). */
    static String stripTags(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String noTags = TAG.matcher(html).replaceAll(" ");
        return WHITESPACE.matcher(noTags).replaceAll(" ").trim();
    }

    /** Decode the HTML entities DuckDuckGo emits (named + numeric decimal + numeric hex). */
    static String decodeEntities(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&nbsp;", " ")
                .replace("&ndash;", "-")
                .replace("&mdash;", "--")
                .replace("&hellip;", "...");
        out = replaceAll(NUMERIC_ENTITY.matcher(out), 10);
        out = replaceAll(HEX_ENTITY.matcher(out), 16);
        return out;
    }

    /** Replace every numeric character reference the matcher finds with the code point it names. */
    private static String replaceAll(Matcher m, int radix) {
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                // IllegalArgumentException covers both parseInt (NumberFormatException) and
                // Character.toChars (an out-of-range code point) — NFE is a subclass of it.
                replacement = new String(Character.toChars(Integer.parseInt(m.group(1), radix)));
            } catch (IllegalArgumentException e) {
                replacement = m.group();   // leave a malformed / out-of-range reference verbatim.
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
