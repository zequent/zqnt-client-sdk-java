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

import io.grpc.ManagedChannel;
import jakarta.enterprise.context.ApplicationScoped;
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

	public ZequentClientProducer(ZequentClientConfigFactory configFactory) {
		this.configFactory = configFactory;
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
        GrpcClientConfig config = configFactory.createConfig();

        // Create channels for each service
        List<ManagedChannel> channels = new ArrayList<>();
        String token = config.getClientToken();
        if (token == null) {
            log.warn("No client credential configured (zequent.client-token or ZQNT_CLIENT_TOKEN): "
                    + "the platform will refuse every call");
        }
        ManagedChannel remoteControlChannel = ChannelFactory.createChannel(config.getRemoteControlConfig(), token);
        ManagedChannel missionAutonomyChannel = ChannelFactory.createChannel(config.getMissionAutonomyConfig(), token);
        ManagedChannel liveDataChannel = ChannelFactory.createChannel(config.getLiveDataConfig(), token);
        ManagedChannel connectorChannel = ChannelFactory.createChannel(config.getConnectorConfig(), token);
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
