package com.zqnt.sdk.client.connector.application.impl;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.connector.application.Connector;
import com.zqnt.sdk.client.connector.application.ConnectorClientException;
import com.zqnt.utils.common.proto.ErrorCode;
import com.zqnt.utils.common.proto.GlobalErrorMessage;
import com.zqnt.utils.connector.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** In-process gRPC coverage for the Skill Registry methods added to {@link ConnectorImpl} (spec
 * §32 — propagating the capability/skill protocol into the client SDK). */
class ConnectorImplSkillRegistryTest {

    private Server server;
    private ManagedChannel channel;
    private Connector connector;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (connector instanceof ConnectorImpl impl) impl.shutdown();
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void observesASkillContractAndReturnsTheStoredResult() throws Exception {
        var stored = SkillContractProtoDTO.newBuilder().setId("row-1").setCommandId("acme.custom_scan").build();
        start(new FakeConnectorService() {
            @Override public void observeSkillContract(UpsertSkillContractRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals("acme.custom_scan", request.getContract().getCommandId());
                respond(observer, SkillContractResponse.newBuilder().setContract(stored).build());
            }
        });

        var result = connector.observeSkillContract(SkillContractProtoDTO.newBuilder()
                .setCommandId("acme.custom_scan").build()).get(5, TimeUnit.SECONDS);

        assertEquals("row-1", result.getId());
    }

    @Test
    void observeSkillContractThrowsOnAServerReportedError() throws Exception {
        start(new FakeConnectorService() {
            @Override public void observeSkillContract(UpsertSkillContractRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                respond(observer, SkillContractResponse.newBuilder().setHasErrors(true)
                        .setError(GlobalErrorMessage.newBuilder().setErrorCode(ErrorCode.ERROR_CODE_CLIENT)
                                .setErrorMessage("commandId already owned")).build());
            }
        });

        var future = connector.observeSkillContract(SkillContractProtoDTO.newBuilder()
                .setCommandId("flight.takeoff").build());
        var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        var cause = assertThrows(ConnectorClientException.class, () -> { throw failure.getCause(); });
        assertTrue(cause.getMessage().contains("commandId already owned"));
    }

    @Test
    void listsSkillContractsFilteredByCommandId() throws Exception {
        start(new FakeConnectorService() {
            @Override public void listSkillContracts(ListSkillContractsRequest request,
                    StreamObserver<SkillContractListResponse> observer) {
                assertEquals("flight.takeoff", request.getCommandId());
                respond(observer, SkillContractListResponse.newBuilder()
                        .addContracts(SkillContractProtoDTO.newBuilder().setCommandId("flight.takeoff")).build());
            }
        });

        var result = connector.listSkillContracts(null, "flight.takeoff").get(5, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals("flight.takeoff", result.get(0).getCommandId());
    }

    @Test
    void setsSkillContractStatus() throws Exception {
        start(new FakeConnectorService() {
            @Override public void setSkillContractStatus(SetSkillContractStatusRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals("row-1", request.getId());
                assertEquals(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED, request.getStatus());
                respond(observer, SkillContractResponse.newBuilder()
                        .setContract(SkillContractProtoDTO.newBuilder().setId("row-1")
                                .setStatus(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED)).build());
            }
        });

        var result = connector.setSkillContractStatus("row-1", SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED)
                .get(5, TimeUnit.SECONDS);

        assertEquals(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED, result.getStatus());
    }

    @Test
    void setsSkillContractPermissionsAsAFullReplacement() throws Exception {
        start(new FakeConnectorService() {
            @Override public void setSkillContractPermissions(SetSkillContractPermissionsRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals(List.of("mission.launch", "role:pilot"), request.getRequiredPermissionsList());
                respond(observer, SkillContractResponse.newBuilder()
                        .setContract(SkillContractProtoDTO.newBuilder().setId("row-1")
                                .addAllRequiredPermissions(request.getRequiredPermissionsList())).build());
            }
        });

        var result = connector.setSkillContractPermissions("row-1", List.of("mission.launch", "role:pilot"))
                .get(5, TimeUnit.SECONDS);

        assertEquals(List.of("mission.launch", "role:pilot"), result.getRequiredPermissionsList());
    }

    private void start(FakeConnectorService implementation) throws IOException {
        server = ServerBuilder.forPort(0).addService(implementation).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        var config = GrpcClientConfig.builder().connectorConfig(ServiceConfig.builder().build()).build();
        connector = ConnectorImpl.create(config, channel);
    }

    private static <T> void respond(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    private static class FakeConnectorService extends ConnectorServiceGrpc.ConnectorServiceImplBase {
    }
}
