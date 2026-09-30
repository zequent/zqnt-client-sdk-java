package com.zqnt.sdk.client.grpc;

import com.zqnt.sdk.client.ZequentClient;
import com.zqnt.sdk.client.livedata.domains.StreamHandle;
import com.zqnt.sdk.client.livedata.domains.StreamTelemetryRequest;
import com.zqnt.utils.connector.proto.ConnectorServiceGrpc;
import com.zqnt.utils.connector.proto.ListSkillContractsRequest;
import com.zqnt.utils.connector.proto.SkillContractListResponse;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesRequest;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesResponse;
import com.zqnt.utils.livedata.proto.LiveDataServiceGrpc;
import com.zqnt.utils.livedata.proto.LiveDataTelemetryResponse;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A host application's own interceptors (a service forwarding its caller's token, or rotating a
 * short-lived one) reach every channel the SDK builds — unary and streaming, all services — and the
 * {@code authorization} header they set wins over a configured client token. Against a real gRPC
 * server, so the headers are the ones on the wire.
 */
class HostInterceptorTest {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** method name -> every authorization value that call carried (joined with '|'). */
    final Map<String, List<String>> seen = new ConcurrentHashMap<>();
    final AtomicReference<Status> refuseWith = new AtomicReference<>();
    final CountDownLatch streamOpened = new CountDownLatch(1);
    Server server;

    @BeforeEach
    void start() throws Exception {
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                Iterable<String> values = headers.getAll(AUTHORIZATION);
                String all = values == null ? "<none>" : String.join("|", values);
                seen.computeIfAbsent(call.getMethodDescriptor().getBareMethodName(), k -> new CopyOnWriteArrayList<>()).add(all);
                Status refusal = refuseWith.get();
                if (refusal != null) {
                    call.close(refusal, new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
        var connector = new ConnectorServiceGrpc.ConnectorServiceImplBase() {
            @Override
            public void listSkillContracts(ListSkillContractsRequest request, StreamObserver<SkillContractListResponse> observer) {
                observer.onNext(SkillContractListResponse.newBuilder().build());
                observer.onCompleted();
            }
        };
        var remoteControl = new RemoteControlServiceGrpc.RemoteControlServiceImplBase() {
            @Override
            public void getCapabilities(AssetCapabilitiesRequest request, StreamObserver<AssetCapabilitiesResponse> observer) {
                observer.onNext(AssetCapabilitiesResponse.newBuilder().build());
                observer.onCompleted();
            }
        };
        var liveData = new LiveDataServiceGrpc.LiveDataServiceImplBase() {
            @Override
            public void streamTelemetry(com.zqnt.utils.livedata.proto.StreamTelemetryRequest request,
                                        StreamObserver<LiveDataTelemetryResponse> observer) {
                streamOpened.countDown(); // held open, like a real telemetry stream
            }
        };
        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(connector, recorder))
                .addService(ServerInterceptors.intercept(remoteControl, recorder))
                .addService(ServerInterceptors.intercept(liveData, recorder))
                .build().start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("deprecation")
    ZequentClient client(String token, ClientInterceptor... interceptors) {
        int port = server.getPort();
        return ZequentClient.builder().maxRetryAttempts(1).clientToken(token).interceptors(List.of(interceptors))
                .connector().host("localhost").port(port).done()
                .remoteControl().host("localhost").port(port).done()
                .liveData().host("localhost").port(port).done()
                .missionAutonomy().host("localhost").port(port).done()
                .build();
    }

    /** What a service like admin-console registers: its caller's token, or its own. */
    static ClientInterceptor bearer(Supplier<String> token) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions callOptions, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> listener, Metadata headers) {
                        if (!headers.containsKey(AUTHORIZATION)) {
                            headers.put(AUTHORIZATION, "Bearer " + token.get());
                        }
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    @Test
    void theHostInterceptorReachesUnaryAndStreamingCallsOfEveryService() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("user-token");
        try (ZequentClient client = client(null, bearer(current::get))) {
            client.remoteControl().getCapabilities("SN-1").get(10, TimeUnit.SECONDS);
            current.set("service-token");
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
            StreamHandle stream = client.liveData().streamTelemetryData(
                    StreamTelemetryRequest.builder().sn("SN-1").tid("t-1").frequencyMs(1000).build(), ignored -> { }, ignored -> { });
            assertTrue(streamOpened.await(10, TimeUnit.SECONDS), "telemetry stream reached the server");
            stream.stop();
        }
        assertEquals(List.of("Bearer user-token"), seen.get("GetCapabilities"));
        assertEquals(List.of("Bearer service-token"), seen.get("ListSkillContracts"));
        assertEquals("Bearer service-token", seen.get("StreamTelemetry").get(0));
    }

    @Test
    void theHostInterceptorsHeaderWinsOverTheClientTokenAndIsTheOnlyOne() throws Exception {
        try (ZequentClient client = client("fixed-token", bearer(() -> "forwarded"))) {
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of("Bearer forwarded"), seen.get("ListSkillContracts"));
    }

    @Test
    void theClientTokenIsStillSentWhenTheInterceptorSetsNone() throws Exception {
        ClientInterceptor passThrough = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions);
            }
        };
        try (ZequentClient client = client("fixed-token", passThrough)) {
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of("Bearer fixed-token"), seen.get("ListSkillContracts"));
    }

    @Test
    void interceptorsRunInTheOrderGiven() throws Exception {
        try (ZequentClient client = client(null, bearer(() -> "first"), bearer(() -> "second"))) {
            client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of("Bearer first"), seen.get("ListSkillContracts"));
    }

    @Test
    void aRefusedHostCredentialIsExplainedAsSuch() {
        refuseWith.set(Status.UNAUTHENTICATED.withDescription("Invalid token"));
        try (ZequentClient client = client(null, bearer(() -> "stale"))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.connector().listSkillContracts(null, null).get(10, TimeUnit.SECONDS));
            String message = ClientCredentialsTest.messages(failure);
            assertTrue(message.contains("UNAUTHENTICATED") && message.contains("client interceptor")
                    && message.contains("Invalid token"), message);
            assertFalse(message.contains("ZQNT_CLIENT_TOKEN"), "not the fixed-token advice: " + message);
        }
    }
}
