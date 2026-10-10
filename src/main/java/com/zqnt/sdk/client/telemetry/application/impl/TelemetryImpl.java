package com.zqnt.sdk.client.telemetry.application.impl;

import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.protos.telemetry.v3.SourceStatus;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.protos.telemetry.v3.TelemetryServiceGrpc;
import com.zqnt.protos.telemetry.v3.WatchTelemetryRequest;
import com.zqnt.protos.telemetry.v3.WatchTelemetryResponse;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.telemetry.application.Telemetry;
import com.zqnt.sdk.client.telemetry.domains.TelemetryWatch;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
        var request = WatchTelemetryRequest.newBuilder()
                .setContext(RequestContext.newBuilder().setRequestId(UUID.randomUUID().toString()));
        if (assetSns != null) {
            for (String sn : assetSns) {
                if (sn == null || sn.isBlank()) throw new IllegalArgumentException("assetSns must not contain a blank serial");
                request.addAssets(AssetRef.newBuilder().setSn(sn));
            }
        }
        if (minInterval != null) {
            request.setMinInterval(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(minInterval.getSeconds())
                    .setNanos(minInterval.getNano()));
        }
        TelemetryWatch watch = new TelemetryWatch();
        AtomicLong lastHeard = new AtomicLong(System.nanoTime());
        stub.watchTelemetry(request.build(), new ClientResponseObserver<WatchTelemetryRequest, WatchTelemetryResponse>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<WatchTelemetryRequest> call) {
                watch.bind(call);
            }

            @Override
            public void onNext(WatchTelemetryResponse response) {
                lastHeard.set(System.nanoTime());
                if (watch.isDone()) return;
                try {
                    switch (response.getItemCase()) {
                        case SAMPLE -> onSample.accept(response.getSample());
                        case SOURCE_STATUS -> onSourceStatus.accept(response.getSourceStatus());
                        default -> { }
                    }
                } catch (RuntimeException failure) {
                    log.warn("Telemetry handler failed: {}", failure.getMessage(), failure);
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

    private void expectHeartbeat(TelemetryWatch watch, AtomicLong lastHeard, long delayNanos) {
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(() -> {
            if (watch.isDone()) return;
            long silent = System.nanoTime() - lastHeard.get();
            if (silent >= heartbeatTimeout.toNanos()) {
                watch.failed(Status.UNAVAILABLE
                        .withDescription("no telemetry and no heartbeat for " + heartbeatTimeout.toSeconds() + "s")
                        .asRuntimeException());
            } else {
                expectHeartbeat(watch, lastHeard, heartbeatTimeout.toNanos() - silent);
            }
        });
    }
}
