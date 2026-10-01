package com.zqnt.sdk.client.remotecontrol.application;

import com.zqnt.sdk.client.remotecontrol.domains.*;

import java.util.concurrent.CompletableFuture;

public interface RemoteControl {


    // Flight ops
    CompletableFuture<TakeoffResponse> takeoff(TakeoffRequest request);
    CompletableFuture<RemoteControlResponse> goTo(GoToRequest request);

    /**
     * A fly-to that may fly straight through a HARD_BLOCK or REQUIRE_APPROVAL no-fly zone which would
     * otherwise refuse it. The platform honours the override only for an organization admin or a
     * system admin (by the caller's own token) and records it in the run's safety audit; anybody else
     * is refused. {@code false} is exactly {@link #goTo(GoToRequest)}.
     */
    default CompletableFuture<RemoteControlResponse> goTo(GoToRequest request, boolean noFlyZoneOverride) {
        if (!noFlyZoneOverride) return goTo(request);
        throw new UnsupportedOperationException("This RemoteControl does not support a no-fly zone override");
    }
    CompletableFuture<RemoteControlResponse> returnToHome(ReturnToHomeRequest request);
    CompletableFuture<RemoteControlResponse> lookAt(LookAtRequest request);

    // Manual Control
    CompletableFuture<RemoteControlResponse> enterManualControl(ManualControlRequest request);
    CompletableFuture<RemoteControlResponse> exitManualControl(ManualControlRequest request);
	ManualControlInputSession startManualControlInput(String sn, String assetId);

    // Dock ops
    CompletableFuture<RemoteControlResponse> openCover(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> closeCover(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> startCharging(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> stopCharging(DockOperationRequest request);

    // Asset ops
    CompletableFuture<RemoteControlResponse> rebootAsset(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> bootSubAsset(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> debugMode(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> changeAcMode(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> takePhoto(DockOperationRequest request);
    CompletableFuture<RemoteControlResponse> liveStreamSplitScreen(LiveStreamSplitScreenRequest request);

    // Payload / integrator-defined commands
    CompletableFuture<CapabilitySnapshot> getCapabilities(String sn);
    CompletableFuture<CustomCommandResponse> sendCustomCommand(CustomCommandRequest request);
}
