package com.zqnt.sdk.client.commands.application.impl;

import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.protos.control.v3.CancelCommandRequest;
import com.zqnt.protos.control.v3.CancelCommandResponse;
import com.zqnt.protos.control.v3.ExecuteCommandRequest;
import com.zqnt.protos.control.v3.ExecuteCommandResponse;
import com.zqnt.protos.control.v3.GetCapabilitiesRequest;
import com.zqnt.protos.control.v3.GetCapabilitiesResponse;
import com.zqnt.protos.control.v3.RemoteControlServiceGrpc;
import com.zqnt.protos.control.v3.WatchCommandEventsRequest;
import com.zqnt.protos.control.v3.WatchCommandEventsResponse;
import com.zqnt.sdk.client.commands.application.Commands;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.commands.domains.CommandRequest;
import com.zqnt.sdk.client.commands.domains.CommandWatch;
import com.zqnt.sdk.client.commands.domains.Structs;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class CommandsImpl implements Commands {

    private final RemoteControlServiceGrpc.RemoteControlServiceStub stub;
    private final GrpcResilience resilience;
    private final int requestTimeoutSeconds;

    private CommandsImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.stub = RemoteControlServiceGrpc.newStub(channel);
        this.resilience = new GrpcResilience(
                config.getMaxRetryAttempts(),
                config.getRetryDelayMillis(),
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerWaitDurationMillis());
        this.requestTimeoutSeconds = config.getRequestTimeoutSeconds();
    }

    public static Commands create(GrpcClientConfig config, ManagedChannel channel) {
        return new CommandsImpl(config, channel);
    }

    @Override
    public CompletableFuture<CapabilitySet> listCapabilities(String assetSn) {
        requireText(assetSn, "assetSn");
        var request = GetCapabilitiesRequest.newBuilder()
                .setContext(context(UUID.randomUUID().toString()))
                .setAsset(AssetRef.newBuilder().setSn(assetSn))
                .build();
        return this.<GetCapabilitiesResponse>unary((s, o) -> s.getCapabilities(request, o))
                .thenApply(GetCapabilitiesResponse::getCapabilities);
    }

    @Override
    public CompletableFuture<CommandResult> executeCommand(String assetSn, String commandId, Map<String, ?> params) {
        CommandRequest.CommandRequestBuilder request = CommandRequest.builder().assetSn(assetSn).commandId(commandId);
        if (params != null) params.forEach(request::param);
        return executeCommand(request.build());
    }

    @Override
    public CompletableFuture<CommandResult> executeCommand(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        requireText(request.getCommandId(), "commandId");
        if (isBlank(request.getAssetSn()) && isBlank(request.getAssetId())) {
            throw new IllegalArgumentException("assetSn or assetId is required");
        }
        String key = isBlank(request.getIdempotencyKey()) ? UUID.randomUUID().toString() : request.getIdempotencyKey();
        var asset = AssetRef.newBuilder();
        if (!isBlank(request.getAssetSn())) asset.setSn(request.getAssetSn());
        if (!isBlank(request.getAssetId())) asset.setId(request.getAssetId());
        var command = Command.newBuilder()
                .setAsset(asset)
                .setCommandId(request.getCommandId())
                .setParams(Structs.toStruct(request.getParams()));
        if (request.getTarget() != null) command.setTarget(request.getTarget());
        if (request.getTimeout() != null) {
            command.setTimeout(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(request.getTimeout().getSeconds())
                    .setNanos(request.getTimeout().getNano()));
        }
        var execute = ExecuteCommandRequest.newBuilder()
                .setContext(context(key))
                .setCommand(command);
        if (!isBlank(request.getReason())) execute.setReason(request.getReason());
        if (request.isNoFlyZoneOverride()) execute.setNoFlyZoneOverride(true);
        var proto = execute.build();
        log.info("ExecuteCommand: asset={}, command={}", proto.getCommand().getAsset(), request.getCommandId());
        return this.<ExecuteCommandResponse>unary((s, o) -> s.executeCommand(proto, o))
                .thenApply(response -> accepted(response.getResult()));
    }

    @Override
    public CompletableFuture<CommandResult> cancelCommand(String commandExecutionId, String reason) {
        requireText(commandExecutionId, "commandExecutionId");
        var cancel = CancelCommandRequest.newBuilder()
                .setContext(context(UUID.randomUUID().toString()))
                .setCommandExecutionId(commandExecutionId);
        if (!isBlank(reason)) cancel.setReason(reason);
        var proto = cancel.build();
        return this.<CancelCommandResponse>unary((s, o) -> s.cancelCommand(proto, o))
                .thenApply(response -> accepted(response.getResult()));
    }

    @Override
    public CommandWatch watchCommand(String commandExecutionId, Consumer<CommandEvent> onEvent) {
        requireText(commandExecutionId, "commandExecutionId");
        return watch(WatchCommandEventsRequest.newBuilder().setCommandExecutionId(commandExecutionId).build(), onEvent);
    }

    @Override
    public CommandWatch watchAsset(String assetSn, Consumer<CommandEvent> onEvent) {
        requireText(assetSn, "assetSn");
        return watch(WatchCommandEventsRequest.newBuilder().setAsset(AssetRef.newBuilder().setSn(assetSn)).build(), onEvent);
    }

    private CommandWatch watch(WatchCommandEventsRequest request, Consumer<CommandEvent> onEvent) {
        Objects.requireNonNull(onEvent, "onEvent");
        CommandWatch watch = new CommandWatch();
        stub.watchCommandEvents(request, new ClientResponseObserver<WatchCommandEventsRequest, WatchCommandEventsResponse>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<WatchCommandEventsRequest> call) {
                watch.bind(call);
            }

            @Override
            public void onNext(WatchCommandEventsResponse response) {
                if (watch.isDone()) return;
                try {
                    onEvent.accept(response.getEvent());
                } catch (RuntimeException failure) {
                    log.warn("Command event handler failed: {}", failure.getMessage(), failure);
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
        return watch;
    }

    private <T> CompletableFuture<T> unary(UnaryCall<T> call) {
        try {
            return resilience.executeWithResilienceAsync(() -> {
                CompletableFuture<T> future = new CompletableFuture<>();
                call.invoke(stub.withDeadlineAfter(requestTimeoutSeconds, TimeUnit.SECONDS), new StreamObserver<>() {
                    @Override
                    public void onNext(T value) {
                        future.complete(value);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        future.completeExceptionally(failure);
                    }

                    @Override
                    public void onCompleted() {
                        if (!future.isDone()) future.completeExceptionally(new IllegalStateException("no response"));
                    }
                });
                return future;
            }).exceptionallyCompose(failure -> CompletableFuture.failedFuture(CommandException.from(failure)));
        } catch (RuntimeException circuitOpen) {
            return CompletableFuture.failedFuture(CommandException.from(circuitOpen));
        }
    }

    private static CommandResult accepted(CommandResult result) {
        if (result.getState() == CommandState.COMMAND_STATE_REJECTED) throw CommandException.rejected(result);
        return result;
    }

    private static RequestContext context(String key) {
        return RequestContext.newBuilder().setRequestId(UUID.randomUUID().toString()).setIdempotencyKey(key).build();
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) throw new IllegalArgumentException(name + " is required");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    private interface UnaryCall<T> {
        void invoke(RemoteControlServiceGrpc.RemoteControlServiceStub stub, StreamObserver<T> observer);
    }
}
