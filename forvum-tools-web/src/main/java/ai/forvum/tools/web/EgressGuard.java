package ai.forvum.tools.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Self-contained SSRF egress policy for {@code web.fetch} (the only SSRF-exposed web tool: it fetches a
 * fully arbitrary, model-supplied URL). ULTRAPLAN §1.1 promises a shared engine egress decorator, but no
 * such bean exists yet and a Layer-3 tool must not depend on the engine, so v0.1 ships this minimal,
 * self-contained guard inside {@code forvum-tools-web} (the maintainer-ratified default; the shared engine
 * policy is deferred). It is the egress analogue of the filesystem module's {@code WorkspaceRoot}.
 *
 * <p>By default it rejects anything that is not a public {@code http(s)} host: a non-http(s) scheme, a
 * hostless URL, a port outside the {@link #allowedPorts} allowlist, and any resolved address that is
 * loopback, link-local (incl. the cloud metadata endpoint {@code 169.254.169.254}), site-local/private
 * ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}), the carrier-grade-NAT range
 * ({@code 100.64.0.0/10}), an IPv6 unique-local address ({@code fc00::/7}), an IPv4-mapped/-compatible or
 * NAT64 ({@code 64:ff9b::/96}) embedding of one of those ranges, multicast, or the wildcard
 * {@code 0.0.0.0}/{@code ::}. {@code allowPrivateNetwork} (operator opt-in via {@code tools/web.json})
 * disables the address-range AND port block so an operator can fetch a trusted intranet host on any port;
 * the scheme/host validation always applies.
 *
 * <p>A literal-IP host is checked without DNS; a name host is resolved once via
 * {@link InetAddress#getAllByName(String)} and EVERY resolved address must pass, and the FIRST resolved
 * address is returned in {@link Approved} as the {@code pinnedAddress} so an HTTP fetch can connect to that
 * validated literal IP (closing the DNS-rebind window for HTTP — see {@link WebFetchTool} / B2). The
 * blocking lookup runs on the turn's virtual thread (no Mutiny, no synchronized).
 *
 * <p><strong>Residual HTTPS DNS-rebind window.</strong> For an {@code https} target the JDK
 * {@code HttpClient} cannot connect to a literal IP without breaking SNI/certificate verification (it has
 * no per-request address pin nor SNI override), so HTTPS is NOT IP-pinned. {@link #recheck(URI)} re-resolves
 * and re-validates the host immediately before each hop's send, which NARROWS but does NOT fully eliminate
 * the window between that re-check and the client's own connect-time resolution. A full close requires the
 * deferred shared engine egress decorator (ULTRAPLAN §1.1); this residual is the maintainer-ratified v0.1
 * trade-off (the redirect loop + HTTP pin already close the loopback/IMDS/HTTP-service vectors).
 */
public final class EgressGuard {

    /** The destination ports {@code web.fetch} permits by default (defense-in-depth, B4). */
    static final Set<Integer> DEFAULT_ALLOWED_PORTS = Set.of(80, 443, -1);   // -1 = scheme default port.

    private final boolean allowPrivateNetwork;
    private final Set<Integer> allowedPorts;

    public EgressGuard(boolean allowPrivateNetwork) {
        this(allowPrivateNetwork, DEFAULT_ALLOWED_PORTS);
    }

    /**
     * @param allowPrivateNetwork operator opt-in: disable the address-range and port block entirely.
     * @param allowedPorts        the destination ports permitted when {@code allowPrivateNetwork} is off
     *                            (operator-widenable via {@code tools/web.json}); {@code null}/empty falls
     *                            back to {@link #DEFAULT_ALLOWED_PORTS}.
     */
    public EgressGuard(boolean allowPrivateNetwork, Set<Integer> allowedPorts) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.allowedPorts = (allowedPorts == null || allowedPorts.isEmpty())
                ? DEFAULT_ALLOWED_PORTS
                : Set.copyOf(allowedPorts);
    }

    /**
     * The result of an approved egress check: the parsed {@link URI} and the validated address an HTTP
     * fetch may pin to. An internal value never JSON-serialized, so (unlike a model-facing DTO) it carries
     * NO {@code @RegisterForReflection}.
     *
     * @param uri           the validated target URI (callers reuse it without re-parsing).
     * @param pinnedAddress the validated address to connect to for an HTTP target, or {@code null} when no
     *                      pin applies ({@code allowPrivateNetwork} on, or a scheme that is not pinned).
     */
    public record Approved(URI uri, InetAddress pinnedAddress) {
    }

    /**
     * Validate {@code url} for {@code web.fetch}, throwing {@link EgressDeniedException} if it is denied.
     * Returns the {@link Approved} target on success.
     */
    public Approved check(String url) {
        if (url == null || url.isBlank()) {
            throw new EgressDeniedException("web.fetch requires a non-blank URL.");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new EgressDeniedException("web.fetch URL is not a valid URI: " + url);
        }
        return check(uri);
    }

    /**
     * Validate an already-parsed {@code uri} (used for a redirect {@code Location} resolved against the
     * current hop), throwing {@link EgressDeniedException} if it is denied. Returns the {@link Approved}
     * target on success.
     */
    public Approved check(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new EgressDeniedException(
                    "web.fetch only supports http/https URLs; refused scheme for: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressDeniedException("web.fetch URL has no host: " + uri);
        }
        if (allowPrivateNetwork) {
            return new Approved(uri, null);
        }
        if (!allowedPorts.contains(uri.getPort())) {
            throw new EgressDeniedException(
                    "web.fetch to port " + uri.getPort() + " is denied (allowed: " + allowedPorts
                  + "). Set allowPrivateNetwork or widen allowedPorts in tools/web.json to opt in.");
        }
        InetAddress[] addresses = resolve(host);
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new EgressDeniedException(
                        "web.fetch to an internal/private/loopback address is denied (host '" + host
                      + "'). Set allowPrivateNetwork in tools/web.json to opt in.");
            }
        }
        return new Approved(uri, addresses[0]);
    }

    /**
     * E-ii connect-time re-check: immediately before a hop's actual send, re-resolve the host and
     * re-validate it through the same {@link #isInternal} policy, so a name that has FLIPPED to an internal
     * address since {@link #check(String)}'s lookup (a short-TTL DNS-rebind) is denied. Returns the
     * re-pinned {@link Approved}. With {@code allowPrivateNetwork} on this is a no-op pass.
     *
     * <p>This narrows but does NOT fully close the HTTPS rebind window (a race remains between this
     * resolution and the JDK client's own connect-time resolution); see the class Javadoc.
     */
    public Approved recheck(URI uri) {
        return check(uri);
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new EgressDeniedException("web.fetch could not resolve host '" + host + "'.");
        }
    }

    /** Whether {@code address} is non-routable from the public internet and must be blocked by default. */
    static boolean isInternal(InetAddress address) {
        // Unwrap an IPv4-mapped / IPv4-compatible / NAT64-embedded IPv6 address to its embedded IPv4 and
        // run the IPv4 predicates on it, so e.g. ::ffff:127.0.0.1 / ::127.0.0.1 / 64:ff9b::7f00:1 cannot
        // smuggle a loopback/private v4 past the v6 checks.
        InetAddress effective = unwrapEmbeddedIpv4(address);
        if (effective.isLoopbackAddress()
                || effective.isAnyLocalAddress()      // 0.0.0.0 / ::
                || effective.isLinkLocalAddress()     // 169.254.x.x / fe80::
                || effective.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
                || effective.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = effective.getAddress();
        if (effective instanceof Inet4Address) {
            // CGNAT 100.64.0.0/10 (RFC 6598): first byte 100, second byte 64..127.
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }
        // IPv6 unique-local fc00::/7 (RFC 4193): top 7 bits == 1111110 (covers fc00::/8 + fd00::/8).
        // Java's isSiteLocalAddress checks the DEPRECATED fec0::/10, not fc00::/7, so add it explicitly.
        return (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * If {@code address} is an IPv6 address embedding an IPv4 one — IPv4-mapped ({@code ::ffff:a.b.c.d}),
     * IPv4-compatible ({@code ::a.b.c.d}), or NAT64 ({@code 64:ff9b::a.b.c.d}, RFC 6052) — return the
     * embedded IPv4 address so the IPv4 predicates apply; otherwise return {@code address} unchanged. The
     * JDK already collapses {@code ::ffff:a.b.c.d} to an {@link Inet4Address}, so this handles the
     * compatible and NAT64 forms the parser leaves as {@link Inet6Address}.
     */
    private static InetAddress unwrapEmbeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address v6)) {
            return address;
        }
        byte[] b = v6.getAddress();   // 16 bytes
        boolean nat64 = b[0] == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xFF && b[3] == (byte) 0x9B
                && allZero(b, 4, 12);
        boolean compatible = allZero(b, 0, 12)
                // a real ::a.b.c.d, not :: (any-local) or ::1 (loopback, already caught above)
                && !(b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1));
        if (nat64 || compatible) {
            byte[] v4 = { b[12], b[13], b[14], b[15] };
            try {
                return InetAddress.getByAddress(v4);
            } catch (UnknownHostException e) {
                return address;   // 4-byte array never throws; defensive only.
            }
        }
        return address;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
