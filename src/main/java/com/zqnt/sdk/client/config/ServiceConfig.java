package com.zqnt.sdk.client.config;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for an individual gRPC service.
 */
@Data
@Builder(toBuilder = true)
public class ServiceConfig {

    private String serviceName;

    @Builder.Default
    private String host = "localhost";

    @Builder.Default
    private int port = 9090;

    @Builder.Default
    private boolean usePlaintext = true;

    // Stork service discovery
    @Builder.Default
    private boolean useStork = false;

    @Builder.Default
    private String storkServiceName = null;

    // Load balancer type: round-robin, random, least-requests
    @Builder.Default
    private LoadBalancerType loadBalancerType = LoadBalancerType.ROUND_ROBIN;

    // Maximum inbound message size in bytes (-1 = use gRPC default of 4 MB)
    @Builder.Default
    private int maxInboundMessageSize = -1;

    public enum LoadBalancerType {
        ROUND_ROBIN,
        RANDOM,
        LEAST_REQUESTS,
        POWER_OF_TWO_CHOICES
    }
}
