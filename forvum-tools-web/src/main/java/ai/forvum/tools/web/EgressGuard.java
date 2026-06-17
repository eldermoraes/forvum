package ai.forvum.tools.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Self-contained SSRF egress policy for {@code web.fetch} (the only SSRF-exposed web tool: it fetches a
 * fully arbitrary, model-supplied URL). ULTRAPLAN §1.1 promises a shared engine egress decorator, but no
 * such bean exists yet and a Layer-3 tool must not depend on the engine, so v0.1 ships this minimal,
 * self-contained guard inside {@code forvum-tools-web} (the maintainer-ratified default; the shared engine
 * policy is deferred). It is the egress analogue of the filesystem module's {@code WorkspaceRoot}.
 *
 * <p>By default it rejects anything that is not a public {@code http(s)} host: a non-http(s) scheme, a
 * hostless URL, and any resolved address that is loopback, link-local (incl. the cloud metadata endpoint
 * {@code 169.254.169.254}), site-local/private ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}),
 * multicast, the wildcard {@code 0.0.0.0}/{@code ::}, or otherwise non-routable. {@code allowPrivateNetwork}
 * (operator opt-in via {@code tools/web.json}) disables the address-range block so an operator can fetch a
 * trusted intranet host; the scheme/host validation always applies.
 *
 * <p>A literal-IP host is checked without DNS; a name host is resolved once via
 * {@link InetAddress#getAllByName(String)} and EVERY resolved address must pass (so a name that resolves to
 * an internal IP — DNS-rebinding-style — is still blocked). The blocking call runs on the turn's virtual
 * thread (no Mutiny, no synchronized).
 */
public final class EgressGuard {

    private final boolean allowPrivateNetwork;

    public EgressGuard(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /**
     * Validate {@code url} for {@code web.fetch}, throwing {@link EgressDeniedException} if it is denied.
     * Returns the parsed {@link URI} on success (callers reuse it without re-parsing).
     */
    public URI check(String url) {
        if (url == null || url.isBlank()) {
            throw new EgressDeniedException("web.fetch requires a non-blank URL.");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new EgressDeniedException("web.fetch URL is not a valid URI: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new EgressDeniedException(
                    "web.fetch only supports http/https URLs; refused scheme for: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressDeniedException("web.fetch URL has no host: " + url);
        }
        if (allowPrivateNetwork) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new EgressDeniedException("web.fetch could not resolve host '" + host + "'.");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new EgressDeniedException(
                        "web.fetch to an internal/private/loopback address is denied (host '" + host
                      + "'). Set allowPrivateNetwork in tools/web.json to opt in.");
            }
        }
        return uri;
    }

    /** Whether {@code address} is non-routable from the public internet and must be blocked by default. */
    static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()        // 0.0.0.0 / ::
                || address.isLinkLocalAddress()        // 169.254.x.x / fe80::
                || address.isSiteLocalAddress()        // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress();
    }
}
