package com.zqnt.sdk.client.telemetry.application.impl;

import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.protos.telemetry.v3.Alert;
import com.zqnt.protos.telemetry.v3.AlertSeverity;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import com.zqnt.protos.telemetry.v3.SourceStatus;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.protos.telemetry.v3.TelemetryServiceGrpc;
import com.zqnt.protos.telemetry.v3.WatchAlertsRequest;
import com.zqnt.protos.telemetry.v3.WatchAlertsResponse;
import com.zqnt.protos.telemetry.v3.WatchDetectionsRequest;
import com.zqnt.protos.telemetry.v3.WatchDetectionsResponse;
import com.zqnt.protos.telemetry.v3.WatchTelemetryRequest;
import com.zqnt.protos.telemetry.v3.WatchTelemetryResponse;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.telemetry.application.Telemetry;
import com.zqnt.sdk.client.telemetry.domains.TelemetryWatch;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
public class TelemetryImpl implements Telemetry {

    private final TelemetryServiceGrpc.TelemetryServiceStub stub;
    private final Duration heartbeatTimeout;

    private TelemetryImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.stub = TelemetryServiceGrpc.newStub(channel);
        this.heartbeatTimeout = Duration.ofSeconds(Math.max(1, config.getTelemetryHeartbeatTimeoutSeconds()));
    }

    public static Telemetry create(GrpcClientConfig config, ManagedChannel channel) {
        return new TelemetryImpl(config, channel);
    }

    @Override
    public TelemetryWatch watchTelemetry(Collection<String> assetSns, Duration minInterval,
                                         Consumer<TelemetrySample> onSample, Consumer<SourceStatus> onSourceStatus) {
        Objects.requireNonNull(onSample, "onSample");
        Objects.requireNonNull(onSourceStatus, "onSourceStatus");
        var request = WatchTelemetryRequest.newBuilder().setContext(context()).addAllAssets(assets(assetSns));
        if (minInterval != null) {
            request.setMinInterval(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(minInterval.getSeconds())
                    .setNanos(minInterval.getNano()));
        }
        return watch("Telemetry", request.build(), stub::watchTelemetry, response -> {
            switch (response.getItemCase()) {
                case SAMPLE -> onSample.accept(response.getSample());
                case SOURCE_STATUS -> onSourceStatus.accept(response.getSourceStatus());
                default -> { }
            }
        });
    }

    @Override
    public TelemetryWatch watchDetections(Collection<String> assetSns, Consumer<DetectionBatch> onBatch) {
        Objects.requireNonNull(onBatch, "onBatch");
        var request = WatchDetectionsRequest.newBuilder().setContext(context()).addAllAssets(assets(assetSns)).build();
        return watch("Detection", request, stub::watchDetections, response -> {
            if (response.hasBatch()) onBatch.accept(response.getBatch());
        });
    }

    @Override
    public TelemetryWatch watchAlerts(Collection<String> assetSns, AlertSeverity minSeverity, Consumer<Alert> onAlert) {
        Objects.requireNonNull(onAlert, "onAlert");
        var request = WatchAlertsRequest.newBuilder().setContext(context()).addAllAssets(assets(assetSns));
        if (minSeverity != null && minSeverity != AlertSeverity.UNRECOGNIZED) request.setMinSeverity(minSeverity);
        return watch("Alert", request.build(), stub::watchAlerts, response -> {
            if (response.hasAlert()) onAlert.accept(response.getAlert());
        });
    }

    private <Q, R> TelemetryWatch watch(String kind, Q request, BiConsumer<Q, StreamObserver<R>> call, Consumer<R> onItem) {
        TelemetryWatch watch = new TelemetryWatch();
        AtomicLong lastHeard = new AtomicLong(System.nanoTime());
        call.accept(request, new ClientResponseObserver<Q, R>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<Q> running) {
                watch.bind(running);
            }

            @Override
            public void onNext(R response) {
                lastHeard.set(System.nanoTime());
                if (watch.isDone()) return;
                try {
                    onItem.accept(response);
                } catch (RuntimeException failure) {
                    log.warn("{} handler failed: {}", kind, failure.getMessage(), failure);
                }
            }

            @Override
            public void onError(Throwable failure) {
                watch.failed(failure);
            }

            @Override
            public void onCompleted() {
                watch.completed();
            }
        });
        expectHeartbeat(watch, lastHeard, heartbeatTimeout.toNanos());
        return watch;
    }

    private static RequestContext context() {
        return RequestContext.newBuilder().setRequestId(UUID.randomUUID().toString()).build();
    }

    private static List<AssetRef> assets(Collection<String> assetSns) {
        List<AssetRef> assets = new ArrayList<>();
        if (assetSns == null) return assets;
        for (String sn : assetSns) {
            if (sn == null || sn.isBlank()) throw new IllegalArgumentException("assetSns must not contain a blank serial");
            assets.add(AssetRef.newBuilder().setSn(sn).build());
        }
        return assets;
    }

    private void expectHeartbeat(TelemetryWatch watch, AtomicLong lastHeard, long delayNanos) {
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(() -> {
            if (watch.isDone()) return;
            long silent = System.nanoTime() - lastHeard.get();
            if (silent >= heartbeatTimeout.toNanos()) {
                watch.failed(Status.UNAVAILABLE
                        .withDescription("no data and no heartbeat for " + heartbeatTimeout.toSeconds() + "s")
                        .asRuntimeException());
            } else {
                expectHeartbeat(watch, lastHeard, heartbeatTimeout.toNanos() - silent);
            }
        });
    }
}
