package com.zqnt.sdk.client.remotecontrol.application.impl;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.remotecontrol.application.RemoteControl;
import com.zqnt.sdk.client.remotecontrol.domains.GoToRequest;
import com.zqnt.utils.devicecontrol.proto.CommandResponse;
import com.zqnt.utils.devicecontrol.proto.CoordinateCommandRequest;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A fly-to sends the no-fly zone override only when asked for; the platform decides who may use it. */
class RemoteControlGoToTest {

    private final AtomicReference<CoordinateCommandRequest> lastGoTo = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private RemoteControl remoteControl;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new RemoteControlServiceGrpc.RemoteControlServiceImplBase() {
                    @Override
                    public void goTo(CoordinateCommandRequest request, StreamObserver<CommandResponse> observer) {
                        lastGoTo.set(request);
                        observer.onNext(CommandResponse.newBuilder().build());
                        observer.onCompleted();
                    }
                })
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        remoteControl = RemoteControlImpl.create(GrpcClientConfig.builder()
                .remoteControlConfig(ServiceConfig.builder().serviceName("remote-control").host("in-process").port(0).build())
                .requestTimeoutSeconds(5)
                .build(), channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static GoToRequest request() {
        return GoToRequest.builder().sn("drone-1").latitude(52.5f).longitude(13.4f).altitude(30).build();
    }

    @Test
    void aPlainGoToSendsNoOverride() {
        remoteControl.goTo(request()).join();

        assertFalse(lastGoTo.get().hasNoFlyZoneOverride());
        assertEquals(52.5, lastGoTo.get().getCoordinate().getLatitude(), 1e-4);
    }

    @Test
    void anOverrideIsSentWhenAskedFor() {
        remoteControl.goTo(request(), true).join();

        assertTrue(lastGoTo.get().hasNoFlyZoneOverride());
        assertTrue(lastGoTo.get().getNoFlyZoneOverride());
    }

    @Test
    void falseIsThePlainGoTo() {
        remoteControl.goTo(request(), false).join();

        assertFalse(lastGoTo.get().hasNoFlyZoneOverride());
    }
}
