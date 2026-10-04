package com.zqnt.sdk.client.grpc;

import com.zqnt.sdk.client.ZequentClient;
import com.zqnt.utils.connector.proto.ConnectorServiceGrpc;
import com.zqnt.utils.connector.proto.ListSkillContractsRequest;
import com.zqnt.utils.connector.proto.SkillContractListResponse;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client credential travels on every call as a bearer token, and the platform's refusals come
 * back saying what to do. Against a real gRPC server on a free port, so the header is the one on
 * the wire.
 */
class ClientCredentialsTest {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    final List<String> seen = new CopyOnWriteArrayList<>();
    final AtomicReference<Status> refuseWith = new AtomicReference<>();
    Server server;

    @BeforeEach
    void start() throws Exception {
        ConnectorServiceGrpc.ConnectorServiceImplBase connector = new ConnectorServiceGrpc.ConnectorServiceImplBase() {
            @Override
            public void listSkillContracts(ListSkillContractsRequest request,
                                           StreamObserver<SkillContractListResponse> observer) {
                observer.onNext(SkillContractListResponse.newBuilder().setHasErrors(false).build());
                observer.onCompleted();
            }
        };
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                String header = headers.get(AUTHORIZATION);
                seen.add(header == null ? "<none>" : header);
                Status refusal = refuseWith.get();
                if (refusal != null) {
                    call.close(refusal, new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(connector, recorder)).build().start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("deprecation")
    ZequentClient client(String token) {
        return ZequentClient.builder().maxRetryAttempts(1).clientToken(token)
                .connector().host("localhost").port(server.getPort()).done()
                .build();
    }

    @Test
    void everyCallCarriesTheCredentialAsABearerToken() throws Exception {
        try (ZequentClient client = client("tok-123")) {
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of("Bearer tok-123", "Bearer tok-123"), seen);
    }

    @Test
    void anExplicitTokenIsTrimmedAndABlankOneIsNone() {
        assertEquals("abc", ClientCredentials.resolve("  abc \n"));
        assertEquals(System.getenv(ClientCredentials.ENV_VAR) == null ? null : System.getenv(ClientCredentials.ENV_VAR).strip(),
                ClientCredentials.resolve("   "), "blank falls back to " + ClientCredentials.ENV_VAR);
    }

    @Test
    void aRefusedCredentialSaysWhatToDo() {
        refuseWith.set(Status.UNAUTHENTICATED.withDescription("Credential has been revoked"));
        try (ZequentClient client = client("revoked-token")) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS));
            String message = messages(failure);
            assertTrue(message.contains("UNAUTHENTICATED"), message);
            assertTrue(message.contains("expired, revoked"), message);
            assertTrue(message.contains("Credential has been revoked"), "the platform's own reason is kept: " + message);
        }
        assertEquals(1, seen.size(), "an authentication failure is not retried");
    }

    @Test
    void aMissingCredentialNamesTheVariable() {
        assumeNoTokenInEnvironment();
        refuseWith.set(Status.UNAUTHENTICATED.withDescription("Authentication required"));
        try (ZequentClient client = client(null)) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS));
            assertTrue(messages(failure).contains("ZQNT_CLIENT_TOKEN"), messages(failure));
        }
        assertEquals(List.of("<none>"), seen);
    }

    @Test
    void aMethodOutsideTheCredentialsScopeSaysSo() {
        refuseWith.set(Status.PERMISSION_DENIED.withDescription("This credential may not call zqnt.ConnectorService/ListUsers"));
        try (ZequentClient client = client("tok")) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS));
            String message = messages(failure);
            assertTrue(message.contains("PERMISSION_DENIED") && message.contains("own organization"), message);
        }
    }

    static void assumeNoTokenInEnvironment() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(ClientCredentials.ENV_VAR) == null);
    }

    static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t).append(" | ");
        }
        return all.toString();
    }
}
