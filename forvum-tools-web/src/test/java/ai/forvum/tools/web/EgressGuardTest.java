package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF egress policy for {@code web.fetch} (the only SSRF-exposed surface: an arbitrary model-supplied
 * URL). The guard blocks loopback / link-local / private / multicast / wildcard addresses by default and
 * permits public hosts; {@code allowPrivateNetwork} opts the operator back in. Pure logic, no network: a
 * literal-IP host needs no DNS, so these assertions are deterministic.
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
        assertThrows(EgressDeniedException.class, () -> guard.check(null));
        assertThrows(EgressDeniedException.class, () -> guard.check("   "));
    }
}
