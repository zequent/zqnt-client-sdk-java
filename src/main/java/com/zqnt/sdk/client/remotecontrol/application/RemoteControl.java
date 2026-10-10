package com.zqnt.sdk.client.remotecontrol.application;

import com.zqnt.sdk.client.remotecontrol.domains.*;

import java.util.concurrent.CompletableFuture;

/**
 * The 2.x typed remote control, kept for 2.x platforms. Every typed command is deprecated: on a 3.0
 * platform use {@link com.zqnt.sdk.client.ZequentClient#commands()} with the command id and params
 * (see MIGRATION.md). {@link #startManualControlInput} is not deprecated; it stays the way to fly by hand.
 */
public interface RemoteControl {


    // Flight ops
    @Deprecated(since = "3.0")
    CompletableFuture<TakeoffResponse> takeoff(TakeoffRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> goTo(GoToRequest request);

    /**
     * A fly-to that may fly straight through a HARD_BLOCK or REQUIRE_APPROVAL no-fly zone which would
     * otherwise refuse it. The platform honours the override only for an organization admin or a
     * system admin (by the caller's own token) and records it in the run's safety audit; anybody else
     * is refused. {@code false} is exactly {@link #goTo(GoToRequest)}.
     */
    @Deprecated(since = "3.0")
    default CompletableFuture<RemoteControlResponse> goTo(GoToRequest request, boolean noFlyZoneOverride) {
        if (!noFlyZoneOverride) return goTo(request);
        throw new UnsupportedOperationException("This RemoteControl does not support a no-fly zone override");
    }
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> returnToHome(ReturnToHomeRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> lookAt(LookAtRequest request);

    // Manual Control
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> enterManualControl(ManualControlRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> exitManualControl(ManualControlRequest request);
	ManualControlInputSession startManualControlInput(String sn, String assetId);

    // Dock ops
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> openCover(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> closeCover(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> startCharging(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> stopCharging(DockOperationRequest request);

    // Asset ops
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> rebootAsset(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> bootSubAsset(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> debugMode(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> changeAcMode(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> takePhoto(DockOperationRequest request);
    @Deprecated(since = "3.0")
    CompletableFuture<RemoteControlResponse> liveStreamSplitScreen(LiveStreamSplitScreenRequest request);

    // Payload / integrator-defined commands
    @Deprecated(since = "3.0")
    CompletableFuture<CapabilitySnapshot> getCapabilities(String sn);
    @Deprecated(since = "3.0")
    CompletableFuture<CustomCommandResponse> sendCustomCommand(CustomCommandRequest request);
}
