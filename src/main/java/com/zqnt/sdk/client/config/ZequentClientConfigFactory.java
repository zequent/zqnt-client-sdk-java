package com.zqnt.sdk.client.config;

import com.zqnt.sdk.client.config.properties.ZequentClientProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating ZequentClient configuration from properties.
 */
@Slf4j
@ApplicationScoped
public class ZequentClientConfigFactory {

    @Inject
    ZequentClientProperties properties;

    /**
     * Create a GrpcClientConfig from properties.
     */
    public GrpcClientConfig createConfig() {
        log.info("Creating ZequentClient configuration from properties");

        ServiceConfig remoteControlConfig = createServiceConfig(
                "remote-control",
                properties.remoteControlService(),
                ZequentEnvironment.LOCAL_PORTS.get("remote-control")
        );

        ServiceConfig missionAutonomyConfig = createServiceConfig(
                "mission-autonomy",
                properties.missionAutonomyService(),
                ZequentEnvironment.LOCAL_PORTS.get("mission-autonomy")
        );

        ServiceConfig liveDataConfig = createServiceConfig(
                "live-data",
                properties.liveDataService(),
                ZequentEnvironment.LOCAL_PORTS.get("live-data")
        );

        ServiceConfig connectorConfig = createServiceConfig(
                "connector",
                properties.connectorService(),
                ZequentEnvironment.LOCAL_PORTS.get("connector")
        );

        var resilience = properties.resilience();

        return GrpcClientConfig.builder()
                .remoteControlConfig(remoteControlConfig)
                .missionAutonomyConfig(missionAutonomyConfig)
                .liveDataConfig(liveDataConfig)
                .connectorConfig(connectorConfig)
                .maxRetryAttempts(resilience.maxRetryAttempts())
                .retryDelayMillis(resilience.retryDelayMillis())
                .circuitBreakerFailureThreshold(resilience.circuitBreakerFailureThreshold())
                .circuitBreakerWaitDurationMillis(resilience.circuitBreakerWaitDurationMillis())
                .connectionTimeoutSeconds(resilience.connectionTimeoutSeconds())
                .requestTimeoutSeconds(resilience.requestTimeoutSeconds())
                .streamInactivityTimeoutSeconds(resilience.streamInactivityTimeoutSeconds())
                .telemetryHeartbeatTimeoutSeconds(resilience.telemetryHeartbeatTimeoutSeconds())
                .liveDataSchedulerThreads(resilience.liveDataSchedulerThreads())
                .defaultLoadBalancerType(ServiceConfig.LoadBalancerType.ROUND_ROBIN)
                .clientToken(com.zqnt.sdk.client.grpc.ClientCredentials.resolve(
                        properties.clientToken().orElse(null)))
                .build();
    }

    private ServiceConfig createServiceConfig(
            String serviceName,
            ZequentClientProperties.ServiceProperties props,
            int defaultPort) {

        int port = props.port() != 9090 ? props.port() : defaultPort;

        ServiceConfig.LoadBalancerType loadBalancerType;
        try {
            loadBalancerType = ServiceConfig.LoadBalancerType.valueOf(props.loadBalancerType());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid load balancer type '{}' for service '{}', using ROUND_ROBIN",
                    props.loadBalancerType(), serviceName);
            loadBalancerType = ServiceConfig.LoadBalancerType.ROUND_ROBIN;
        }

        String storkServiceName = props.storkServiceName();
        if (storkServiceName == null || storkServiceName.isEmpty()) {
            storkServiceName = serviceName + "-service";
        }

        ServiceConfig config = ServiceConfig.builder()
                .serviceName(serviceName)
                .host(props.host())
                .port(port)
                .usePlaintext(props.usePlaintext())
                .useStork(props.useStork())
                .storkServiceName(storkServiceName)
                .loadBalancerType(loadBalancerType)
                .maxInboundMessageSize(props.maxInboundMessageSize())
                .build();

        log.info("Service '{}' configured: host={}, port={}, useStork={}, loadBalancer={}",
                serviceName, config.getHost(), config.getPort(),
                config.isUseStork(), config.getLoadBalancerType());

        return config;
    }
}
