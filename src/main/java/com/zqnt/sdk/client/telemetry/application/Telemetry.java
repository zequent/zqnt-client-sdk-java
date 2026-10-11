package com.zqnt.sdk.client.telemetry.application;

import com.zqnt.protos.telemetry.v3.Alert;
import com.zqnt.protos.telemetry.v3.AlertSeverity;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import com.zqnt.protos.telemetry.v3.SourceStatus;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.sdk.client.telemetry.domains.TelemetryWatch;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Live data of assets: telemetry, detections and alerts. A {@link TelemetrySample} has the fields every asset shares
 * (position, relative altitude, heading, speeds, battery); everything device-specific is in its
 * {@code details}, under the keys the asset declares in its capabilities' {@code telemetry_fields}.
 */
public interface Telemetry {

    /**
     * Samples of the given assets from now on, until the watch is closed. No asset (an empty or
     * {@code null} collection) watches every asset of the caller's organization; a dock's serial also
     * brings the samples of its drone. {@code minInterval} keeps at most one sample per asset per
     * interval ({@code null} = every sample). {@code onSourceStatus} hears every watched asset's
     * state first, then each time an asset goes online, stale or silent.
     *
     * <p>The platform sends a heartbeat on a quiet stream, so the watch fails with category
     * {@code SERVICE} when neither arrives within the client's {@code telemetryHeartbeatTimeoutSeconds}.</p>
     */
    TelemetryWatch watchTelemetry(Collection<String> assetSns, Duration minInterval,
                                  Consumer<TelemetrySample> onSample, Consumer<SourceStatus> onSourceStatus);

    default TelemetryWatch watchTelemetry(Collection<String> assetSns, Consumer<TelemetrySample> onSample) {
        return watchTelemetry(assetSns, null, onSample, status -> { });
    }

    /**
     * Detection batches of the given assets (empty or {@code null}: every asset of the caller's
     * organization) from now on, until the watch is closed. Fails like {@link #watchTelemetry} when
     * neither a batch nor a heartbeat arrives in time.
     */
    TelemetryWatch watchDetections(Collection<String> assetSns, Consumer<DetectionBatch> onBatch);

    /**
     * Alerts of the given assets (empty or {@code null}: every asset of the caller's organization) at
     * {@code minSeverity} or above ({@code null}: every severity), until the watch is closed. Fails like
     * {@link #watchTelemetry} when neither an alert nor a heartbeat arrives in time.
     */
    TelemetryWatch watchAlerts(Collection<String> assetSns, AlertSeverity minSeverity, Consumer<Alert> onAlert);
}
