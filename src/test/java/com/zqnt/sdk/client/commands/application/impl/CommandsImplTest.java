package com.zqnt.sdk.client.commands.application.impl;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.zqnt.protos.capability.v3.Capability;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.capability.v3.Target;
import com.zqnt.protos.capability.v3.TargetType;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.Error;
import com.zqnt.protos.common.v3.ErrorCategory;
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
import com.zqnt.sdk.client.config.ServiceConfig;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CommandsImplTest {

    private final AtomicReference<GetCapabilitiesRequest> lastCapabilities = new AtomicReference<>();
    private final AtomicReference<ExecuteCommandRequest> lastExecute = new AtomicReference<>();
    private final AtomicReference<CancelCommandRequest> lastCancel = new AtomicReference<>();
    private final AtomicReference<WatchCommandEventsRequest> lastWatch = new AtomicReference<>();
    private final AtomicReference<StreamObserver<WatchCommandEventsResponse>> openWatch = new AtomicReference<>();
    private final CountDownLatch watchCancelled = new CountDownLatch(1);
    private final List<StreamObserver<WatchCommandEventsResponse>> assetWatches = new CopyOnWriteArrayList<>();
    private final List<CommandEvent> eventsOnExecute = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Status> refuseWith = new AtomicReference<>();
    private final AtomicReference<CommandResult> answerWith = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private Commands commands;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FakeRemoteControlV3())
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        commands = CommandsImpl.create(GrpcClientConfig.builder()
                .remoteControlConfig(ServiceConfig.builder().serviceName("remote-control").host("in-process").port(0).build())
                .maxRetryAttempts(0)
                .requestTimeoutSeconds(5)
                .build(), channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void listsTheCapabilitiesOfAnAsset() {
        CapabilitySet capabilities = commands.listCapabilities("drone-1").join();

        assertEquals("drone-1", lastCapabilities.get().getAsset().getSn());
        assertFalse(lastCapabilities.get().getContext().getRequestId().isBlank());
        assertEquals(List.of("flight.takeoff", "navigation.go_to"),
                capabilities.getCapabilitiesList().stream().map(Capability::getCommandId).toList());
    }

    @Test
    void executesACommandByIdWithItsParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("latitude", 52.52);
        params.put("longitude", 13.405);
        params.put("altitude", 40);
        params.put("speed", null);

        CommandResult result = commands.executeCommand("drone-1", "navigation.go_to", params).join();

        ExecuteCommandRequest sent = lastExecute.get();
        assertEquals("drone-1", sent.getCommand().getAsset().getSn());
        assertEquals("navigation.go_to", sent.getCommand().getCommandId());
        assertEquals(52.52, sent.getCommand().getParams().getFieldsOrThrow("latitude").getNumberValue(), 1e-9);
        assertEquals(40, sent.getCommand().getParams().getFieldsOrThrow("altitude").getNumberValue(), 1e-9);
        assertFalse(sent.getCommand().getParams().containsFields("speed"));
        assertFalse(sent.getContext().getIdempotencyKey().isBlank());
        assertFalse(sent.getNoFlyZoneOverride());
        assertEquals(CommandState.COMMAND_STATE_ACCEPTED, result.getState());
        assertEquals("exec-1", result.getCommandExecutionId());
    }

    @Test
    void sendsTheOptionsOfACommandRequest() {
        commands.executeCommand(CommandRequest.builder()
                .assetId("asset-uuid")
                .commandId("camera.change_zoom")
                .param("zoom", 4.0)
                .param("lens", "zoom")
                .target(Target.newBuilder().setType(TargetType.TARGET_TYPE_PAYLOAD).setRef("payload-0").build())
                .timeout(Duration.ofSeconds(90))
                .reason("inspect the roof")
                .noFlyZoneOverride(true)
                .idempotencyKey("key-1")
                .build()).join();

        ExecuteCommandRequest sent = lastExecute.get();
        assertEquals("asset-uuid", sent.getCommand().getAsset().getId());
        assertTrue(sent.getCommand().getAsset().getSn().isEmpty());
        assertEquals("zoom", sent.getCommand().getParams().getFieldsOrThrow("lens").getStringValue());
        assertEquals("payload-0", sent.getCommand().getTarget().getRef());
        assertEquals(90, sent.getCommand().getTimeout().getSeconds());
        assertEquals("inspect the roof", sent.getReason());
        assertTrue(sent.getNoFlyZoneOverride());
        assertEquals("key-1", sent.getContext().getIdempotencyKey());
    }

    @Test
    void aRejectedCommandFailsWithItsCategoryAndCode() {
        answerWith.set(CommandResult.newBuilder()
                .setCommandId("flight.takeoff")
                .setState(CommandState.COMMAND_STATE_REJECTED)
                .setError(Error.newBuilder()
                        .setCategory(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT)
                        .setCode("command.invalid_params")
                        .setMessage("altitude must be a number"))
                .build());

        CommandException failure = failureOf(() -> commands.executeCommand("drone-1", "flight.takeoff",
                Map.of("altitude", "high")).join());

        assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, failure.getCategory());
        assertEquals("command.invalid_params", failure.getCode());
        assertEquals("altitude must be a number", failure.getMessage());
        assertNull(failure.getStatus());
        assertEquals(CommandState.COMMAND_STATE_REJECTED, failure.getResult().getState());
    }

    @Test
    void aRefusedCallFailsWithTheMatchingCategory() {
        refuseWith.set(Status.INVALID_ARGUMENT.withDescription("command.asset.sn and command.command_id are required"));
        CommandException invalid = failureOf(() -> commands.executeCommand("drone-1", "flight.takeoff", Map.of()).join());
        assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, invalid.getCategory());
        assertEquals(Status.Code.INVALID_ARGUMENT, invalid.getStatus());
        assertEquals("command.asset.sn and command.command_id are required", invalid.getMessage());
        assertFalse(invalid.isRetryable());

        refuseWith.set(Status.PERMISSION_DENIED.withDescription("asset of another organization"));
        CommandException denied = failureOf(() -> commands.listCapabilities("drone-2").join());
        assertEquals(ErrorCategory.ERROR_CATEGORY_PERMISSION_DENIED, denied.getCategory());

        refuseWith.set(Status.UNAVAILABLE.withDescription("No capabilities for drone-3"));
        CommandException unavailable = failureOf(() -> commands.listCapabilities("drone-3").join());
        assertEquals(ErrorCategory.ERROR_CATEGORY_SERVICE, unavailable.getCategory());
        assertTrue(unavailable.isRetryable());
    }

    @Test
    void aFailedRunIsAResultNotAnException() {
        answerWith.set(CommandResult.newBuilder()
                .setCommandExecutionId("exec-9")
                .setCommandId("flight.takeoff")
                .setState(CommandState.COMMAND_STATE_FAILED)
                .setError(Error.newBuilder().setCategory(ErrorCategory.ERROR_CATEGORY_ASSET).setCode("flight.not_airborne"))
                .build());

        CommandResult result = commands.executeCommand("drone-1", "flight.takeoff", null).join();

        assertEquals(CommandState.COMMAND_STATE_FAILED, result.getState());
        assertEquals("flight.not_airborne", result.getError().getCode());
    }

    @Test
    void cancelsACommandRun() {
        CommandResult result = commands.cancelCommand("exec-1", "operator abort").join();

        assertEquals("exec-1", lastCancel.get().getCommandExecutionId());
        assertEquals("operator abort", lastCancel.get().getReason());
        assertEquals(CommandState.COMMAND_STATE_CANCELLED, result.getState());
    }

    @Test
    void watchesOneRunUntilItsTerminalEvent() throws Exception {
        List<CommandEvent> events = new CopyOnWriteArrayList<>();

        CommandWatch watch = commands.watchCommand("exec-1", events::add);
        watch.done().get(5, TimeUnit.SECONDS);

        assertEquals("exec-1", lastWatch.get().getCommandExecutionId());
        assertEquals(List.of(CommandState.COMMAND_STATE_RUNNING, CommandState.COMMAND_STATE_SUCCEEDED),
                events.stream().map(CommandEvent::getState).toList());
        assertEquals(true, Structs.toMap(events.get(1).getResult()).get("arrived"));
    }

    @Test
    void watchesAnAssetUntilClosed() throws Exception {
        List<CommandEvent> events = new CopyOnWriteArrayList<>();

        CommandWatch watch = commands.watchAsset("drone-1", events::add);
        openWatch.get().onNext(WatchCommandEventsResponse.newBuilder().setEvent(event("exec-2", CommandState.COMMAND_STATE_RUNNING)).build());
        watch.close();

        assertEquals("drone-1", lastWatch.get().getAsset().getSn());
        assertEquals(1, events.size());
        assertTrue(watch.isDone());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aRefusedWatchFailsItsCompletion() {
        refuseWith.set(Status.INVALID_ARGUMENT.withDescription("give either command_execution_id or asset.sn"));

        CommandWatch watch = commands.watchCommand("exec-1", event -> { });

        CommandException failure = failureOf(() -> watch.done().join());
        assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, failure.getCategory());
    }

    @Test
    void executeAndWaitCompletesWithTheSucceededResult() throws Exception {
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_RUNNING));
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_SUCCEEDED).toBuilder()
                .setResult(Structs.toStruct(Map.of("arrived", true))).build());

        CommandResult result = commands.executeAndWait("drone-1", "navigation.go_to",
                Map.of("latitude", 52.52), Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);

        assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, result.getState());
        assertEquals("exec-1", result.getCommandExecutionId());
        assertEquals(true, Structs.toMap(result.getResult()).get("arrived"));
        assertEquals("drone-1", lastWatch.get().getAsset().getSn());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void executeAndWaitFailsWithTheFinalResultOfAFailedRun() throws Exception {
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_FAILED).toBuilder()
                .setError(Error.newBuilder().setCode("flight.not_airborne").setMessage("not airborne")).build());

        CommandException failure = failureOf(() -> commands.executeAndWait("drone-1", "navigation.go_to", Map.of(), null).join());

        assertEquals(CommandState.COMMAND_STATE_FAILED, failure.getResult().getState());
        assertEquals("exec-1", failure.getResult().getCommandExecutionId());
        assertEquals(ErrorCategory.ERROR_CATEGORY_ASSET, failure.getCategory());
        assertEquals("flight.not_airborne", failure.getCode());
        assertEquals("not airborne", failure.getMessage());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void executeAndWaitFailsOnARejectionWithoutWaiting() throws Exception {
        answerWith.set(CommandResult.newBuilder()
                .setCommandId("flight.takeoff")
                .setState(CommandState.COMMAND_STATE_REJECTED)
                .setError(Error.newBuilder().setCode("command.invalid_params"))
                .build());

        CommandException failure = failureOf(() -> commands.executeAndWait("drone-1", "flight.takeoff", Map.of(), null).join());

        assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, failure.getCategory());
        assertEquals(CommandState.COMMAND_STATE_REJECTED, failure.getResult().getState());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void executeAndWaitIgnoresEventsOfOtherRuns() throws Exception {
        eventsOnExecute.add(event("exec-7", CommandState.COMMAND_STATE_FAILED));
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_SUCCEEDED));

        CommandResult result = commands.executeAndWait("drone-1", "navigation.go_to", Map.of(), Duration.ofSeconds(5))
                .get(5, TimeUnit.SECONDS);

        assertEquals("exec-1", result.getCommandExecutionId());
        assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, result.getState());
    }

    @Test
    void executeAndWaitSeesAnOutcomeThatArrivesBeforeTheReply() throws Exception {
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_SUCCEEDED));

        CommandResult result = commands.executeAndWait("drone-1", "navigation.go_to", Map.of(), Duration.ofSeconds(5))
                .get(5, TimeUnit.SECONDS);

        assertEquals(List.of("WatchCommandEvents", "ExecuteCommand"), received);
        assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, result.getState());
    }

    @Test
    void executeAndWaitStopsWaitingAfterTheCallersTimeout() throws Exception {
        eventsOnExecute.add(event("exec-1", CommandState.COMMAND_STATE_RUNNING));

        CommandException failure = failureOf(() -> commands.executeAndWait("drone-1", "navigation.go_to", Map.of(),
                Duration.ofMillis(200)).join());

        assertEquals(ErrorCategory.ERROR_CATEGORY_TIMEOUT, failure.getCategory());
        assertNull(failure.getResult());
        assertTrue(watchCancelled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void executeAndWaitNeedsAnAssetSn() {
        assertThrows(IllegalArgumentException.class, () -> commands.executeAndWait(
                CommandRequest.builder().assetId("asset-uuid").commandId("flight.takeoff").build(), null));
    }

    @Test
    void paramsRoundTripThroughAStruct() {
        Map<String, Object> params = Map.of("waypoints", List.of(Map.of("latitude", 1.5)), "enabled", true, "mode", "cool");

        Struct struct = Structs.toStruct(params);

        assertEquals(Map.of("waypoints", List.of(Map.of("latitude", 1.5)), "enabled", true, "mode", "cool"),
                Structs.toMap(struct));
        assertEquals(Value.KindCase.LIST_VALUE, struct.getFieldsOrThrow("waypoints").getKindCase());
    }

    @Test
    void anAssetAndACommandIdAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> commands.executeCommand(" ", "flight.takeoff", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> commands.executeCommand("drone-1", "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> commands.listCapabilities(null));
    }

    private static CommandException failureOf(Runnable call) {
        CompletionException failure = assertThrows(CompletionException.class, call::run);
        return assertInstanceOf(CommandException.class, failure.getCause());
    }

    private static CommandEvent event(String executionId, CommandState state) {
        return CommandEvent.newBuilder()
                .setCommandExecutionId(executionId)
                .setCommandId("navigation.go_to")
                .setAsset(AssetRef.newBuilder().setSn("drone-1"))
                .setState(state)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_760_000_000L))
                .build();
    }

    private final class FakeRemoteControlV3 extends RemoteControlServiceGrpc.RemoteControlServiceImplBase {

        @Override
        public void getCapabilities(GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> observer) {
            lastCapabilities.set(request);
            if (refused(observer)) return;
            observer.onNext(GetCapabilitiesResponse.newBuilder().setCapabilities(CapabilitySet.newBuilder()
                    .setAssetSn(request.getAsset().getSn())
                    .addCapabilities(Capability.newBuilder().setCommandId("flight.takeoff"))
                    .addCapabilities(Capability.newBuilder().setCommandId("navigation.go_to"))).build());
            observer.onCompleted();
        }

        @Override
        public void executeCommand(ExecuteCommandRequest request, StreamObserver<ExecuteCommandResponse> observer) {
            lastExecute.set(request);
            received.add("ExecuteCommand");
            if (refused(observer)) return;
            eventsOnExecute.forEach(event -> assetWatches.forEach(watch ->
                    watch.onNext(WatchCommandEventsResponse.newBuilder().setEvent(event).build())));
            CommandResult result = answerWith.get() != null ? answerWith.get() : CommandResult.newBuilder()
                    .setCommandExecutionId("exec-1")
                    .setCommandId(request.getCommand().getCommandId())
                    .setState(CommandState.COMMAND_STATE_ACCEPTED)
                    .build();
            observer.onNext(ExecuteCommandResponse.newBuilder().setResult(result).build());
            observer.onCompleted();
        }

        @Override
        public void cancelCommand(CancelCommandRequest request, StreamObserver<CancelCommandResponse> observer) {
            lastCancel.set(request);
            if (refused(observer)) return;
            observer.onNext(CancelCommandResponse.newBuilder().setResult(CommandResult.newBuilder()
                    .setCommandExecutionId(request.getCommandExecutionId())
                    .setState(CommandState.COMMAND_STATE_CANCELLED)).build());
            observer.onCompleted();
        }

        @Override
        public void watchCommandEvents(WatchCommandEventsRequest request, StreamObserver<WatchCommandEventsResponse> observer) {
            lastWatch.set(request);
            received.add("WatchCommandEvents");
            if (refused(observer)) return;
            ((ServerCallStreamObserver<WatchCommandEventsResponse>) observer).setOnCancelHandler(() -> {
                assetWatches.remove(observer);
                watchCancelled.countDown();
            });
            if (!request.getCommandExecutionId().isBlank()) {
                observer.onNext(WatchCommandEventsResponse.newBuilder()
                        .setEvent(event(request.getCommandExecutionId(), CommandState.COMMAND_STATE_RUNNING)).build());
                observer.onNext(WatchCommandEventsResponse.newBuilder()
                        .setEvent(event(request.getCommandExecutionId(), CommandState.COMMAND_STATE_SUCCEEDED).toBuilder()
                                .setResult(Structs.toStruct(Map.of("arrived", true)))).build());
                observer.onCompleted();
                return;
            }
            openWatch.set(observer);
            assetWatches.add(observer);
        }

        private boolean refused(StreamObserver<?> observer) {
            Status status = refuseWith.get();
            if (status == null) return false;
            observer.onError(status.asRuntimeException());
            return true;
        }
    }
}
