package com.zqnt.sdk.client.telemetry.application.impl;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.telemetry.v3.Alert;
import com.zqnt.protos.telemetry.v3.AlertSeverity;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import com.zqnt.protos.telemetry.v3.SourceState;
import com.zqnt.protos.telemetry.v3.SourceStatus;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.protos.telemetry.v3.TelemetryServiceGrpc;
import com.zqnt.protos.telemetry.v3.WatchAlertsRequest;
import com.zqnt.protos.telemetry.v3.WatchAlertsResponse;
import com.zqnt.protos.telemetry.v3.WatchDetectionsRequest;
import com.zqnt.protos.telemetry.v3.WatchDetectionsResponse;
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
    private final AtomicReference<WatchDetectionsRequest> lastDetectionWatch = new AtomicReference<>();
    private final AtomicReference<StreamObserver<WatchDetectionsResponse>> openDetectionWatch = new AtomicReference<>();
    private final AtomicReference<WatchAlertsRequest> lastAlertWatch = new AtomicReference<>();
    private final AtomicReference<StreamObserver<WatchAlertsResponse>> openAlertWatch = new AtomicReference<>();
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

    @Test
    void watchesDetectionBatchesOfTheNamedAssets() {
        List<DetectionBatch> batches = new CopyOnWriteArrayList<>();

        TelemetryWatch watch = telemetry(35).watchDetections(List.of("drone-1"), batches::add);
        openDetectionWatch.get().onNext(WatchDetectionsResponse.newBuilder().setHeartbeat(Timestamp.newBuilder().setSeconds(1)).build());
        openDetectionWatch.get().onNext(WatchDetectionsResponse.newBuilder().setBatch(DetectionBatch.newBuilder()
                .setAsset(AssetRef.newBuilder().setSn("drone-1")).setStreamUrl("rtsp://cam")).build());
        watch.close();

        assertEquals(List.of("drone-1"), lastDetectionWatch.get().getAssetsList().stream().map(AssetRef::getSn).toList());
        assertFalse(lastDetectionWatch.get().getContext().getRequestId().isBlank());
        assertEquals(1, batches.size());
        assertEquals("rtsp://cam", batches.get(0).getStreamUrl());
        assertTrue(watch.isDone());
    }

    @Test
    void watchesAlertsFromTheMinimumSeverity() {
        List<Alert> alerts = new CopyOnWriteArrayList<>();

        TelemetryWatch watch = telemetry(35).watchAlerts(null, AlertSeverity.ALERT_SEVERITY_WARNING, alerts::add);
        openAlertWatch.get().onNext(WatchAlertsResponse.newBuilder().setHeartbeat(Timestamp.newBuilder().setSeconds(1)).build());
        openAlertWatch.get().onNext(WatchAlertsResponse.newBuilder().setAlert(Alert.newBuilder()
                .setAsset(AssetRef.newBuilder().setSn("DOCK-1")).setCode("dock.rain")
                .setSeverity(AlertSeverity.ALERT_SEVERITY_WARNING)).build());
        watch.close();

        assertEquals(0, lastAlertWatch.get().getAssetsCount());
        assertEquals(AlertSeverity.ALERT_SEVERITY_WARNING, lastAlertWatch.get().getMinSeverity());
        assertEquals(List.of("dock.rain"), alerts.stream().map(Alert::getCode).toList());
    }

    @Test
    void anAlertWatchWithoutSeverityAsksForEverySeverity() {
        telemetry(35).watchAlerts(List.of("DOCK-1"), null, alert -> { }).close();

        assertEquals(AlertSeverity.ALERT_SEVERITY_UNSPECIFIED, lastAlertWatch.get().getMinSeverity());
    }

    @Test
    void aSilentDetectionStreamFailsAfterTheHeartbeatTimeout() {
        TelemetryWatch watch = telemetry(1).watchDetections(List.of("drone-1"), batch -> { });

        CompletionException failure = assertThrows(CompletionException.class,
                () -> watch.done().orTimeout(5, TimeUnit.SECONDS).join());
        assertEquals(ErrorCategory.ERROR_CATEGORY_SERVICE,
                assertInstanceOf(CommandException.class, failure.getCause()).getCategory());
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

        @Override
        public void watchDetections(WatchDetectionsRequest request, StreamObserver<WatchDetectionsResponse> observer) {
            lastDetectionWatch.set(request);
            openDetectionWatch.set(observer);
        }

        @Override
        public void watchAlerts(WatchAlertsRequest request, StreamObserver<WatchAlertsResponse> observer) {
            lastAlertWatch.set(request);
            openAlertWatch.set(observer);
        }
    }
}
