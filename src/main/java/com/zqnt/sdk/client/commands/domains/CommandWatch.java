package com.zqnt.sdk.client.commands.domains;

import io.grpc.stub.ClientCallStreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running watch on command events. {@link #done()} completes when the platform ends the watch
 * (a watched run reached its terminal event) or {@link #close()} is called, and fails with a
 * {@link CommandException} when the platform refuses or drops it.
 */
public final class CommandWatch implements AutoCloseable {

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
        if (done.complete(null)) {
            ClientCallStreamObserver<?> running = call.getAndSet(null);
            if (running != null) running.cancel("watch closed by the client", null);
        }
    }

    public void bind(ClientCallStreamObserver<?> running) {
        call.set(running);
        if (done.isDone()) {
            ClientCallStreamObserver<?> bound = call.getAndSet(null);
            if (bound != null) bound.cancel("watch closed before it started", null);
        }
    }

    public void completed() {
        done.complete(null);
    }

    public void failed(Throwable failure) {
        done.completeExceptionally(CommandException.from(failure));
    }
}
