package com.zqnt.sdk.client.connector.application;

import com.zqnt.sdk.client.connector.domains.*;
import com.zqnt.sdk.client.livedata.domains.StreamHandle;
import com.zqnt.sdk.client.missionautonomy.domains.SchedulerResponse;
import com.zqnt.utils.connector.proto.SkillContractProtoDTO;
import com.zqnt.utils.connector.proto.SkillContractStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Access to all endpoints exposed by the Connector gRPC service. */
public interface Connector {

    CompletableFuture<ConnectorResponse> registerAsset(RegisterAssetRequest request);
    CompletableFuture<ConnectorResponse> deregisterAsset(DeregisterAssetRequest request);
    CompletableFuture<ConnectorResponse> updateAsset(UpdateAssetRequest request);
    CompletableFuture<ConnectorResponse> updateSubAsset(UpdateSubAssetRequest request);
    CompletableFuture<ConnectorResponse> getAssetBySn(GetAssetBySnRequest request);
    CompletableFuture<ConnectorResponse> getAssetById(GetAssetByIdRequest request);
    CompletableFuture<ConnectorResponse> getSubAssetBySn(GetSubAssetBySnRequest request);

    CompletableFuture<AssetPayloadResponse> upsertAssetPayload(UpsertAssetPayloadRequest request);
    CompletableFuture<AssetPayloadListResponse> listAssetPayloads(ListAssetPayloadsRequest request);
    CompletableFuture<AssetPayloadResponse> deleteAssetPayload(DeleteAssetPayloadRequest request);

    CompletableFuture<ConnectorResponse> getOrganization(GetOrganizationRequest request);

    CompletableFuture<SchedulerResponse> getScheduler(GetSchedulerRequest request);
    CompletableFuture<SchedulerResponse> createScheduler(CreateSchedulerRequest request);
    CompletableFuture<SchedulerResponse> createSchedulers(CreateSchedulersRequest request);
    CompletableFuture<SchedulerResponse> updateScheduler(UpdateSchedulerRequest request);
    CompletableFuture<SchedulerResponse> deleteScheduler(DeleteSchedulerRequest request);
    CompletableFuture<SchedulerResponse> deleteSchedulers(DeleteSchedulersRequest request);

    CompletableFuture<ConnectorPolicyResponse> getActivePoliciesByType(GetPoliciesRequest request);
    CompletableFuture<ConnectorPolicyResponse> getAllActivePolicies(GetAllActivePoliciesRequest request);
    CompletableFuture<ConnectorConfigResponse> getTechnicalConfigs(GetTechnicalConfigsRequest request);

    // Skill Registry: the persisted, de-duplicated capability catalog (independent of which devices
    // are currently connected) — see connector's SkillContractRegistryService. Raw generated proto
    // types throughout, matching MissionAutonomy's convention for the newer capability-execution
    // surface, rather than this file's older hand-mapped Request/Response domain classes.

    /** Upserts {@code contract} — new for a never-seen (command_id, schema_version) pair, or
     * refreshed content/last-seen for one already known. Throws {@link ConnectorClientException} on
     * a server-reported error. */
    CompletableFuture<SkillContractProtoDTO> observeSkillContract(SkillContractProtoDTO contract);

    /** {@code commandId}, when set, returns that command's full version history instead of the
     * whole registry (status is then ignored, matching the RPC's own semantics). Either argument
     * may be null. */
    CompletableFuture<List<SkillContractProtoDTO>> listSkillContracts(SkillContractStatus status, String commandId);

    CompletableFuture<SkillContractProtoDTO> setSkillContractStatus(String id, SkillContractStatus status);

    /** Full replacement, not a merge. Declarative only — nothing currently enforces this (see
     * {@code SkillContractProtoDTO#required_permissions}). */
    CompletableFuture<SkillContractProtoDTO> setSkillContractPermissions(String id, List<String> requiredPermissions);

    StreamHandle assetMonitoring(AssetMonitoringRequest request,
                                 Consumer<AssetMonitoringResponse> onData,
                                 Consumer<Throwable> onError);

    ConnectorBatchSession<StoreTelemetryRequest> storeTelemetryBatch();
    ConnectorBatchSession<StoreDetectionRequest> storeDetectionBatch();
    ConnectorBatchSession<StoreNotificationRequest> storeNotificationBatch();
}
