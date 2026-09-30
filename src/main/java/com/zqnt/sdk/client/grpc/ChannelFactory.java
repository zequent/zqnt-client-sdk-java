package com.zqnt.sdk.client.grpc;

import com.zqnt.sdk.client.config.ServiceConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Factory for creating gRPC channels with load balancing and service discovery support.
 */
@Slf4j
public class ChannelFactory {

    /**
     * Create a managed channel for a service with the given configuration, without a client
     * credential. Core refuses such calls; kept for callers that add their own interceptors.
     */
    public static ManagedChannel createChannel(ServiceConfig config) {
        return createChannel(config, null);
    }

    /**
     * Create a managed channel for a service that sends {@code clientToken} (see
     * {@link ClientCredentials}) on every call. A null token sends none, and refusals still come
     * back with an explanation.
     */
    public static ManagedChannel createChannel(ServiceConfig config, String clientToken) {
        ManagedChannelBuilder<?> channelBuilder;

        if (config.isUseStork() && config.getStorkServiceName() != null) {
            // Use Stork service discovery
            log.info("Creating channel with Stork service discovery: {}", config.getStorkServiceName());
            channelBuilder = ManagedChannelBuilder.forTarget("stork://" + config.getStorkServiceName());
        } else {
            // Direct connection
            log.info("Creating direct channel: {}:{}", config.getHost(), config.getPort());
            channelBuilder = ManagedChannelBuilder.forAddress(config.getHost(), config.getPort());
        }

        // Configure load balancing
        String loadBalancerPolicy = getLoadBalancerPolicy(config.getLoadBalancerType());
        channelBuilder.defaultLoadBalancingPolicy(loadBalancerPolicy);
        log.info("Load balancer policy: {}", loadBalancerPolicy);

        // Configure keep-alive
        channelBuilder
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(30, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(5, TimeUnit.MINUTES)
                .enableRetry()
                .maxRetryAttempts(5);

        // Configure TLS
        if (config.isUsePlaintext()) {
            channelBuilder.usePlaintext();
        }

        // Configure max inbound message size
        if (config.getMaxInboundMessageSize() > 0) {
            channelBuilder.maxInboundMessageSize(config.getMaxInboundMessageSize());
            log.info("Max inbound message size set to {} bytes for service: {}",
                    config.getMaxInboundMessageSize(), config.getServiceName());
        }

        channelBuilder.intercept(ClientCredentials.interceptor(clientToken));

        ManagedChannel channel = channelBuilder.build();
        log.info("Channel created successfully for service: {}", config.getServiceName());

        return channel;
    }

    private static String getLoadBalancerPolicy(ServiceConfig.LoadBalancerType type) {
        return switch (type) {
            case ROUND_ROBIN -> "round_robin";
            case RANDOM -> "random";
            case LEAST_REQUESTS -> "least_request";
            case POWER_OF_TWO_CHOICES -> "pick_first"; // Fallback
        };
    }
}
