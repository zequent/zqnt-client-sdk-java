package com.zqnt.sdk.client.telemetry.domains;

import com.zqnt.sdk.client.commands.domains.CommandException;
import io.grpc.stub.ClientCallStreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running live data watch. {@link #done()} completes when {@link #close()} is called or the platform
 * ends the watch, and fails with a {@link CommandException} when the platform refuses or drops it, or
 * when neither an item nor a heartbeat arrived in time. A watch does not reconnect: watch again.
 */
public final class TelemetryWatch implements AutoCloseable {

    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final AtomicReference<ClientCallStreamObserver<?>> call = new AtomicReference<>();

    public CompletableFuture<Void> done() {
        return done;
    }

    public boolean isDone() {
        return done.isDone();
    }

    @Override
    public void close() {
        if (done.complete(null)) cancel("watch closed by the client");
    }

    public void bind(ClientCallStreamObserver<?> running) {
        call.set(running);
        if (done.isDone()) cancel("watch closed before it started");
    }

    public void completed() {
        done.complete(null);
    }

    public void failed(Throwable failure) {
        if (done.completeExceptionally(CommandException.from(failure))) cancel(failure.getMessage());
    }

    private void cancel(String reason) {
        ClientCallStreamObserver<?> running = call.getAndSet(null);
        if (running != null) running.cancel(reason, null);
    }
}
