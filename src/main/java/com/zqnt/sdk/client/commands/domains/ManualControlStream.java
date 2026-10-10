package com.zqnt.sdk.client.commands.domains;

import com.zqnt.protos.edge.v3.ManualControlInput;

import java.util.concurrent.CompletableFuture;

/**
 * Stick input for one asset over a single stream: roll, pitch, yaw and throttle from -1 to 1, and an
 * optional gimbal pitch. The asset has to be in manual control first ({@code flight.manual.enter}).
 *
 * <p>{@link #close()} ends the session; {@link #done()} then completes with the number of inputs the
 * platform passed on to the asset. {@link #done()} fails with a {@link CommandException} when the
 * platform refuses the session (no adapter reachable for the asset, an asset of another organization)
 * or the adapter drops it; {@link #send} throws an {@link IllegalStateException} once the session is
 * over.</p>
 */
public interface ManualControlStream extends AutoCloseable {

    String assetSn();

    void send(ManualControlInput input);

    default void send(float roll, float pitch, float yaw, float throttle) {
        send(ManualControlInput.newBuilder().setRoll(roll).setPitch(pitch).setYaw(yaw).setThrottle(throttle).build());
    }

    CompletableFuture<Long> done();

    @Override
    void close();
}
