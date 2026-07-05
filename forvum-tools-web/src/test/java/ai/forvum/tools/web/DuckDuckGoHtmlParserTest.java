package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pure fixture-driven contract for the DuckDuckGo HTML parser (#192): result extraction, {@code uddg}
 * redirect unwrap (direct / wrapped / scheme-relative), entity decode, tag strip, anchor filtering, and
 * bot-challenge detection. Hermetic — every input is a saved fixture or a small literal, no network. The
 * degrade cases (no-results, challenge, drifted markup) feed the backend's {@link WebSearchException} path.
 */
class DuckDuckGoHtmlParserTest {

    @Test
    void parsesResultsFromTheSavedPage() {
        List<SearchResult> results = DuckDuckGoHtmlParser.parse(TestFixtures.load("ddg-results.html"));

        assertEquals(3, results.size(), () -> "expected 3 results, got " + results);

        SearchResult first = results.get(0);
        assertEquals("Asynchronous Programming in Rust", first.title(), "tags stripped from the title");
        assertEquals("https://rust-lang.github.io/async-book/", first.url(), "uddg unwrapped from the href");
        assertTrue(first.snippet().contains("async book"), first.snippet());
        assertTrue(first.snippet().contains("&") || first.snippet().contains("explained"),
                "entities decoded in the snippet: " + first.snippet());

        SearchResult second = results.get(1);
        assertEquals("https://tokio.rs/", second.url(), "a direct href is kept verbatim");
        assertTrue(second.title().contains("Tokio"), second.title());

        SearchResult third = results.get(2);
        assertEquals("https://doc.rust-lang.org/std/keyword.async.html", third.url());
    }

    @Test
    void unwrapsAWrappedUddgUrl() {
        assertEquals("https://example.com/page?a=1&b=2",
                DuckDuckGoHtmlParser.unwrapUddg(
                        "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1%26b%3D2&rut=x"));
    }

    @Test
    void unwrapsAnAbsoluteUddgUrl() {
        assertEquals("https://example.org/",
                DuckDuckGoHtmlParser.unwrapUddg(
                        "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2F"));
    }

    @Test
    void keepsADirectUrlUnchanged() {
        assertEquals("https://tokio.rs/", DuckDuckGoHtmlParser.unwrapUddg("https://tokio.rs/"));
        assertEquals("", DuckDuckGoHtmlParser.unwrapUddg(""));
        assertEquals("", DuckDuckGoHtmlParser.unwrapUddg(null));
    }

    @Test
    void decodesNamedNumericAndHexEntities() {
        assertEquals("a & b < c > d \" e ' f",
                DuckDuckGoHtmlParser.decodeEntities("a &amp; b &lt; c &gt; d &quot; e &#39; f"));
        assertEquals("'/'", DuckDuckGoHtmlParser.decodeEntities("&#x27;&#x2F;&#x27;"));
        assertEquals("A", DuckDuckGoHtmlParser.decodeEntities("&#65;"));
        assertEquals("—", DuckDuckGoHtmlParser.decodeEntities("&#8212;"));
    }

    @Test
    void leavesAMalformedEntityVerbatim() {
        assertEquals("&#xZZ;", DuckDuckGoHtmlParser.decodeEntities("&#xZZ;"));
    }

    @Test
    void stripsInlineTagsAndCollapsesWhitespace() {
        assertEquals("bold and code",
                DuckDuckGoHtmlParser.stripTags("<b>bold</b>   and <code>code</code>"));
        assertEquals("", DuckDuckGoHtmlParser.stripTags(""));
    }

    @Test
    void skipsAnchorsMissingHrefOrTitle() {
        // A result__a with no href and one with an empty title must both be dropped.
        String html = """
                <a class="result__a">no href here</a>
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fok.example%2F"><b></b></a>
                <a class="result__a" href="https://good.example/">Good</a>
                """;
        List<SearchResult> results = DuckDuckGoHtmlParser.parse(html);
        assertEquals(1, results.size(), () -> "only the fully-formed anchor survives: " + results);
        assertEquals("https://good.example/", results.get(0).url());
        assertEquals("Good", results.get(0).title());
    }

    @Test
    void noResultsPageParsesToEmptyAndIsNotAChallenge() {
        String html = TestFixtures.load("ddg-no-results.html");
        assertTrue(DuckDuckGoHtmlParser.parse(html).isEmpty(), "no results anchors");
        assertFalse(DuckDuckGoHtmlParser.isBotChallenge(html), "a genuine no-results page is not a challenge");
    }

    @Test
    void challengePageIsDetected() {
        String html = TestFixtures.load("ddg-challenge.html");
        assertTrue(DuckDuckGoHtmlParser.parse(html).isEmpty(), "a challenge page has no result anchors");
        assertTrue(DuckDuckGoHtmlParser.isBotChallenge(html), "challenge markers detected when no results");
    }

    @Test
    void driftedMarkupParsesToEmptyAndIsNotAChallenge() {
        String html = TestFixtures.load("ddg-drifted.html");
        assertTrue(DuckDuckGoHtmlParser.parse(html).isEmpty(),
                "renamed result classes yield no parseable results (the drift/degrade path)");
        assertFalse(DuckDuckGoHtmlParser.isBotChallenge(html), "drift is not a bot challenge");
    }

    @Test
    void aPageWithResultAnchorsIsNeverAChallenge() {
        // Even if a challenge marker string appears, the presence of result__a means results won.
        String html = "<a class=\"result__a\" href=\"https://x.example/\">X</a> are you a human";
        assertFalse(DuckDuckGoHtmlParser.isBotChallenge(html));
    }
}
