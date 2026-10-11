package com.zqnt.sdk.client.connector.domains;

import io.grpc.stub.ClientCallStreamObserver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running connector stream. {@link #stop()} cancels it.
 */
public class StreamHandle implements AutoCloseable {

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ClientCallStreamObserver<?>> activeCall = new AtomicReference<>();

    public void stop() {
        stopped.set(true);
        cancelActiveCall("stream stopped by client");
    }

    public boolean isStopped() {
        return stopped.get();
    }

    @Override
    public void close() {
        stop();
    }

    public void bindActiveCall(ClientCallStreamObserver<?> call) {
        activeCall.set(call);
        if (stopped.get()) {
            cancelActiveCall("stream stopped before start");
        }
    }

    public void cancelActiveCall(String reason) {
        ClientCallStreamObserver<?> call = activeCall.getAndSet(null);
        if (call != null) {
            try {
                call.cancel(reason, null);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
