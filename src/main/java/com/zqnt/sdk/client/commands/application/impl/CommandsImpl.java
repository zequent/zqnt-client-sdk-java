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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    public CompletableFuture<CommandResult> executeAndWait(String assetSn, String commandId, Map<String, ?> params, Duration wait) {
        CommandRequest.CommandRequestBuilder request = CommandRequest.builder().assetSn(assetSn).commandId(commandId);
        if (params != null) params.forEach(request::param);
        return executeAndWait(request.build(), wait);
    }

    @Override
    public CompletableFuture<CommandResult> executeAndWait(CommandRequest request, Duration wait) {
        Objects.requireNonNull(request, "request");
        requireText(request.getAssetSn(), "assetSn");
        requireText(request.getCommandId(), "commandId");
        RunOutcome outcome = new RunOutcome(request.getCommandId());
        CommandWatch watch = watchAsset(request.getAssetSn(), outcome::event);
        outcome.future.whenComplete((result, failure) -> watch.close());
        watch.done().whenComplete((ended, failure) -> outcome.future.completeExceptionally(failure != null
                ? CommandException.from(failure)
                : CommandException.watchEnded("the event watch on " + request.getAssetSn() + " ended before "
                        + request.getCommandId() + " finished")));
        if (outcome.future.isDone()) return outcome.future;
        if (wait != null) {
            CompletableFuture.delayedExecutor(wait.toNanos(), TimeUnit.NANOSECONDS).execute(() ->
                    outcome.future.completeExceptionally(CommandException.waitTimedOut(
                            request.getCommandId() + " did not finish within " + wait)));
        }
        try {
            executeCommand(request).whenComplete((result, failure) -> {
                if (failure != null) outcome.future.completeExceptionally(CommandException.from(failure));
                else outcome.started(result);
            });
        } catch (RuntimeException invalid) {
            watch.close();
            throw invalid;
        }
        return outcome.future;
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

    private static final class RunOutcome {
        private final CompletableFuture<CommandResult> future = new CompletableFuture<>();
        private final List<CommandEvent> early = new ArrayList<>();
        private final String commandId;
        private String executionId;

        RunOutcome(String commandId) {
            this.commandId = commandId;
        }

        synchronized void started(CommandResult result) {
            if (isFinal(result.getState())) {
                settle(result);
                return;
            }
            executionId = result.getCommandExecutionId();
            early.forEach(this::event);
            early.clear();
        }

        synchronized void event(CommandEvent event) {
            if (executionId == null) {
                early.add(event);
            } else if (executionId.equals(event.getCommandExecutionId()) && isFinal(event.getState())) {
                settle(CommandResult.newBuilder()
                        .setCommandExecutionId(event.getCommandExecutionId())
                        .setCommandId(event.getCommandId().isEmpty() ? commandId : event.getCommandId())
                        .setState(event.getState())
                        .setResult(event.getResult())
                        .setError(event.getError())
                        .build());
            }
        }

        private void settle(CommandResult result) {
            if (result.getState() == CommandState.COMMAND_STATE_SUCCEEDED) future.complete(result);
            else future.completeExceptionally(CommandException.of(result));
        }

        private static boolean isFinal(CommandState state) {
            return switch (state) {
                case COMMAND_STATE_SUCCEEDED, COMMAND_STATE_FAILED, COMMAND_STATE_REJECTED,
                     COMMAND_STATE_CANCELLED, COMMAND_STATE_TIMED_OUT -> true;
                default -> false;
            };
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
