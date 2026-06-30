package ai.forvum.channel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.DeviceCredential;

import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HandshakeRequest;
import io.smallrye.mutiny.Uni;

import org.junit.jupiter.api.Test;

import java.security.Permission;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Web channel's #166 credential propagation: {@link ChatSocket#credentialFor} turns the
 * handshake-authenticated principal into the {@link DeviceCredential} carried into the turn. The operator
 * (the host, #165) propagates {@link DeviceCredential#ABSENT} so it keeps full scopes; a device-role
 * principal propagates the device id (its principal) plus the token it presented at the handshake, so the
 * engine re-authenticates it and intersects its approvedScopes. Plain unit test (no socket / no boot).
 */
class ChatSocketCredentialTest {

    @Test
    void anOperatorPropagatesAbsentSoTheHostKeepsFullScopes() {
        SecurityIdentity operator = identity("operator", "operator");
        assertSame(DeviceCredential.ABSENT,
                ChatSocket.credentialFor(operator, handshake(null, "access_token=op-secret")),
                "the host operator is not a paired device — it carries no device credential");
    }

    @Test
    void aDevicePropagatesItsIdAndTheHandshakeToken() {
        SecurityIdentity device = identity("web", ChatSocket.DEVICE_ROLE);
        DeviceCredential credential = ChatSocket.credentialFor(device, handshake(null, "access_token=dev-secret"));
        assertEquals("web", credential.deviceId(), "the device id is the authenticated principal");
        assertEquals("dev-secret", credential.token(), "the presented handshake token rides the credential");
    }

    @Test
    void aDeviceReadsTheBearerHeaderWhenPresent() {
        SecurityIdentity device = identity("web", ChatSocket.DEVICE_ROLE);
        DeviceCredential credential = ChatSocket.credentialFor(device, handshake("Bearer hdr-token", null));
        assertEquals("hdr-token", credential.token(), "an Authorization: Bearer header is read before the query");
    }

    @Test
    void aUrlEncodedAccessTokenIsDecoded() {
        SecurityIdentity device = identity("web", ChatSocket.DEVICE_ROLE);
        DeviceCredential credential = ChatSocket.credentialFor(device, handshake(null, "access_token=a%2Bb%3Dc"));
        assertEquals("a+b=c", credential.token(), "the query token is URL-decoded");
    }

    @Test
    void aQueryTokenIsStrippedToMirrorTheTransport() {
        // OperatorAuthMechanism.extractToken strips the query token before matching; the engine compares
        // byte-exact, so ChatSocket must strip too or a whitespace-padded token authenticates at the
        // transport then fails the engine compare (a fail-closed inconsistency).
        SecurityIdentity device = identity("web", ChatSocket.DEVICE_ROLE);
        DeviceCredential credential = ChatSocket.credentialFor(device, handshake(null, "access_token=%20padded%20"));
        assertEquals("padded", credential.token(), "the query token is stripped, mirroring the transport");
    }

    @Test
    void anAnonymousOrNullIdentityPropagatesAbsent() {
        assertSame(DeviceCredential.ABSENT,
                ChatSocket.credentialFor(null, handshake(null, "access_token=x")));
        assertSame(DeviceCredential.ABSENT,
                ChatSocket.credentialFor(anonymous(), handshake(null, "access_token=x")));
    }

    private static SecurityIdentity identity(String principal, String role) {
        return new FakeIdentity(principal, role, false);
    }

    private static SecurityIdentity anonymous() {
        return new FakeIdentity(null, null, true);
    }

    /** A minimal {@link SecurityIdentity} double — only the principal, role, and anonymous flag matter. */
    private record FakeIdentity(String principalName, String role, boolean anonymous) implements SecurityIdentity {
        @Override
        public Principal getPrincipal() {
            return anonymous ? null : () -> principalName;
        }

        @Override
        public boolean isAnonymous() {
            return anonymous;
        }

        @Override
        public Set<String> getRoles() {
            return role == null ? Set.of() : Set.of(role);
        }

        @Override
        public boolean hasRole(String roleName) {
            return getRoles().contains(roleName);
        }

        @Override
        public Set<Permission> getPermissions() {
            return Set.of();
        }

        @Override
        public <T extends Credential> T getCredential(Class<T> credentialType) {
            return null;
        }

        @Override
        public Set<Credential> getCredentials() {
            return Set.of();
        }

        @Override
        public <T> T getAttribute(String name) {
            return null;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Uni<Boolean> checkPermission(Permission permission) {
            return Uni.createFrom().item(false);
        }
    }

    /** A minimal {@link HandshakeRequest} double exposing only the Authorization header and the query. */
    private static HandshakeRequest handshake(String authorization, String query) {
        return new HandshakeRequest() {
            @Override
            public String header(String name) {
                return "Authorization".equalsIgnoreCase(name) ? authorization : null;
            }

            @Override
            public List<String> headers(String name) {
                return List.of();
            }

            @Override
            public Map<String, List<String>> headers() {
                return Map.of();
            }

            @Override
            public String scheme() {
                return "ws";
            }

            @Override
            public String host() {
                return "localhost";
            }

            @Override
            public int port() {
                return 8080;
            }

            @Override
            public String path() {
                return "/ws/chat";
            }

            @Override
            public String query() {
                return query;
            }

            @Override
            public String localAddress() {
                return null;
            }

            @Override
            public String remoteAddress() {
                return null;
            }
        };
    }
}
