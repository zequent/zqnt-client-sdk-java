package com.zqnt.sdk.client.commands.domains;

import com.zqnt.protos.capability.v3.Target;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Duration;
import java.util.Map;

/**
 * One command with its options. {@code assetSn} or {@code assetId} names the asset, {@code commandId}
 * the dotted command. {@code timeout} unset is the capability's own default; {@code reason} is
 * required for CRITICAL commands; {@code idempotencyKey} unset is a fresh key per call, kept across
 * the SDK's own retries.
 */
@Value
@Builder(toBuilder = true)
public class CommandRequest {
    String assetSn;
    String assetId;
    String commandId;
    @Singular
    Map<String, Object> params;
    Target target;
    Duration timeout;
    String reason;
    boolean noFlyZoneOverride;
    String idempotencyKey;
}
