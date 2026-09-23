package com.zqnt.sdk.client.connector.application.impl;

import com.zqnt.sdk.client.connector.domains.*;

import java.util.List;
import java.util.Map;

final class ConnectorRequestValidator {

    private ConnectorRequestValidator() { }

    static DeregisterAssetRequest validate(DeregisterAssetRequest request) {
        requireSnContext(require(request, "request").getContext());
        return request;
    }

    static GetAssetBySnRequest validate(GetAssetBySnRequest request) {
        requireSnContext(require(request, "request").getContext());
        return request;
    }

    static GetAssetByIdRequest validate(GetAssetByIdRequest request) {
        require(request, "request");
        requireText(request.getAssetId(), "assetId");
        return request;
    }

    static GetSubAssetBySnRequest validate(GetSubAssetBySnRequest request) {
        requireSnContext(require(request, "request").getContext());
        return request;
    }

    static GetMissionRequest validate(GetMissionRequest request) {
        require(request, "request");
        requireText(request.getMissionId(), "missionId");
        return request;
    }

    static DeleteMissionRequest validate(DeleteMissionRequest request) {
        require(request, "request");
        requireText(request.getMissionId(), "missionId");
        return request;
    }

    static GetTaskRequest validate(GetTaskRequest request) {
        require(request, "request");
        requireText(request.getTaskId(), "taskId");
        return request;
    }

    static GetTaskByFlightIdRequest validate(GetTaskByFlightIdRequest request) {
        require(request, "request");
        requireText(request.getFlightId(), "flightId");
        return request;
    }

    static GetWaypointsByTaskIdRequest validate(GetWaypointsByTaskIdRequest request) {
        require(request, "request");
        requireText(request.getTaskId(), "taskId");
        return request;
    }

    static DeleteTaskRequest validate(DeleteTaskRequest request) {
        require(request, "request");
        requireText(request.getTaskId(), "taskId");
        return request;
    }

    static GetSchedulerRequest validate(GetSchedulerRequest request) {
        require(request, "request");
        requireText(request.getSchedulerId(), "schedulerId");
        return request;
    }

    static DeleteSchedulerRequest validate(DeleteSchedulerRequest request) {
        require(request, "request");
        requireText(request.getSchedulerId(), "schedulerId");
        return request;
    }

    static GetAllActivePoliciesRequest validate(GetAllActivePoliciesRequest request) {
        return require(request, "request");
    }

    static AssetMonitoringRequest validate(AssetMonitoringRequest request) {
        requireSnContext(require(request, "request").getContext());
        return request;
    }

    static RegisterAssetRequest validate(RegisterAssetRequest request) {
        require(request, "request");
        require(request.getAsset(), "asset");
        requireText(request.getAsset().getSn(), "asset.sn");
        return request;
    }

    static UpdateAssetRequest validate(UpdateAssetRequest request) {
        require(request, "request");
        requireText(request.getAssetId(), "assetId");
        require(request.getAsset(), "asset");
        validatePaths(request.getUpdateFields(), "updateFields");
        return request;
    }

    static UpdateSubAssetRequest validate(UpdateSubAssetRequest request) {
        require(request, "request");
        requireText(request.getSubAssetId(), "subAssetId");
        require(request.getSubAsset(), "subAsset");
        validatePaths(request.getUpdateFields(), "updateFields");
        return request;
    }

    static GetOrganizationRequest validate(GetOrganizationRequest request) {
        return require(request, "request");
    }

    static UpsertAssetPayloadRequest validate(UpsertAssetPayloadRequest request) {
        require(request, "request");
        require(request.getPayload(), "payload");
        if (request.getOwner() != null) validateOwner(request.getOwner());
        validatePaths(request.getUpdateFields(), "updateFields");
        return request;
    }

    static ListAssetPayloadsRequest validate(ListAssetPayloadsRequest request) {
        require(request, "request");
        if (request.getOwner() != null) {
            validateOwner(request.getOwner());
        } else if (request.getContext() == null
                || (isBlank(request.getContext().getAssetId()) && isBlank(request.getContext().getSn()))) {
            throw new IllegalArgumentException("owner or context.assetId/context.sn is required");
        }
        return request;
    }

    static DeleteAssetPayloadRequest validate(DeleteAssetPayloadRequest request) {
        require(request, "request");
        validateOwner(require(request.getOwner(), "owner"));
        requireText(request.getPayloadId(), "payloadId");
        return request;
    }

    static CreateMissionRequest validate(CreateMissionRequest request) {
        require(request, "request");
        require(request.getMission(), "mission");
        return request;
    }

    static UpdateMissionRequest validate(UpdateMissionRequest request) {
        require(request, "request");
        requireText(request.getMissionId(), "missionId");
        require(request.getMission(), "mission");
        return request;
    }

    static UploadMissionZonesRequest validate(UploadMissionZonesRequest request) {
        require(request, "request");
        requireText(request.getMissionId(), "missionId");
        noNullElements(request.getZones(), "zones");
        return request;
    }

    static CreateTaskRequest validate(CreateTaskRequest request) {
        require(request, "request");
        require(request.getTask(), "task");
        return request;
    }

    static UpdateTaskRequest validate(UpdateTaskRequest request) {
        require(request, "request");
        requireText(request.getTaskId(), "taskId");
        require(request.getTask(), "task");
        return request;
    }

    static CreateSchedulerRequest validate(CreateSchedulerRequest request) {
        require(request, "request");
        require(request.getScheduler(), "scheduler");
        return request;
    }

    static CreateSchedulersRequest validate(CreateSchedulersRequest request) {
        require(request, "request");
        requireNonEmpty(request.getSchedulers(), "schedulers");
        noNullElements(request.getSchedulers(), "schedulers");
        return request;
    }

    static UpdateSchedulerRequest validate(UpdateSchedulerRequest request) {
        require(request, "request");
        requireText(request.getSchedulerId(), "schedulerId");
        require(request.getScheduler(), "scheduler");
        return request;
    }

    static DeleteSchedulersRequest validate(DeleteSchedulersRequest request) {
        require(request, "request");
        requireNonEmpty(request.getSchedulerIds(), "schedulerIds");
        validatePaths(request.getSchedulerIds(), "schedulerIds");
        return request;
    }

    static GetPoliciesRequest validate(GetPoliciesRequest request) {
        require(request, "request");
        requireText(request.getPolicyType(), "policyType");
        return request;
    }

    static GetTechnicalConfigsRequest validate(GetTechnicalConfigsRequest request) {
        return require(request, "request");
    }

    static StoreTelemetryRequest validate(StoreTelemetryRequest request) {
        require(request, "request");
        require(request.getSourceType(), "sourceType");
        requireText(request.getAssetId(), "assetId");
        var telemetry = require(request.getTelemetry(), "telemetry");
        require(telemetry.getTimestamp(), "telemetry.timestamp");
        boolean hasAsset = telemetry.getAsset() != null;
        boolean hasSubAsset = telemetry.getSubAsset() != null;
        if (hasAsset == hasSubAsset) {
            throw invalid("telemetry must contain exactly one of asset or subAsset");
        }
        if (request.getSourceType() == StoreTelemetryRequest.SourceType.ASSET && !hasAsset) {
            throw invalid("sourceType ASSET requires telemetry.asset");
        }
        if (request.getSourceType() == StoreTelemetryRequest.SourceType.SUB_ASSET && !hasSubAsset) {
            throw invalid("sourceType SUB_ASSET requires telemetry.subAsset");
        }
        validateMap(request.getAdditionalData(), "additionalData");
        return request;
    }

    static StoreDetectionRequest validate(StoreDetectionRequest request) {
        require(request, "request");
        var detection = require(request.getDetection(), "detection");
        requireText(detection.getAssetSn(), "detection.assetSn");
        requireText(detection.getObjectId(), "detection.objectId");
        requireText(detection.getObjectType(), "detection.objectType");
        require(detection.getDetectedAt(), "detection.detectedAt");
        return request;
    }

    static StoreNotificationRequest validate(StoreNotificationRequest request) {
        require(request, "request");
        require(request.getEventType(), "eventType");
        require(request.getSeverity(), "severity");

        int eventCount = (request.getAssetStatus() != null ? 1 : 0)
                + (request.getCommandExecution() != null ? 1 : 0)
                + (request.getMission() != null ? 1 : 0);
        if (eventCount != 1) {
            throw invalid("exactly one of assetStatus, commandExecution or mission must be set");
        }

        switch (request.getEventType()) {
            case ASSET_STATUS -> validateAssetStatus(require(request.getAssetStatus(), "assetStatus"));
            case COMMAND_EXECUTION -> validateCommandExecution(require(request.getCommandExecution(), "commandExecution"));
            case MISSION -> validateMission(require(request.getMission(), "mission"));
        }
        return request;
    }

    private static void validateAssetStatus(StoreNotificationRequest.AssetStatusEvent event) {
        requireText(event.getSn(), "assetStatus.sn");
    }

    private static void validateCommandExecution(StoreNotificationRequest.CommandExecutionEvent event) {
        requireText(event.getExternalExecutionId(), "commandExecution.externalExecutionId");
        require(event.getStatus(), "commandExecution.status");
        requireText(event.getAssetSn(), "commandExecution.assetSn");
    }

    private static void validateMission(StoreNotificationRequest.MissionEvent event) {
        requireText(event.getMissionId(), "mission.missionId");
        require(event.getMissionType(), "mission.missionType");
        require(event.getStatus(), "mission.status");
    }

    private static void validateOwner(PayloadOwner owner) {
        require(owner.getType(), "owner.type");
        requireText(owner.getId(), "owner.id");
    }

    private static void requireSnContext(ConnectorRequestContext context) {
        require(context, "context");
        requireText(context.getSn(), "context.sn");
    }

    private static void validatePaths(List<String> values, String fieldName) {
        if (values == null) return;
        for (int index = 0; index < values.size(); index++) {
            requireText(values.get(index), fieldName + "[" + index + "]");
        }
    }

    private static void validateMap(Map<String, String> values, String fieldName) {
        if (values == null) return;
        values.forEach((key, value) -> {
            requireText(key, fieldName + " key");
            require(value, fieldName + "[" + key + "]");
        });
    }

    private static void noNullElements(List<?> values, String fieldName) {
        if (values == null) return;
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == null) throw invalid(fieldName + "[" + index + "] must not be null");
        }
    }

    private static void requireNonEmpty(List<?> values, String fieldName) {
        if (values == null || values.isEmpty()) throw invalid(fieldName + " must not be empty");
    }

    private static <T> T require(T value, String fieldName) {
        if (value == null) throw invalid(fieldName + " must not be null");
        return value;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) throw invalid(fieldName + " must not be null or blank");
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid Connector request: " + message);
    }
}
