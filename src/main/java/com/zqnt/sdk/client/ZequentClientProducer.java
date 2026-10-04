package com.zqnt.sdk.client;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ZequentClientConfigFactory;
import com.zqnt.sdk.client.connector.application.Connector;
import com.zqnt.sdk.client.connector.application.impl.ConnectorImpl;
import com.zqnt.sdk.client.grpc.ChannelFactory;
import com.zqnt.sdk.client.livedata.application.LiveData;
import com.zqnt.sdk.client.livedata.application.impl.LiveDataImpl;
import com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy;
import com.zqnt.sdk.client.missionautonomy.application.impl.MissionAutonomyImpl;
import com.zqnt.sdk.client.remotecontrol.application.RemoteControl;
import com.zqnt.sdk.client.remotecontrol.application.impl.RemoteControlImpl;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * CDI Producer for ZequentClient - the single entry point for customers.
 * This creates the ZequentClient bean with all services configured via properties.
 */
@Slf4j
@ApplicationScoped
public class ZequentClientProducer {


    private final ZequentClientConfigFactory configFactory;
    private final Instance<ClientInterceptor> interceptors;

    /**
     * @param interceptors every {@link ClientInterceptor} bean the application marked with
     *                     {@link ZequentClientInterceptor}; put on every channel of the client
     */
    @Inject
    public ZequentClientProducer(ZequentClientConfigFactory configFactory,
                                 @ZequentClientInterceptor Instance<ClientInterceptor> interceptors) {
        this.configFactory = configFactory;
        this.interceptors = interceptors;
    }

	/**
     * Produces the ZequentClient bean - the ONLY public API for customers.
     * All service implementations are internal and not exposed as separate beans.
     */
    @Produces
    @ApplicationScoped
    public ZequentClient produceZequentClient() {
        log.info("Creating ZequentClient from properties");

        // Create config from properties
        List<ClientInterceptor> contributed = new ArrayList<>();
        interceptors.forEach(contributed::add);
        GrpcClientConfig config = configFactory.createConfig().toBuilder().interceptors(contributed).build();

        // Create channels for each service
        List<ManagedChannel> channels = new ArrayList<>();
        if (!config.hasCredentialSource()) {
            log.warn("No client credential configured (zequent.client-token, ZQNT_CLIENT_TOKEN or a "
                    + "@ZequentClientInterceptor bean): the platform will refuse every call");
        } else if (!contributed.isEmpty()) {
            log.info("ZequentClient channels carry {} @ZequentClientInterceptor interceptor(s)", contributed.size());
        }
        ManagedChannel remoteControlChannel = ChannelFactory.createChannel(config.getRemoteControlConfig(), config);
        ManagedChannel missionAutonomyChannel = ChannelFactory.createChannel(config.getMissionAutonomyConfig(), config);
        ManagedChannel liveDataChannel = ChannelFactory.createChannel(config.getLiveDataConfig(), config);
        ManagedChannel connectorChannel = ChannelFactory.createChannel(config.getConnectorConfig(), config);
        channels.add(remoteControlChannel);
        channels.add(missionAutonomyChannel);
        channels.add(liveDataChannel);
        channels.add(connectorChannel);

        // Create service implementations (internal, not exposed as beans)
        RemoteControl remoteControl =RemoteControlImpl.create(config, remoteControlChannel);
        MissionAutonomy missionAutonomy = MissionAutonomyImpl.create(config, missionAutonomyChannel);
        LiveData liveData = LiveDataImpl.create(config, liveDataChannel);
        Connector connector = ConnectorImpl.create(config, connectorChannel);

        // Create and return ZequentClient
        return new ZequentClient(config, remoteControl, missionAutonomy, liveData, connector, channels);
    }

    public void disposeZequentClient(@Disposes ZequentClient client) {
        if (client != null) {
            client.close();
        }
    }
}
