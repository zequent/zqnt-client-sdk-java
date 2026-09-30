package com.zqnt.sdk.client;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.config.ZequentEnvironment;
import com.zqnt.sdk.client.connector.application.Connector;
import com.zqnt.sdk.client.connector.application.impl.ConnectorImpl;
import com.zqnt.sdk.client.grpc.ChannelFactory;
import com.zqnt.sdk.client.grpc.ClientCredentials;
import com.zqnt.sdk.client.livedata.application.LiveData;
import com.zqnt.sdk.client.livedata.application.impl.LiveDataImpl;
import com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy;
import com.zqnt.sdk.client.missionautonomy.application.impl.MissionAutonomyImpl;
import com.zqnt.sdk.client.remotecontrol.application.RemoteControl;
import com.zqnt.sdk.client.remotecontrol.application.impl.RemoteControlImpl;

import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Central Zequent Framework Client for interacting with all Zequent services.
 * Each service has its own connection, port, and load balancing configuration.
 * Supports Stork service discovery for Kubernetes/Docker deployments.
 *
 * This class is NOT a CDI bean itself - it's created by ZequentClientProducer.
 * Customers inject it using @Inject ZequentClient.
 */
@Slf4j
public class ZequentClient implements AutoCloseable {

    private final GrpcClientConfig config;
    private final RemoteControl remoteControl;
    private final MissionAutonomy missionAutonomy;
    private final LiveData liveData;
    private final Connector connector;
    private final List<ManagedChannel> channels;


    /**
     * Package-private constructor used by ZequentClientProducer.
     * Customers should NOT create instances directly - use @Inject instead!
     *
     * Note: This constructor is called by ZequentClientProducer, NOT by CDI directly.
     */
    ZequentClient(GrpcClientConfig config, RemoteControl remoteControl,
                  MissionAutonomy missionAutonomy, LiveData liveData, Connector connector,
                  List<ManagedChannel> channels) {
        this.config = config;
        this.remoteControl = remoteControl;
        this.missionAutonomy = missionAutonomy;
        this.liveData = liveData;
        this.connector = connector;
        this.channels = channels;
        log.info("ZequentClient initialized with {} channels", channels.size());
    }

    /**
     * Create a builder for configuring the ZequentClient (for standalone usage without CDI).
     * Most customers should use @Inject instead!
     *
     * @deprecated Use CDI injection with @Inject ZequentClient instead
     */
    @Deprecated
    public static ZequentClientBuilder builder() {
        return new ZequentClientBuilder();
    }

    /**
     * A client configured entirely from the environment, for applications without CDI — the same
     * variables every Zequent client SDK reads (see {@link com.zqnt.sdk.client.config.ZequentEnvironment}):
     * {@code CONNECTOR_SERVICE_HOST}/{@code _PORT}/{@code _USE_PLAINTEXT} (and the same for
     * {@code REMOTE_CONTROL_SERVICE}, {@code LIVE_DATA_SERVICE}, {@code MISSION_AUTONOMY_SERVICE}) and
     * {@code ZQNT_CLIENT_TOKEN}. Nothing set is the local development stack on localhost. To add
     * interceptors or override one service, use {@code builder().fromEnvironment()} instead.
     */
    @SuppressWarnings("deprecation")
    public static ZequentClient fromEnvironment() {
        return builder().fromEnvironment().build();
    }

    /**
     * Access remote control operations for drones and docks.
     *
     * @return RemoteControl interface for flight operations, manual control, and dock operations
     */
    public RemoteControl remoteControl() {
        return remoteControl;
    }

    /**
     * Access mission autonomy operations.
     *
     * @return MissionAutonomy interface for mission planning and execution
     */
    public MissionAutonomy missionAutonomy() {
        return missionAutonomy;
    }

    /**
     * Access live telemetry data streaming.
     *
     * @return LiveData interface for streaming telemetry data
     */
    public LiveData liveData() {
        return liveData;
    }

    /** Access all Connector service endpoints. */
    public Connector connector() {
        return connector;
    }

    /**
     * Get the current configuration.
     */
    public GrpcClientConfig getConfig() {
        return config;
    }

    /**
     * Check if all channels are connected.
     */
    public boolean isConnected() {
        return channels.stream()
                .allMatch(ch -> !ch.isShutdown() && !ch.isTerminated());
    }

    @Override
    public void close() {
        log.info("Shutting down ZequentClient with {} channels", channels.size());

        // Shutdown service implementations first
        if (remoteControl instanceof RemoteControlImpl) {
            ((RemoteControlImpl) remoteControl).shutdown();
        }
        if (missionAutonomy instanceof MissionAutonomyImpl) {
            ((MissionAutonomyImpl) missionAutonomy).shutdown();
        }
        if (liveData instanceof LiveDataImpl) {
            ((LiveDataImpl) liveData).shutdown();
        }
        if (connector instanceof ConnectorImpl) {
            ((ConnectorImpl) connector).shutdown();
        }

        // Then shutdown channels
        for (ManagedChannel channel : channels) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Channel did not terminate gracefully, forcing shutdown");
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while shutting down channel", e);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("All channels closed");
    }

    /**
     * Builder for creating configured ZequentClient instances with multi-service support.
     */
    public static class ZequentClientBuilder {
        // Global resilience settings
        private int maxRetryAttempts = 3;
        private long retryDelayMillis = 1000;
        private int circuitBreakerFailureThreshold = 5;
        private long circuitBreakerWaitDurationMillis = 30000;
        private int connectionTimeoutSeconds = 30;
        private int requestTimeoutSeconds = 60;
        private int streamInactivityTimeoutSeconds = 5 * 60;
        private int telemetryHeartbeatTimeoutSeconds = 35;
        private int liveDataSchedulerThreads = 2;
        private ServiceConfig.LoadBalancerType defaultLoadBalancerType = ServiceConfig.LoadBalancerType.ROUND_ROBIN;
        private String clientToken;
        private final List<io.grpc.ClientInterceptor> interceptors = new ArrayList<>();
        private java.util.function.Function<String, String> environment;

        // Service-specific builders
        private ServiceConfigBuilder remoteControlBuilder;
        private ServiceConfigBuilder missionAutonomyBuilder;
        private ServiceConfigBuilder liveDataBuilder;
        private ServiceConfigBuilder connectorBuilder;

        public ZequentClientBuilder maxRetryAttempts(int maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
            return this;
        }

        public ZequentClientBuilder retryDelayMillis(long retryDelayMillis) {
            this.retryDelayMillis = retryDelayMillis;
            return this;
        }

        public ZequentClientBuilder circuitBreakerFailureThreshold(int threshold) {
            this.circuitBreakerFailureThreshold = threshold;
            return this;
        }

        public ZequentClientBuilder circuitBreakerWaitDurationMillis(long waitDuration) {
            this.circuitBreakerWaitDurationMillis = waitDuration;
            return this;
        }

        public ZequentClientBuilder connectionTimeoutSeconds(int timeout) {
            this.connectionTimeoutSeconds = timeout;
            return this;
        }

        public ZequentClientBuilder requestTimeoutSeconds(int timeout) {
            this.requestTimeoutSeconds = timeout;
            return this;
        }

        public ZequentClientBuilder streamInactivityTimeoutSeconds(int timeout) {
            this.streamInactivityTimeoutSeconds = timeout;
            return this;
        }

        public ZequentClientBuilder telemetryHeartbeatTimeoutSeconds(int timeout) {
            this.telemetryHeartbeatTimeoutSeconds = timeout;
            return this;
        }

        public ZequentClientBuilder liveDataSchedulerThreads(int threads) {
            this.liveDataSchedulerThreads = threads;
            return this;
        }

        /**
         * The client credential to call the platform with — issued in the console (Access &amp;
         * Integrations &rarr; Credentials, kind "client"). Without one, the {@code ZQNT_CLIENT_TOKEN}
         * environment variable is used; with neither, the platform refuses every call.
         */
        public ZequentClientBuilder clientToken(String clientToken) {
            this.clientToken = clientToken;
            return this;
        }

        /**
         * Adds an interceptor to every channel the client creates (all four services, unary and
         * streaming calls) — for a host application whose credential is not one fixed token, e.g.
         * one that forwards its own caller's token. Interceptors run in the order added, before the
         * SDK's credential interceptor; an {@code authorization} header they set wins over
         * {@link #clientToken(String)}.
         */
        public ZequentClientBuilder interceptor(io.grpc.ClientInterceptor interceptor) {
            this.interceptors.add(java.util.Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /** Adds several interceptors; see {@link #interceptor(io.grpc.ClientInterceptor)}. */
        public ZequentClientBuilder interceptors(java.util.Collection<? extends io.grpc.ClientInterceptor> interceptors) {
            interceptors.forEach(this::interceptor);
            return this;
        }

        /**
         * Takes every service not configured explicitly on this builder, and the client token when
         * none is given, from the environment (see {@link ZequentClient#fromEnvironment()}); unset
         * variables fall back to the local development stack.
         */
        public ZequentClientBuilder fromEnvironment() {
            return fromEnvironment(System::getenv);
        }

        /** {@link #fromEnvironment()} against another source of variables (tests, a loaded .env). */
        public ZequentClientBuilder fromEnvironment(java.util.function.Function<String, String> environment) {
            this.environment = java.util.Objects.requireNonNull(environment, "environment");
            return this;
        }

        public ZequentClientBuilder defaultLoadBalancerType(ServiceConfig.LoadBalancerType type) {
            this.defaultLoadBalancerType = type;
            return this;
        }

        public ServiceConfigBuilder remoteControl() {
            this.remoteControlBuilder = new ServiceConfigBuilder(this, "remote-control");
            return this.remoteControlBuilder;
        }

        public ServiceConfigBuilder missionAutonomy() {
            this.missionAutonomyBuilder = new ServiceConfigBuilder(this, "mission-autonomy");
            return this.missionAutonomyBuilder;
        }

        public ServiceConfigBuilder liveData() {
            this.liveDataBuilder = new ServiceConfigBuilder(this, "live-data");
            return this.liveDataBuilder;
        }

        public ServiceConfigBuilder connector() {
            this.connectorBuilder = new ServiceConfigBuilder(this, "connector");
            return this.connectorBuilder;
        }

        public ZequentClient build() {
            // Build service configs with defaults
            ServiceConfig remoteControlConfig = buildServiceConfig(remoteControlBuilder, "remote-control", ZequentEnvironment.REMOTE_CONTROL);
            ServiceConfig missionAutonomyConfig = buildServiceConfig(missionAutonomyBuilder, "mission-autonomy", ZequentEnvironment.MISSION_AUTONOMY);
            ServiceConfig liveDataConfig = buildServiceConfig(liveDataBuilder, "live-data", ZequentEnvironment.LIVE_DATA);
            ServiceConfig connectorConfig = buildServiceConfig(connectorBuilder, "connector", ZequentEnvironment.CONNECTOR);

            // Build global config
            GrpcClientConfig globalConfig = GrpcClientConfig.builder()
                    .remoteControlConfig(remoteControlConfig)
                    .missionAutonomyConfig(missionAutonomyConfig)
                    .liveDataConfig(liveDataConfig)
                    .connectorConfig(connectorConfig)
                    .maxRetryAttempts(maxRetryAttempts)
                    .retryDelayMillis(retryDelayMillis)
                    .circuitBreakerFailureThreshold(circuitBreakerFailureThreshold)
                    .circuitBreakerWaitDurationMillis(circuitBreakerWaitDurationMillis)
                    .connectionTimeoutSeconds(connectionTimeoutSeconds)
                    .requestTimeoutSeconds(requestTimeoutSeconds)
                    .streamInactivityTimeoutSeconds(streamInactivityTimeoutSeconds)
                    .telemetryHeartbeatTimeoutSeconds(telemetryHeartbeatTimeoutSeconds)
                    .liveDataSchedulerThreads(liveDataSchedulerThreads)
                    .defaultLoadBalancerType(defaultLoadBalancerType)
                    .clientToken(ClientCredentials.resolve(clientToken != null && !clientToken.isBlank() || environment == null
                            ? clientToken : environment.apply(ClientCredentials.ENV_VAR)))
                    .interceptors(interceptors)
                    .build();
            if (!globalConfig.hasCredentialSource()) {
                log.warn("No client credential configured (ZQNT_CLIENT_TOKEN or builder().clientToken(...)): "
                        + "the platform will refuse every call");
            }

            // Create channels for each service
            List<ManagedChannel> channels = new ArrayList<>();
            ManagedChannel remoteControlChannel = ChannelFactory.createChannel(remoteControlConfig, globalConfig);
            ManagedChannel missionAutonomyChannel = ChannelFactory.createChannel(missionAutonomyConfig, globalConfig);
            ManagedChannel liveDataChannel = ChannelFactory.createChannel(liveDataConfig, globalConfig);
            ManagedChannel connectorChannel = ChannelFactory.createChannel(connectorConfig, globalConfig);
            channels.add(remoteControlChannel);
            channels.add(missionAutonomyChannel);
            channels.add(liveDataChannel);
            channels.add(connectorChannel);

            // Create service implementations with their own channels
            RemoteControl remoteControl = RemoteControlImpl.create(globalConfig, remoteControlChannel);
            MissionAutonomy missionAutonomy = MissionAutonomyImpl.create(globalConfig, missionAutonomyChannel);
            LiveData liveData = LiveDataImpl.create(globalConfig, liveDataChannel);
            Connector connector = ConnectorImpl.create(globalConfig, connectorChannel);

            return new ZequentClient(globalConfig, remoteControl, missionAutonomy, liveData, connector, channels);
        }

        private ServiceConfig buildServiceConfig(ServiceConfigBuilder builder, String serviceName, String envPrefix) {
            if (builder != null) {
                return builder.buildInternal();
            }
            if (environment != null) {
                return ZequentEnvironment.fromEnvironment(environment, envPrefix, serviceName, defaultLoadBalancerType);
            }
            // Not configured: the service on the local development stack
            return ZequentEnvironment.local(serviceName, defaultLoadBalancerType);
        }
    }

    /**
     * Builder for configuring individual services.
     */
    public static class ServiceConfigBuilder {
        private final ZequentClientBuilder parent;
        private final String serviceName;
        private String host = "localhost";
        private Integer port;
        private boolean usePlaintext = true;
        private boolean useStork = false;
        private String storkServiceName;
        private ServiceConfig.LoadBalancerType loadBalancerType;
        private int maxInboundMessageSize = -1;

        ServiceConfigBuilder(ZequentClientBuilder parent, String serviceName) {
            this.parent = parent;
            this.serviceName = serviceName;
        }

        public ServiceConfigBuilder host(String host) {
            this.host = host;
            return this;
        }

        public ServiceConfigBuilder port(int port) {
            this.port = port;
            return this;
        }

        public ServiceConfigBuilder usePlaintext(boolean usePlaintext) {
            this.usePlaintext = usePlaintext;
            return this;
        }

        public ServiceConfigBuilder useStork(boolean useStork) {
            this.useStork = useStork;
            return this;
        }

        public ServiceConfigBuilder storkServiceName(String storkServiceName) {
            this.storkServiceName = storkServiceName;
            this.useStork = true;
            return this;
        }

        public ServiceConfigBuilder loadBalancerType(ServiceConfig.LoadBalancerType loadBalancerType) {
            this.loadBalancerType = loadBalancerType;
            return this;
        }

        public ServiceConfigBuilder maxInboundMessageSize(int maxInboundMessageSize) {
            this.maxInboundMessageSize = maxInboundMessageSize;
            return this;
        }

        public ZequentClientBuilder done() {
            return parent;
        }

        ServiceConfig buildInternal() {
            return ServiceConfig.builder()
                    .serviceName(serviceName)
                    .host(host)
                    .port(port != null ? port : getDefaultPort())
                    .usePlaintext(usePlaintext)
                    .useStork(useStork)
                    .storkServiceName(storkServiceName != null ? storkServiceName : serviceName + "-service")
                    .loadBalancerType(loadBalancerType != null ? loadBalancerType : parent.defaultLoadBalancerType)
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .build();
        }

        private int getDefaultPort() {
            return ZequentEnvironment.LOCAL_PORTS.getOrDefault(serviceName, 9001);
        }
    }
}
