package com.zqnt.sdk.client.commands.application;

import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.commands.domains.CommandRequest;
import com.zqnt.sdk.client.commands.domains.CommandWatch;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Commands any asset by id: discover what an asset can do with {@link #listCapabilities(String)},
 * run it with {@link #executeCommand(String, String, Map)}. Command ids are dotted
 * ({@code flight.takeoff}, {@code navigation.go_to}, {@code dock.open_cover}) and params are a JSON
 * object matching the capability's input schema.
 *
 * <p>Every future fails with a {@link CommandException} when the platform refuses the call or the
 * command is {@code REJECTED}. {@link #executeCommand} completes with the reply as it is, so a command
 * that started and then failed completes normally with state {@code FAILED} and its error on the
 * result; {@link #executeAndWait} completes only with a {@code SUCCEEDED} result.</p>
 */
public interface Commands {

    /** The asset's current capability snapshot: every command id it accepts, with schemas and risk. */
    CompletableFuture<CapabilitySet> listCapabilities(String assetSn);

    /**
     * Runs {@code commandId} on the asset. A {@code null} value in {@code params} is left out:
     * leave a parameter out rather than sending 0 for "not given".
     */
    CompletableFuture<CommandResult> executeCommand(String assetSn, String commandId, Map<String, ?> params);

    /** Runs a command with options: target, timeout, reason, idempotency key, no-fly zone override. */
    CompletableFuture<CommandResult> executeCommand(CommandRequest request);

    /**
     * Runs a command and waits for its outcome. The asset's command events are watched before the
     * command is sent, so a run that finishes right away is not missed; events of other runs are
     * ignored. The future completes with the {@code SUCCEEDED} result and fails with a
     * {@link CommandException} carrying the final result when the run is {@code REJECTED},
     * {@code FAILED}, {@code CANCELLED} or {@code TIMED_OUT}.
     *
     * <p>{@code wait} bounds the wait ({@code null} waits without a limit): when it elapses the future
     * fails with category {@code TIMEOUT} and no result, and the command keeps running on the
     * platform ({@link #cancelCommand} stops it). Completing or cancelling the future closes the
     * watch.</p>
     */
    CompletableFuture<CommandResult> executeAndWait(String assetSn, String commandId, Map<String, ?> params, Duration wait);

    /** {@link #executeAndWait(String, String, Map, Duration)} with options; the asset is named by {@code assetSn}. */
    CompletableFuture<CommandResult> executeAndWait(CommandRequest request, Duration wait);

    CompletableFuture<CommandResult> cancelCommand(String commandExecutionId, String reason);

    /**
     * Events of one command run from now on, ending with its terminal event. A run that finishes
     * before the watch starts is missed; {@link #executeAndWait} waits for a run's outcome.
     */
    CommandWatch watchCommand(String commandExecutionId, Consumer<CommandEvent> onEvent);

    /** Events of every command on the asset from now on, until the watch is closed. */
    CommandWatch watchAsset(String assetSn, Consumer<CommandEvent> onEvent);
}
