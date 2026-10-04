package com.zqnt.sdk.client.connector.application.impl;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.connector.application.Connector;
import com.zqnt.sdk.client.connector.application.ConnectorClientException;
import com.zqnt.sdk.client.connector.domains.*;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import com.zqnt.sdk.client.livedata.domains.StreamHandle;
import com.zqnt.sdk.client.missionautonomy.domains.SchedulerResponse;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.connector.proto.ConnectorServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Framework-independent Connector gRPC client. Protobuf is contained entirely in this layer. */
@Slf4j
public final class ConnectorImpl implements Connector {

    private final ConnectorServiceGrpc.ConnectorServiceFutureStub futureStub;
    private final ConnectorServiceGrpc.ConnectorServiceStub asyncStub;
    private final ConnectorMapper mapper = new ConnectorMapper();
    private final GrpcResilience resilience;
    private final int requestTimeoutSeconds;
    private final ExecutorService callbackExecutor;

    private ConnectorImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.futureStub = ConnectorServiceGrpc.newFutureStub(channel);
        this.asyncStub = ConnectorServiceGrpc.newStub(channel);
        this.resilience = new GrpcResilience(config.getMaxRetryAttempts(), config.getRetryDelayMillis(),
                config.getCircuitBreakerFailureThreshold(), config.getCircuitBreakerWaitDurationMillis());
        this.requestTimeoutSeconds = config.getRequestTimeoutSeconds();
        this.callbackExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "connector-callback");
            thread.setDaemon(true);
            return thread;
        });
        log.debug("Connector created with channel for {}:{}",
                config.getConnectorConfig().getHost(), config.getConnectorConfig().getPort());
    }

    public static ConnectorImpl create(GrpcClientConfig config, ManagedChannel channel) {
        return new ConnectorImpl(config, channel);
    }

    @Override public CompletableFuture<ConnectorResponse> registerAsset(RegisterAssetRequest request) {
        return execute(() -> futureStub.registerAsset(mapper.registerAsset(ConnectorRequestValidator.validate(request)))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> deregisterAsset(DeregisterAssetRequest request) {
        return execute(() -> futureStub.deregisterAsset(mapper.base(ConnectorRequestValidator.validate(request).getContext()))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> updateAsset(UpdateAssetRequest request) {
        return execute(() -> futureStub.updateAsset(mapper.updateAsset(ConnectorRequestValidator.validate(request)))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> updateSubAsset(UpdateSubAssetRequest request) {
        return execute(() -> futureStub.updateSubAsset(mapper.updateSubAsset(ConnectorRequestValidator.validate(request)))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> getAssetBySn(GetAssetBySnRequest request) {
        return execute(() -> futureStub.getAssetBySn(mapper.base(ConnectorRequestValidator.validate(request).getContext()))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> getAssetById(GetAssetByIdRequest request) {
        return execute(() -> futureStub.getAssetById(mapper.getAssetById(ConnectorRequestValidator.validate(request)))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> getSubAssetBySn(GetSubAssetBySnRequest request) {
        return execute(() -> futureStub.getSubAssetBySn(mapper.base(ConnectorRequestValidator.validate(request).getContext()))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<AssetPayloadResponse> upsertAssetPayload(UpsertAssetPayloadRequest request) {
        return execute(() -> futureStub.upsertAssetPayload(mapper.upsertPayload(ConnectorRequestValidator.validate(request)))).thenApply(mapper::payloadResponse);
    }
    @Override public CompletableFuture<AssetPayloadListResponse> listAssetPayloads(ListAssetPayloadsRequest request) {
        return execute(() -> futureStub.listAssetPayloads(mapper.listPayloads(ConnectorRequestValidator.validate(request)))).thenApply(mapper::payloadListResponse);
    }
    @Override public CompletableFuture<AssetPayloadResponse> deleteAssetPayload(DeleteAssetPayloadRequest request) {
        return execute(() -> futureStub.deleteAssetPayload(mapper.deletePayload(ConnectorRequestValidator.validate(request)))).thenApply(mapper::payloadResponse);
    }
    @Override public CompletableFuture<ConnectorResponse> getOrganization(GetOrganizationRequest request) {
        return execute(() -> futureStub.getOrganization(mapper.organization(ConnectorRequestValidator.validate(request)))).thenApply(mapper::connectorResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> getScheduler(GetSchedulerRequest request) {
        return execute(() -> futureStub.getScheduler(mapper.getScheduler(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> createScheduler(CreateSchedulerRequest request) {
        return execute(() -> futureStub.createScheduler(mapper.createScheduler(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> createSchedulers(CreateSchedulersRequest request) {
        return execute(() -> futureStub.createSchedulers(mapper.createSchedulers(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> updateScheduler(UpdateSchedulerRequest request) {
        return execute(() -> futureStub.updateScheduler(mapper.updateScheduler(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> deleteScheduler(DeleteSchedulerRequest request) {
        return execute(() -> futureStub.deleteScheduler(mapper.deleteScheduler(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<SchedulerResponse> deleteSchedulers(DeleteSchedulersRequest request) {
        return execute(() -> futureStub.deleteSchedulers(mapper.deleteSchedulers(ConnectorRequestValidator.validate(request)))).thenApply(mapper::schedulerResponse);
    }
    @Override public CompletableFuture<ConnectorPolicyResponse> getActivePoliciesByType(GetPoliciesRequest request) {
        return execute(() -> futureStub.getActivePoliciesByType(mapper.policies(ConnectorRequestValidator.validate(request)))).thenApply(mapper::policyResponse);
    }
    @Override public CompletableFuture<ConnectorPolicyResponse> getAllActivePolicies(GetAllActivePoliciesRequest request) {
        return execute(() -> futureStub.getAllActivePolicies(mapper.allPolicies(ConnectorRequestValidator.validate(request)))).thenApply(mapper::policyResponse);
    }
    @Override public CompletableFuture<ConnectorConfigResponse> getTechnicalConfigs(GetTechnicalConfigsRequest request) {
        return execute(() -> futureStub.getTechnicalConfigs(mapper.configs(ConnectorRequestValidator.validate(request)))).thenApply(mapper::configResponse);
    }

    @Override public CompletableFuture<com.zqnt.utils.connector.proto.SkillContractProtoDTO> observeSkillContract(
            com.zqnt.utils.connector.proto.SkillContractProtoDTO contract) {
        require(contract);
        var protoRequest = com.zqnt.utils.connector.proto.UpsertSkillContractRequest.newBuilder()
                .setBase(buildBase()).setContract(contract).build();
        return execute(() -> futureStub.observeSkillContract(protoRequest)).thenApply(this::requireContract);
    }

    @Override public CompletableFuture<java.util.List<com.zqnt.utils.connector.proto.SkillContractProtoDTO>> listSkillContracts(
            com.zqnt.utils.connector.proto.SkillContractStatus status, String commandId) {
        var protoRequest = com.zqnt.utils.connector.proto.ListSkillContractsRequest.newBuilder().setBase(buildBase());
        if (status != null) protoRequest.setStatus(status);
        if (commandId != null && !commandId.isBlank()) protoRequest.setCommandId(commandId);
        return execute(() -> futureStub.listSkillContracts(protoRequest.build())).thenApply(response -> {
            requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                    response.getTid());
            return response.getContractsList();
        });
    }

    @Override public CompletableFuture<com.zqnt.utils.connector.proto.SkillContractProtoDTO> setSkillContractStatus(
            String id, com.zqnt.utils.connector.proto.SkillContractStatus status) {
        var protoRequest = com.zqnt.utils.connector.proto.SetSkillContractStatusRequest.newBuilder().setBase(buildBase())
                .setId(requireText(id, "id")).setStatus(status).build();
        return execute(() -> futureStub.setSkillContractStatus(protoRequest)).thenApply(this::requireContract);
    }

    @Override public CompletableFuture<com.zqnt.utils.connector.proto.SkillContractProtoDTO> setSkillContractPermissions(
            String id, java.util.List<String> requiredPermissions) {
        var protoRequest = com.zqnt.utils.connector.proto.SetSkillContractPermissionsRequest.newBuilder()
                .setBase(buildBase()).setId(requireText(id, "id"))
                .addAllRequiredPermissions(requiredPermissions == null ? java.util.List.of() : requiredPermissions)
                .build();
        return execute(() -> futureStub.setSkillContractPermissions(protoRequest)).thenApply(this::requireContract);
    }

    @Override
    public StreamHandle assetMonitoring(AssetMonitoringRequest request, Consumer<AssetMonitoringResponse> onData,
                                        Consumer<Throwable> onError) {
        Objects.requireNonNull(onData, "onData must not be null");
        Objects.requireNonNull(onError, "onError must not be null");
        RequestBase protoRequest = mapper.base(ConnectorRequestValidator.validate(request).getContext());
        StreamHandle handle = new StreamHandle();
        asyncStub.assetMonitoring(protoRequest,
                new ClientResponseObserver<RequestBase, com.zqnt.utils.connector.proto.AssetMonitoringResponse>() {
            @Override public void beforeStart(ClientCallStreamObserver<RequestBase> call) { handle.bindActiveCall(call); }
            @Override public void onNext(com.zqnt.utils.connector.proto.AssetMonitoringResponse value) {
                if (!handle.isStopped()) {
                    try { onData.accept(mapper.monitoring(value)); }
                    catch (RuntimeException error) { handle.stop(); onError.accept(error); }
                }
            }
            @Override public void onError(Throwable error) {
                handle.cancelActiveCall("asset monitoring failed");
                if (!handle.isStopped()) onError.accept(error);
            }
            @Override public void onCompleted() { handle.cancelActiveCall("asset monitoring completed"); }
        });
        return handle;
    }

    @Override public ConnectorBatchSession<StoreTelemetryRequest> storeTelemetryBatch() {
        return openBatch(asyncStub::storeTelemetryBatch,
                request -> mapper.telemetry(ConnectorRequestValidator.validate(request)));
    }
    @Override public ConnectorBatchSession<StoreDetectionRequest> storeDetectionBatch() {
        return openBatch(asyncStub::storeDetectionBatch,
                request -> mapper.detection(ConnectorRequestValidator.validate(request)));
    }
    @Override public ConnectorBatchSession<StoreNotificationRequest> storeNotificationBatch() {
        return openBatch(asyncStub::storeNotificationBatch,
                request -> mapper.notification(ConnectorRequestValidator.validate(request)));
    }

    private <D, P> ConnectorBatchSession<D> openBatch(
            Function<StreamObserver<com.zqnt.utils.connector.proto.ConnectorResponse>, StreamObserver<P>> opener,
            Function<D, P> requestMapper) {
        CompletableFuture<ConnectorResponse> response = new CompletableFuture<>();
        StreamObserver<P> requests = opener.apply(new StreamObserver<>() {
            private com.zqnt.utils.connector.proto.ConnectorResponse lastResponse;
            @Override public void onNext(com.zqnt.utils.connector.proto.ConnectorResponse value) { lastResponse = value; }
            @Override public void onError(Throwable error) { response.completeExceptionally(error); }
            @Override public void onCompleted() {
                if (lastResponse != null) response.complete(mapper.connectorResponse(lastResponse));
                else response.completeExceptionally(new IllegalStateException("Connector batch completed without a response"));
            }
        });
        return new ConnectorBatchSessionImpl<>(requests, requestMapper, response, requestTimeoutSeconds);
    }

    private <T> CompletableFuture<T> execute(Supplier<ListenableFuture<T>> call) {
        return resilience.executeWithResilienceAsync(() -> {
            CompletableFuture<T> result = new CompletableFuture<>();
            Futures.addCallback(call.get(), new FutureCallback<>() {
                @Override public void onSuccess(T value) { result.complete(value); }
                @Override public void onFailure(Throwable error) { result.completeExceptionally(error); }
            }, callbackExecutor);
            return result.orTimeout(requestTimeoutSeconds, TimeUnit.SECONDS);
        });
    }

    private static <T> T require(T request) { return Objects.requireNonNull(request, "request must not be null"); }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private RequestBase buildBase() {
        return RequestBase.newBuilder().setTid(java.util.UUID.randomUUID().toString())
                .setTimestamp(com.zqnt.utils.core.ProtobufHelpers.now()).build();
    }

    /** Throw-on-error unwrapping for the Skill Registry's raw-proto responses — mirrors
     * MissionAutonomyImpl's convention for its own raw-proto capability-execution surface, unlike
     * this class's older hand-mapped endpoints, which surface errors via {@code ConnectorResponse}
     * fields instead of throwing. */
    private com.zqnt.utils.connector.proto.SkillContractProtoDTO requireContract(
            com.zqnt.utils.connector.proto.SkillContractResponse response) {
        requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                response.getTid());
        if (!response.hasContract()) {
            throw new ConnectorClientException("MALFORMED_RESPONSE", "Missing skill contract", response.getTid());
        }
        return response.getContract();
    }

    private void requireSuccess(boolean hasErrors, com.zqnt.utils.common.proto.GlobalErrorMessage error,
            String transactionId) {
        if (!hasErrors && error == null) return;
        throw new ConnectorClientException(error == null ? "" : error.getErrorCode().name(),
                error == null ? "Connector operation failed" : error.getErrorMessage(), transactionId);
    }

    public void shutdown() { callbackExecutor.shutdown(); }
}
