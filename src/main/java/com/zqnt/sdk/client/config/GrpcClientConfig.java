package com.zqnt.sdk.client.config;

import lombok.Builder;
import lombok.Data;

/**
 * Global configuration for all gRPC client connections with resilience settings.
 */
@Data
@Builder
public class GrpcClientConfig {

    // Service-specific configurations
    private ServiceConfig remoteControlConfig;
    private ServiceConfig missionAutonomyConfig;
    private ServiceConfig liveDataConfig;
    private ServiceConfig connectorConfig;

    // Global retry configuration
    @Builder.Default
    private int maxRetryAttempts = 3;

    @Builder.Default
    private long retryDelayMillis = 1000;

    // Global circuit breaker configuration
    @Builder.Default
    private int circuitBreakerFailureThreshold = 5;

    @Builder.Default
    private long circuitBreakerWaitDurationMillis = 30000; // 30 seconds

    // Global timeout configuration
    @Builder.Default
    private int connectionTimeoutSeconds = 30;

    @Builder.Default
    private int requestTimeoutSeconds = 60;

    // Long-lived LiveData stream configuration
    @Builder.Default
    private int streamInactivityTimeoutSeconds = 5 * 60;

    /** Allows roughly three missed 10-second application heartbeats before reconnecting telemetry. */
    @Builder.Default
    private int telemetryHeartbeatTimeoutSeconds = 35;

    @Builder.Default
    private int liveDataSchedulerThreads = 2;

    /**
     * The client credential sent on every call (see {@link com.zqnt.sdk.client.grpc.ClientCredentials}).
     * Null: the {@code ZQNT_CLIENT_TOKEN} environment variable, if set.
     */
    @lombok.ToString.Exclude
    private String clientToken;

    // Default load balancer for all services
    @Builder.Default
    private ServiceConfig.LoadBalancerType defaultLoadBalancerType = ServiceConfig.LoadBalancerType.ROUND_ROBIN;
}
