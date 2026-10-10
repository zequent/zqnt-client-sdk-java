package com.zqnt.sdk.client.telemetry.application.impl;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.telemetry.v3.SourceState;
import com.zqnt.protos.telemetry.v3.SourceStatus;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.protos.telemetry.v3.TelemetryServiceGrpc;
import com.zqnt.protos.telemetry.v3.WatchTelemetryRequest;
import com.zqnt.protos.telemetry.v3.WatchTelemetryResponse;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.telemetry.application.Telemetry;
import com.zqnt.sdk.client.telemetry.domains.TelemetryWatch;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryImplTest {

    private final AtomicReference<WatchTelemetryRequest> lastWatch = new AtomicReference<>();
    private final AtomicReference<StreamObserver<WatchTelemetryResponse>> openWatch = new AtomicReference<>();
    private final AtomicReference<Status> refuseWith = new AtomicReference<>();
    private final CountDownLatch watchCancelled = new CountDownLatch(1);
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FakeTelemetryV3())
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void watchesSamplesAndSourceStatusOfTheNamedAssets() {
        List<TelemetrySample> samples = new CopyOnWriteArrayList<>();
        List<SourceStatus> statuses = new CopyOnWriteArrayList<>();

        TelemetryWatch watch = telemetry(35).watchTelemetry(List.of("DOCK-1", "drone-1"), Duration.ofMillis(500),
                samples::add, statuses::add);
        openWatch.get().onNext(WatchTelemetryResponse.newBuilder().setSourceStatus(SourceStatus.newBuilder()
                .setAsset(AssetRef.newBuilder().setSn("drone-1")).setState(SourceState.SOURCE_STATE_ONLINE)).build());
        openWatch.get().onNext(WatchTelemetryResponse.newBuilder().setHeartbeat(Timestamp.newBuilder().setSeconds(1)).build());
        openWatch.get().onNext(WatchTelemetryResponse.newBuilder().setSample(TelemetrySample.newBuilder()
                .setAsset(AssetRef.newBuilder().setSn("drone-1")).setBatteryPercent(87)).build());
        watch.close();

        assertEquals(List.of("DOCK-1", "drone-1"), lastWatch.get().getAssetsList().stream().map(AssetRef::getSn).toList());
        assertEquals(500_000_000, lastWatch.get().getMinInterval().getNanos());
        assertFalse(lastWatch.get().getContext().getRequestId().isBlank());
        assertEquals(1, samples.size());
        assertEquals(87, samples.get(0).getBatteryPercent(), 1e-9);
        assertEquals(SourceState.SOURCE_STATE_ONLINE, statuses.get(0).getState());
        assertTrue(watch.isDone());
        assertDoesNotThrow(() -> assertTrue(watchCancelled.await(5, TimeUnit.SECONDS)));
    }

    @Test
    void noAssetWatchesTheWholeOrganizationWithEverySample() {
        TelemetryWatch watch = telemetry(35).watchTelemetry(null, sample -> { });
        watch.close();

        assertEquals(0, lastWatch.get().getAssetsCount());
        assertFalse(lastWatch.get().hasMinInterval());
    }

    @Test
    void aRefusedWatchFailsItsCompletion() {
        refuseWith.set(Status.PERMISSION_DENIED.withDescription("live data is not licensed"));

        TelemetryWatch watch = telemetry(35).watchTelemetry(List.of("drone-1"), sample -> { });

        CompletionException failure = assertThrows(CompletionException.class, () -> watch.done().join());
        CommandException refused = assertInstanceOf(CommandException.class, failure.getCause());
        assertEquals(ErrorCategory.ERROR_CATEGORY_PERMISSION_DENIED, refused.getCategory());
    }

    @Test
    void aSilentStreamFailsAfterTheHeartbeatTimeout() throws Exception {
        TelemetryWatch watch = telemetry(1).watchTelemetry(List.of("drone-1"), sample -> { });

        CompletionException failure = assertThrows(CompletionException.class,
                () -> watch.done().orTimeout(5, TimeUnit.SECONDS).join());
        CommandException silent = assertInstanceOf(CommandException.class, failure.getCause());
        assertEquals(ErrorCategory.ERROR_CATEGORY_SERVICE, silent.getCategory());
        assertTrue(silent.isRetryable());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aBlankSerialIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> telemetry(35).watchTelemetry(List.of("drone-1", " "), sample -> { }));
    }

    private Telemetry telemetry(int heartbeatTimeoutSeconds) {
        return TelemetryImpl.create(GrpcClientConfig.builder()
                .liveDataConfig(ServiceConfig.builder().serviceName("live-data").host("in-process").port(0).build())
                .telemetryHeartbeatTimeoutSeconds(heartbeatTimeoutSeconds)
                .build(), channel);
    }

    private final class FakeTelemetryV3 extends TelemetryServiceGrpc.TelemetryServiceImplBase {

        @Override
        public void watchTelemetry(WatchTelemetryRequest request, StreamObserver<WatchTelemetryResponse> observer) {
            lastWatch.set(request);
            Status status = refuseWith.get();
            if (status != null) {
                observer.onError(status.asRuntimeException());
                return;
            }
            ((ServerCallStreamObserver<WatchTelemetryResponse>) observer).setOnCancelHandler(watchCancelled::countDown);
            openWatch.set(observer);
        }
    }
}
