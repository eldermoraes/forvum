package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.Set;

/**
 * SSRF egress policy for {@code web.fetch} (the only SSRF-exposed surface: an arbitrary model-supplied
 * URL). The guard blocks loopback / link-local / private / CGNAT / IPv6-ULA / mapped-or-NAT64-embedded /
 * multicast / wildcard addresses by default, restricts the port to an allowlist, and permits public hosts;
 * {@code allowPrivateNetwork} opts the operator back in. Pure logic, no network: a literal-IP host (incl.
 * the v6 and embedded forms below) needs no DNS, so these assertions are deterministic.
 */
class EgressGuardTest {

    private final EgressGuard guard = new EgressGuard(false);
    private final EgressGuard permissive = new EgressGuard(true);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://127.1.2.3/x",
            "http://[::1]/x",
            "http://localhost/x",
            "http://10.0.0.5/x",
            "http://172.16.4.4/x",
            "http://172.31.255.1/x",
            "http://192.168.1.1/x",
            "http://169.254.169.254/latest/meta-data",   // the classic cloud metadata SSRF target
            "http://[fe80::1]/x",                          // IPv6 link-local
            "http://0.0.0.0/x"                             // wildcard
    })
    void blocksInternalAddressesByDefault(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "an internal/private/link-local target must be denied by default: " + url);
    }

    // --- B3: IPv6 ULA, IPv4-mapped/-compatible, NAT64, CGNAT, and encoded IPv4 ---------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[fc00::1]/x",                  // IPv6 unique-local fc00::/8 (Java misses this)
            "http://[fd00::1]/x",                  // IPv6 unique-local fd00::/8 (the common ULA prefix)
            "http://[fdff:ffff::1]/x"
    })
    void blocksIpv6UniqueLocalAddresses(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "an IPv6 unique-local (fc00::/7) target must be denied: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[::ffff:10.0.0.1]/x",          // IPv4-mapped private 10/8
            "http://[::ffff:127.0.0.1]/x",         // IPv4-mapped loopback
            "http://[::ffff:7f00:1]/x",            // same loopback, hex IPv4 form
            "http://[::ffff:169.254.169.254]/x"    // IPv4-mapped metadata endpoint
    })
    void blocksIpv4MappedInternalAddresses(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "an IPv4-mapped IPv6 wrapping an internal v4 must be denied: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[64:ff9b::7f00:1]/x",          // NAT64 64:ff9b::/96 of 127.0.0.1
            "http://[64:ff9b::127.0.0.1]/x",       // NAT64 dotted form of 127.0.0.1
            "http://[64:ff9b::a00:1]/x",           // NAT64 of 10.0.0.1
            "http://[::127.0.0.1]/x",              // IPv4-compatible ::a.b.c.d of loopback
            "http://[::a00:1]/x"                   // IPv4-compatible of 10.0.0.1
    })
    void blocksNat64AndIpv4CompatibleInternalAddresses(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "a NAT64 / IPv4-compatible embedding of an internal v4 must be denied: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://100.64.0.1/x",                 // CGNAT 100.64.0.0/10 low edge
            "http://100.127.255.255/x"             // CGNAT high edge
    })
    void blocksCarrierGradeNat(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "a CGNAT (100.64.0.0/10) target must be denied: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://2130706433/x",                 // decimal IPv4 == 127.0.0.1 (the JDK parses this)
            "http://0x7f000001/x"                  // hex IPv4 form: the JDK rejects it → UnknownHost
    })
    void blocksEncodedLoopbackIpv4(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "an encoded IPv4 that resolves to (or fails to resolve away from) loopback is denied: "
                        + url);
    }

    @Test
    void blocksUserinfoMetadataHostParse() {
        // The userinfo (a@) must NOT be confused for the host: the host is 169.254.169.254 (link-local).
        assertThrows(EgressDeniedException.class,
                () -> guard.check("http://a@169.254.169.254/latest/meta-data"),
                "userinfo must not smuggle the metadata endpoint past the host check");
    }

    // --- B4: port allowlist (defense-in-depth) ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8:6379/x",               // Redis
            "http://8.8.8.8:11434/x",              // Ollama
            "http://8.8.8.8:9200/x",               // Elasticsearch
            "http://8.8.8.8:8080/x"                // a generic non-allowlisted port
    })
    void blocksDisallowedPortsByDefault(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "a port outside {80,443,default} is denied by default: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8:80/x",
            "https://8.8.8.8:443/x",
            "http://8.8.8.8/x"                     // default port (-1)
    })
    void allowsAllowlistedPorts(String url) {
        assertDoesNotThrow(() -> guard.check(url), "an allowlisted port must pass: " + url);
    }

    @Test
    void operatorCanWidenAllowedPorts() {
        EgressGuard widened = new EgressGuard(false, Set.of(80, 443, -1, 8080));
        assertDoesNotThrow(() -> widened.check("http://8.8.8.8:8080/x"),
                "an operator-widened port passes");
        assertThrows(EgressDeniedException.class, () -> widened.check("http://8.8.8.8:6379/x"),
                "a port still outside the widened set is denied");
    }

    @Test
    void allowPrivateNetworkBypassesPortRestriction() {
        assertDoesNotThrow(() -> permissive.check("http://127.0.0.1:6379/x"),
                "allowPrivateNetwork opts back in regardless of port (intranet on any port)");
    }

    // --- existing public / opt-in / scheme / null contracts --------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/x",
            "https://example.com/path",
            "https://api.search.brave.com/res/v1/web/search"
    })
    void allowsPublicAddresses(String url) {
        assertDoesNotThrow(() -> guard.check(url), "a public target must pass: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://192.168.1.1/x",
            "http://169.254.169.254/x"
    })
    void allowsInternalWhenPrivateNetworkOptedIn(String url) {
        assertDoesNotThrow(() -> permissive.check(url),
                "allowPrivateNetwork opts an internal target back in: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/x",          // non-http scheme
            "file:///etc/passwd",           // local file
            "gopher://example.com/x",
            "not-a-url",
            "http://",                      // no host
            "https:///nohost"
    })
    void rejectsNonHttpOrHostlessUrls(String url) {
        assertThrows(EgressDeniedException.class, () -> guard.check(url),
                "only http/https with a host are permitted: " + url);
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(EgressDeniedException.class, () -> guard.check((String) null));
        assertThrows(EgressDeniedException.class, () -> guard.check("   "));
    }

    // --- B2: the approval carries the pinned address for an HTTP target --------------------------

    @Test
    void httpCheckReturnsPinnedAddress() throws Exception {
        EgressGuard.Approved approved = guard.check("http://8.8.8.8/x");
        assertNotNull(approved.pinnedAddress(),
                "an HTTP approval pins the validated address so the fetch connects to that literal IP");
        assertEquals(InetAddress.getByName("8.8.8.8"), approved.pinnedAddress());
        assertEquals("8.8.8.8", approved.uri().getHost());
    }

    @Test
    void httpsCheckReturnsPinnedAddressButFetcherDoesNotRewrite() {
        // For HTTPS the address is still validated + returned, but JdkHttpFetcher must NOT IP-rewrite it
        // (SNI/cert). That fetcher behavior is asserted in JdkHttpFetcherTest; here we only show check()
        // succeeds and pins the resolved address (literal IP, deterministic).
        EgressGuard.Approved approved = guard.check("https://8.8.8.8/x");
        assertNotNull(approved.pinnedAddress());
    }

    @Test
    void privateNetworkApprovalCarriesNoPin() {
        EgressGuard.Approved approved = permissive.check("http://127.0.0.1/x");
        assertNull(approved.pinnedAddress(),
                "with allowPrivateNetwork on there is no resolution → no pin (the URI is used as-is)");
    }

    // --- E-ii: recheck re-validates immediately before the send ----------------------------------

    @Test
    void recheckPassesForAPublicLiteral() {
        EgressGuard.Approved approved = guard.check("http://8.8.8.8/x");
        assertDoesNotThrow(() -> guard.recheck(approved.uri()),
                "a still-public host passes the connect-time re-check");
    }

    @Test
    void recheckDeniesAnInternalLiteral() {
        // A literal that is internal can never have passed check(), but recheck is the same policy, so it
        // denies an internal target — the guard's connect-time gate (and the green-for-wrong-reason proof
        // that recheck is the real isInternal policy, not a pass-through).
        assertThrows(EgressDeniedException.class,
                () -> guard.recheck(java.net.URI.create("http://169.254.169.254/x")),
                "recheck re-runs the full internal-address policy");
    }
}
