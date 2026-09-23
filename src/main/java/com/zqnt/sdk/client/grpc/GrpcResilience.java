package com.zqnt.sdk.client.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Provides resilience patterns (retry, circuit breaker, timeout) for gRPC calls.
 * Framework-agnostic implementation using only standard Java APIs.
 */
@Slf4j
public class GrpcResilience {

    /**
     * Status codes the server itself answered with. They say the request was wrong or unsupported, not
     * that the service is unhealthy, so they must not count towards opening the circuit — one breaker is
     * shared by every method of a service client, and five clicks on an unimplemented command would
     * otherwise block every other command for every user.
     */
    private static final Set<Status.Code> NON_TRANSIENT_CODES = EnumSet.of(
            Status.Code.CANCELLED,
            Status.Code.INVALID_ARGUMENT,
            Status.Code.NOT_FOUND,
            Status.Code.ALREADY_EXISTS,
            Status.Code.PERMISSION_DENIED,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.ABORTED,
            Status.Code.OUT_OF_RANGE,
            Status.Code.UNIMPLEMENTED,
            Status.Code.UNAUTHENTICATED);

    private final int maxRetryAttempts;
    private final long retryDelayMillis;
    private final int circuitBreakerFailureThreshold;
    private final long circuitBreakerWaitDurationMillis;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long circuitOpenedAt = 0;
    private volatile boolean circuitOpen = false;

    public GrpcResilience(int maxRetryAttempts, long retryDelayMillis,
                          int circuitBreakerFailureThreshold, long circuitBreakerWaitDurationMillis) {
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        this.circuitBreakerWaitDurationMillis = circuitBreakerWaitDurationMillis;
    }

    /**
     * Execute an async gRPC call with retry and circuit breaker.
     * Uses CompletableFuture for framework-agnostic async operations.
     */
    public <T> CompletableFuture<T> executeWithResilienceAsync(Supplier<CompletableFuture<T>> futureSupplier) {
        checkCircuitBreakerBlocking(); // Throws if circuit is open

        return executeWithRetry(futureSupplier, 0);
    }

    private <T> CompletableFuture<T> executeWithRetry(Supplier<CompletableFuture<T>> futureSupplier, int attempt) {
        return futureSupplier.get()
                .thenApply(result -> {
                    recordSuccess();
                    return result;
                })
                .exceptionallyCompose(throwable -> {
                    Throwable cause = unwrapException(throwable);

                    if (attempt < maxRetryAttempts && isRetryable(cause)) {
                        long delay = retryDelayMillis * (attempt + 1); // Exponential backoff
                        log.warn("Attempt {} failed, retrying after {} ms: {}",
                                attempt + 1, delay, cause.getMessage());

                        // Create a delayed CompletableFuture for retry
                        CompletableFuture<T> delayedRetry = new CompletableFuture<>();
                        CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .execute(() -> {
                                    executeWithRetry(futureSupplier, attempt + 1)
                                            .whenComplete((result, error) -> {
                                                if (error != null) {
                                                    delayedRetry.completeExceptionally(error);
                                                } else {
                                                    delayedRetry.complete(result);
                                                }
                                            });
                                });
                        return delayedRetry;
                    } else {
                        if (countsAsFailure(cause)) {
                            recordFailure(cause);
                        }
                        return CompletableFuture.failedFuture(
                                new RuntimeException("All retry attempts failed after " + (attempt + 1) + " tries", cause)
                        );
                    }
                });
    }

    /**
     * Execute a blocking call with retry and circuit breaker.
     */
    public <T> T executeBlocking(Supplier<T> supplier) {
        checkCircuitBreakerBlocking();

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetryAttempts) {
            try {
                T result = supplier.get();
                recordSuccess();
                return result;
            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt <= maxRetryAttempts && isRetryable(e)) {
                    log.warn("Attempt {} failed, retrying after {} ms: {}",
                            attempt, retryDelayMillis, e.getMessage());
                    try {
                        Thread.sleep(retryDelayMillis * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                } else {
                    break;
                }
            }
        }

        if (countsAsFailure(lastException)) {
            recordFailure(lastException);
        }
        throw new RuntimeException("All retry attempts failed", lastException);
    }

    public void checkCircuitBreaker() {
        checkCircuitBreakerBlocking();
    }

    private synchronized void checkCircuitBreakerBlocking() {
        if (circuitOpen) {
            long now = System.currentTimeMillis();
            if (now - circuitOpenedAt >= circuitBreakerWaitDurationMillis) {
                log.info("Circuit breaker attempting to close after wait duration");
                circuitOpen = false;
                failureCount.set(0);
            } else {
                throw new RuntimeException("Circuit breaker is OPEN - rejecting call");
            }
        }
    }

    public synchronized void recordSuccess() {
        if (failureCount.get() > 0) {
            log.debug("Request succeeded - resetting failure count");
            failureCount.set(0);
        }
        if (circuitOpen) {
            log.info("Circuit breaker closing after successful request");
            circuitOpen = false;
        }
    }

    public void recordFailure(Throwable throwable) {
        int failures = failureCount.incrementAndGet();
        log.warn("Request failed - failure count: {}/{}", failures, circuitBreakerFailureThreshold);

        if (failures >= circuitBreakerFailureThreshold) {
            synchronized (this) {
                if (!circuitOpen) {
                    circuitOpen = true;
                    circuitOpenedAt = System.currentTimeMillis();
                    log.error("Circuit breaker OPENED after {} failures", failures);
                }
            }
        }
    }

    private boolean isRetryable(Throwable e) {
        if (e instanceof StatusRuntimeException) {
            Status.Code code = ((StatusRuntimeException) e).getStatus().getCode();
            return code == Status.Code.UNAVAILABLE ||
                    code == Status.Code.DEADLINE_EXCEEDED ||
                    code == Status.Code.RESOURCE_EXHAUSTED ||
                    code == Status.Code.UNKNOWN ||
                    code == Status.Code.INTERNAL;
        }
        return true; // Retry other exceptions
    }

    private boolean countsAsFailure(Throwable e) {
        if (e instanceof StatusRuntimeException) {
            return !NON_TRANSIENT_CODES.contains(((StatusRuntimeException) e).getStatus().getCode());
        }
        return true;
    }

    private Throwable unwrapException(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public int getFailureCount() {
        return failureCount.get();
    }
}
