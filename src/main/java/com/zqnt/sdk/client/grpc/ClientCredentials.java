package com.zqnt.sdk.client.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * The client credential a customer application calls the Zequent platform with.
 *
 * <p>A host application whose credential is not one fixed token (it forwards its own caller's
 * token, or rotates a short-lived one) registers an {@link io.grpc.ClientInterceptor} instead —
 * {@code ZequentClient.builder().interceptor(...)}, or a
 * {@link com.zqnt.sdk.client.ZequentClientInterceptor} bean in Quarkus. An {@code authorization}
 * header set by such an interceptor wins; the fixed token is then not sent.</p>
 *
 * <p>Every core service refuses a call without a credential. An organization administrator issues
 * one in the console (Deploy &rarr; Access &amp; Integrations &rarr; Credentials, kind "client"); it
 * is shown once. Hand it to the SDK with {@code ZequentClient.builder().clientToken(...)},
 * {@code zequent.client-token} in a Quarkus application, or the {@value #ENV_VAR} environment
 * variable. It acts for the organization it was issued to, and only on that organization's assets,
 * Applications and runs.</p>
 *
 * <p>{@link #interceptor(String)} attaches it to every call as {@code authorization: Bearer ...},
 * and turns the platform's refusals ({@code UNAUTHENTICATED}, {@code PERMISSION_DENIED}) into a
 * message that says what to do about them.</p>
 */
public final class ClientCredentials {

    /** The environment variable read when no token is configured explicitly. */
    public static final String ENV_VAR = "ZQNT_CLIENT_TOKEN";

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private ClientCredentials() {
    }

    /** The explicit token if one is given, else {@value #ENV_VAR}; null when neither is set. */
    public static String resolve(String explicitToken) {
        String token = blankToNull(explicitToken);
        return token != null ? token : blankToNull(System.getenv(ENV_VAR));
    }

    /** Adds the bearer token (when there is one) and explains refusals. */
    public static ClientInterceptor interceptor(String token) {
        return new BearerInterceptor(blankToNull(token));
    }

    /** What the platform's refusal means for somebody holding (or not holding) a credential. */
    static Status explain(Status status, boolean hasToken) {
        String cause = status.getDescription() == null ? "" : " (" + status.getDescription() + ")";
        if (status.getCode() == Status.Code.UNAUTHENTICATED) {
            String advice = hasToken
                    ? "the client credential was not accepted: it is expired, revoked, or not issued by this "
                    + "installation. Issue a new one in the console (Access & Integrations > Credentials, kind "
                    + "'client')"
                    : "no client credential is configured. Set " + ENV_VAR + " or pass one with "
                    + "ZequentClient.builder().clientToken(...); an organization administrator issues it in the "
                    + "console (Access & Integrations > Credentials, kind 'client')";
            return status.withDescription("Zequent refused the call: " + advice + cause);
        }
        if (status.getCode() == Status.Code.PERMISSION_DENIED) {
            return status.withDescription("Zequent refused the call: this client credential may not do this — "
                    + "it reaches only its own organization's assets, Applications and runs, and never "
                    + "administration" + cause);
        }
        return status;
    }

    /** A refusal of the credential the host application's own interceptor supplied. */
    static Status explainHostCredential(Status status) {
        if (status.getCode() != Status.Code.UNAUTHENTICATED && status.getCode() != Status.Code.PERMISSION_DENIED) {
            return status;
        }
        String cause = status.getDescription() == null ? "" : " (" + status.getDescription() + ")";
        return status.withDescription("Zequent refused the credential set by the application's own client "
                + "interceptor" + cause);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final class BearerInterceptor implements ClientInterceptor {
        private final String token;

        BearerInterceptor(String token) {
            this.token = token;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                   CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    // A host interceptor that already named the caller (see
                    // ZequentClientInterceptor) wins: two authorization values would leave the
                    // platform reading whichever came last.
                    boolean fromHost = headers.containsKey(AUTHORIZATION);
                    if (token != null && !fromHost) {
                        headers.put(AUTHORIZATION, "Bearer " + token);
                    }
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            super.onClose(fromHost ? explainHostCredential(status) : explain(status, token != null), trailers);
                        }
                    }, headers);
                }
            };
        }
    }
}
