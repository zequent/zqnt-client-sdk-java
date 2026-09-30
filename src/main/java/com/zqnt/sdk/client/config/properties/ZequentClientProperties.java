package com.zqnt.sdk.client.config.properties;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration properties for ZequentClient.
 * Can be configured via application.properties or environment variables.
 *
 * Example application.properties:
 * <pre>
 * zequent.remote-control-service.host=localhost
 * zequent.remote-control-service.port=9091
 * zequent.remote-control-service.use-stork=false
 * </pre>
 *
 * Example environment variables:
 * <pre>
 * ZEQUENT_REMOTE_CONTROL_SERVICE_HOST=localhost
 * ZEQUENT_REMOTE_CONTROL_SERVICE_PORT=9091
 * </pre>
 */
@ConfigMapping(prefix = "zequent")
public interface ZequentClientProperties {


    /**
     * Remote Control Service configuration.
     */
    @WithName("remote-control-service")
    ServiceProperties remoteControlService();

    /**
     * Mission Autonomy Service configuration.
     */
    @WithName("mission-autonomy-service")
    ServiceProperties missionAutonomyService();

    /**
     * Live Data Service configuration.
     */
    @WithName("live-data-service")
    ServiceProperties liveDataService();

    /** Connector Service configuration. */
    @WithName("connector-service")
    ServiceProperties connectorService();

    /**
     * The client credential sent on every call ({@code zequent.client-token}, or the environment
     * variable {@code ZEQUENT_CLIENT_TOKEN}); when unset, {@code ZQNT_CLIENT_TOKEN} is used. Issued in
     * the console under Access &amp; Integrations &rarr; Credentials, kind "client".
     */
    java.util.Optional<String> clientToken();

    /**
     * Global resilience configuration.
     */
    ResilienceProperties resilience();

    /**
     * Configuration for an individual service.
     */
    interface ServiceProperties {

        @WithDefault("localhost")
        String host();

        @WithDefault("9090")
        int port();

        @WithDefault("true")
        boolean usePlaintext();

        @WithDefault("false")
        boolean useStork();

        @WithDefault("")
        String storkServiceName();

        @WithDefault("ROUND_ROBIN")
        String loadBalancerType();

        /**
         * Maximum inbound gRPC message size in bytes. Defaults to -1 (gRPC default: 4 MB).
         * Set to a higher value (e.g. 20971520 for 20 MB) if messages exceed 4 MB.
         */
        @WithDefault("-1")
        int maxInboundMessageSize();
    }

    /**
     * Global resilience configuration.
     */
    interface ResilienceProperties {

        @WithDefault("3")
        int maxRetryAttempts();

        @WithDefault("1000")
        long retryDelayMillis();

        @WithDefault("5")
        int circuitBreakerFailureThreshold();

        @WithDefault("30000")
        long circuitBreakerWaitDurationMillis();

        @WithDefault("30")
        int connectionTimeoutSeconds();

        @WithDefault("60")
        int requestTimeoutSeconds();

        @WithDefault("300")
        int streamInactivityTimeoutSeconds();

        @WithDefault("35")
        int telemetryHeartbeatTimeoutSeconds();

        @WithDefault("2")
        int liveDataSchedulerThreads();
    }
}
