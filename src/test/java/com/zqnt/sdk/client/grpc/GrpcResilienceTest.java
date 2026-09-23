package com.zqnt.sdk.client.grpc;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class GrpcResilienceTest {

    private static final int THRESHOLD = 3;

    private final GrpcResilience resilience = new GrpcResilience(0, 1, THRESHOLD, 60_000);

    private void fail(Status status) {
        var call = resilience.executeWithResilienceAsync(
                () -> CompletableFuture.failedFuture(status.asRuntimeException()));
        assertThrows(CompletionException.class, call::join);
    }

    @Test
    void unimplementedDoesNotOpenTheCircuit() {
        for (int i = 0; i < THRESHOLD * 3; i++) {
            fail(Status.UNIMPLEMENTED);
        }

        assertFalse(resilience.isCircuitOpen());
        assertEquals(0, resilience.getFailureCount());
    }

    @Test
    void requestErrorsDoNotOpenTheCircuit() {
        for (Status status : new Status[]{Status.INVALID_ARGUMENT, Status.NOT_FOUND, Status.PERMISSION_DENIED,
                Status.UNAUTHENTICATED, Status.FAILED_PRECONDITION, Status.ALREADY_EXISTS}) {
            fail(status);
        }

        assertFalse(resilience.isCircuitOpen());
    }

    @Test
    void unavailableStillOpensTheCircuit() {
        for (int i = 0; i < THRESHOLD; i++) {
            fail(Status.UNAVAILABLE);
        }

        assertTrue(resilience.isCircuitOpen());
        var rejected = assertThrows(RuntimeException.class,
                () -> resilience.executeWithResilienceAsync(() -> CompletableFuture.completedFuture("ok")));
        assertTrue(rejected.getMessage().contains("Circuit breaker is OPEN"));
    }

    @Test
    void nonStatusExceptionsStillCount() {
        for (int i = 0; i < THRESHOLD; i++) {
            var call = resilience.executeWithResilienceAsync(
                    () -> CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException("slow")));
            assertThrows(CompletionException.class, call::join);
        }

        assertTrue(resilience.isCircuitOpen());
    }

    @Test
    void blockingUnimplementedDoesNotOpenTheCircuit() {
        for (int i = 0; i < THRESHOLD * 2; i++) {
            assertThrows(RuntimeException.class, () -> resilience.executeBlocking(() -> {
                throw Status.UNIMPLEMENTED.asRuntimeException();
            }));
        }

        assertFalse(resilience.isCircuitOpen());
    }
}
