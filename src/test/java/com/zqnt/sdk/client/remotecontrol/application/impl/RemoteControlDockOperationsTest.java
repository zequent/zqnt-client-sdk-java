package com.zqnt.sdk.client.remotecontrol.application.impl;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.remotecontrol.application.RemoteControl;
import com.zqnt.sdk.client.remotecontrol.domains.AirConditionerMode;
import com.zqnt.sdk.client.remotecontrol.domains.DockOperationRequest;
import com.zqnt.utils.common.proto.AssetAirConditionerStateEnum;
import com.zqnt.utils.devicecontrol.proto.ChangeAcModeCommandRequest;
import com.zqnt.utils.devicecontrol.proto.CommandResponse;
import com.zqnt.utils.devicecontrol.proto.ToggleCommandRequest;
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

class RemoteControlDockOperationsTest {

    private final AtomicReference<ToggleCommandRequest> lastBoot = new AtomicReference<>();
    private final AtomicReference<ChangeAcModeCommandRequest> lastAc = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;
    private RemoteControl remoteControl;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new RemoteControlServiceGrpc.RemoteControlServiceImplBase() {
                    @Override
                    public void bootSubAsset(ToggleCommandRequest request, StreamObserver<CommandResponse> observer) {
                        lastBoot.set(request);
                        observer.onNext(CommandResponse.newBuilder().build());
                        observer.onCompleted();
                    }

                    @Override
                    public void changeAcMode(ChangeAcModeCommandRequest request, StreamObserver<CommandResponse> observer) {
                        lastAc.set(request);
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

    @Test
    void bootSubAssetWithoutValueBootsUp() {
        remoteControl.bootSubAsset(DockOperationRequest.builder().sn("dock-1").build()).join();

        assertTrue(lastBoot.get().getEnabled(), "no value must mean boot up, not power down");
    }

    @Test
    void bootSubAssetFalsePowersDown() {
        remoteControl.bootSubAsset(DockOperationRequest.builder().sn("dock-1").value(false).build()).join();

        assertFalse(lastBoot.get().getEnabled());
    }

    @Test
    void changeAcModeSendsTheRequestedMode() {
        remoteControl.changeAcMode(DockOperationRequest.builder()
                .sn("dock-1").acMode(AirConditionerMode.HEAT).build()).join();

        assertEquals(AssetAirConditionerStateEnum.AIR_CONDITIONER_HEAT, lastAc.get().getMode());
    }

    @Test
    void changeAcModeWithoutModeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> remoteControl.changeAcMode(
                DockOperationRequest.builder().sn("dock-1").build()));
        assertNull(lastAc.get());
    }
}
