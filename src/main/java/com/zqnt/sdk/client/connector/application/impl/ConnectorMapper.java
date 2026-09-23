package com.zqnt.sdk.client.connector.application.impl;

import com.google.protobuf.FieldMask;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.zqnt.sdk.client.connector.domains.*;
import com.zqnt.sdk.client.missionautonomy.application.impl.MissionAutonomyImpl;
import com.zqnt.sdk.client.missionautonomy.domains.MissionResponse;
import com.zqnt.sdk.client.missionautonomy.domains.SchedulerResponse;
import com.zqnt.sdk.client.missionautonomy.domains.TaskResponse;
import com.zqnt.utils.JsonUtils;
import com.zqnt.utils.asset.domains.AssetDTO;
import com.zqnt.utils.asset.domains.AssetPayloadDTO;
import com.zqnt.utils.asset.domains.SubAssetDTO;
import com.zqnt.utils.common.proto.*;
import com.zqnt.utils.connector.proto.*;
import com.zqnt.utils.core.ProtoJsonUtils;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.edge.sdk.domains.TelemetryData;
import com.zqnt.utils.events.proto.*;
import com.zqnt.utils.mission.proto.*;
import com.zqnt.utils.missionautonomy.domains.MissionDTO;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;
import com.zqnt.utils.missionautonomy.domains.WaypointDTO;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ConnectorMapper {

    RequestBase base(ConnectorRequestContext context) {
        ConnectorRequestContext value = context != null ? context : new ConnectorRequestContext();
        RequestBase.Builder builder = RequestBase.newBuilder()
                .setTid(value.getTid() != null && !value.getTid().isBlank()
                        ? value.getTid() : UUID.randomUUID().toString())
                .setSn(value.getSn() != null ? value.getSn() : "")
                .setTimestamp(ProtobufHelpers.now());
        set(builder::setAssetId, value.getAssetId());
        set(builder::setExternalId, value.getExternalId());
        set(builder::setClientId, value.getClientId());
        set(builder::setUserId, value.getUserId());
        return builder.build();
    }

    ConnectorRegisterAssetRequest registerAsset(RegisterAssetRequest request) {
        return ConnectorRegisterAssetRequest.newBuilder().setBase(base(request.getContext()))
                .setAsset(asset(request.getAsset())).build();
    }

    ConnectorUpdateAssetRequest updateAsset(UpdateAssetRequest request) {
        return ConnectorUpdateAssetRequest.newBuilder().setBase(base(request.getContext()))
                .setAssetId(request.getAssetId()).setAsset(asset(request.getAsset()))
                .setUpdateMask(mask(request.getUpdateFields())).build();
    }

    ConnectorUpdateSubAssetRequest updateSubAsset(UpdateSubAssetRequest request) {
        return ConnectorUpdateSubAssetRequest.newBuilder().setBase(base(request.getContext()))
                .setSubAssetId(request.getSubAssetId()).setSubAsset(subAsset(request.getSubAsset()))
                .setUpdateMask(mask(request.getUpdateFields())).build();
    }

    ConnectorGetAssetByIdRequest getAssetById(GetAssetByIdRequest request) {
        return ConnectorGetAssetByIdRequest.newBuilder().setBase(base(request.getContext()))
                .setAssetId(request.getAssetId()).build();
    }

    ConnectorGetOrganizationRequest organization(GetOrganizationRequest request) {
        var builder = ConnectorGetOrganizationRequest.newBuilder().setBase(base(request.getContext()));
        set(builder::setBindCode, request.getBindCode());
        return builder.build();
    }

    com.zqnt.utils.connector.proto.UpsertAssetPayloadRequest upsertPayload(
            com.zqnt.sdk.client.connector.domains.UpsertAssetPayloadRequest request) {
        var builder = com.zqnt.utils.connector.proto.UpsertAssetPayloadRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setPayload(payload(request.getPayload())).setUpdateMask(mask(request.getUpdateFields()));
        set(builder::setSubAssetSn, request.getSubAssetSn());
        if (request.getOwner() != null) builder.setOwner(owner(request.getOwner()));
        return builder.build();
    }

    com.zqnt.utils.connector.proto.ListAssetPayloadsRequest listPayloads(
            com.zqnt.sdk.client.connector.domains.ListAssetPayloadsRequest request) {
        var builder = com.zqnt.utils.connector.proto.ListAssetPayloadsRequest.newBuilder()
                .setBase(base(request.getContext()));
        if (request.getOwner() != null) builder.setOwner(owner(request.getOwner()));
        return builder.build();
    }

    com.zqnt.utils.connector.proto.DeleteAssetPayloadRequest deletePayload(
            com.zqnt.sdk.client.connector.domains.DeleteAssetPayloadRequest request) {
        return com.zqnt.utils.connector.proto.DeleteAssetPayloadRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setOwner(owner(request.getOwner())).setPayloadId(request.getPayloadId()).build();
    }

    private AssetPayloadOwner owner(PayloadOwner owner) {
        var builder = AssetPayloadOwner.newBuilder();
        if (owner.getType() == PayloadOwner.Type.ASSET) builder.setAssetId(owner.getId());
        else builder.setSubAssetId(owner.getId());
        return builder.build();
    }

    com.zqnt.utils.mission.proto.GetMissionRequest getMission(
            com.zqnt.sdk.client.connector.domains.GetMissionRequest request) {
        return com.zqnt.utils.mission.proto.GetMissionRequest.newBuilder()
                .setBase(base(request.getContext())).setMissionId(request.getMissionId()).build();
    }

    com.zqnt.utils.mission.proto.CreateMissionRequest createMission(
            com.zqnt.sdk.client.connector.domains.CreateMissionRequest request) {
        return com.zqnt.utils.mission.proto.CreateMissionRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setMission(MissionAutonomyImpl.mapMissionDtoToProto(MissionProtoDTO.newBuilder(), request.getMission()))
                .build();
    }

    com.zqnt.utils.mission.proto.UpdateMissionRequest updateMission(
            com.zqnt.sdk.client.connector.domains.UpdateMissionRequest request) {
        return com.zqnt.utils.mission.proto.UpdateMissionRequest.newBuilder()
                .setBase(base(request.getContext())).setMissionId(request.getMissionId())
                .setMission(MissionAutonomyImpl.mapMissionDtoToProto(MissionProtoDTO.newBuilder(), request.getMission()))
                .build();
    }

    com.zqnt.utils.mission.proto.DeleteMissionRequest deleteMission(
            com.zqnt.sdk.client.connector.domains.DeleteMissionRequest request) {
        return com.zqnt.utils.mission.proto.DeleteMissionRequest.newBuilder()
                .setBase(base(request.getContext())).setMissionId(request.getMissionId()).build();
    }

    UploadMissionNfzZonesRequest uploadZones(UploadMissionZonesRequest request) {
        var builder = UploadMissionNfzZonesRequest.newBuilder().setBase(base(request.getContext()))
                .setMissionId(request.getMissionId()).setReplaceExisting(request.isReplaceExisting());
        if (request.getZones() != null) request.getZones().stream()
                .map(MissionAutonomyImpl::mapMissionZoneDtoToProto)
                .forEach(builder::addZones);
        return builder.build();
    }

    com.zqnt.utils.mission.proto.GetTaskRequest getTask(
            com.zqnt.sdk.client.connector.domains.GetTaskRequest request) {
        return com.zqnt.utils.mission.proto.GetTaskRequest.newBuilder()
                .setBase(base(request.getContext())).setTaskId(request.getTaskId()).build();
    }

    com.zqnt.utils.mission.proto.GetTaskByFlightIdRequest getTaskByFlightId(
            com.zqnt.sdk.client.connector.domains.GetTaskByFlightIdRequest request) {
        return com.zqnt.utils.mission.proto.GetTaskByFlightIdRequest.newBuilder()
                .setBase(base(request.getContext())).setFlightId(request.getFlightId()).build();
    }

    com.zqnt.utils.mission.proto.GetWaypointsByTaskIdRequest getWaypoints(
            com.zqnt.sdk.client.connector.domains.GetWaypointsByTaskIdRequest request) {
        return com.zqnt.utils.mission.proto.GetWaypointsByTaskIdRequest.newBuilder()
                .setBase(base(request.getContext())).setTaskId(request.getTaskId()).build();
    }

    com.zqnt.utils.mission.proto.CreateTaskRequest createTask(
            com.zqnt.sdk.client.connector.domains.CreateTaskRequest request) {
        return com.zqnt.utils.mission.proto.CreateTaskRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setTask(MissionAutonomyImpl.mapTaskDtoToProto(TaskProtoDTO.newBuilder(), request.getTask())).build();
    }

    com.zqnt.utils.mission.proto.UpdateTaskRequest updateTask(
            com.zqnt.sdk.client.connector.domains.UpdateTaskRequest request) {
        return com.zqnt.utils.mission.proto.UpdateTaskRequest.newBuilder()
                .setBase(base(request.getContext())).setTaskId(request.getTaskId())
                .setTask(MissionAutonomyImpl.mapTaskDtoToProto(TaskProtoDTO.newBuilder(), request.getTask())).build();
    }

    com.zqnt.utils.mission.proto.DeleteTaskRequest deleteTask(
            com.zqnt.sdk.client.connector.domains.DeleteTaskRequest request) {
        return com.zqnt.utils.mission.proto.DeleteTaskRequest.newBuilder()
                .setBase(base(request.getContext())).setTaskId(request.getTaskId()).build();
    }

    com.zqnt.utils.mission.proto.GetSchedulerRequest getScheduler(
            com.zqnt.sdk.client.connector.domains.GetSchedulerRequest request) {
        return com.zqnt.utils.mission.proto.GetSchedulerRequest.newBuilder()
                .setBase(base(request.getContext())).setSchedulerId(request.getSchedulerId()).build();
    }

    com.zqnt.utils.mission.proto.CreateSchedulerRequest createScheduler(
            com.zqnt.sdk.client.connector.domains.CreateSchedulerRequest request) {
        return com.zqnt.utils.mission.proto.CreateSchedulerRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setScheduler(MissionAutonomyImpl.mapSchedulerDtoToProto(
                        SchedulerProtoDTO.newBuilder(), request.getScheduler())).build();
    }

    com.zqnt.utils.mission.proto.CreateSchedulersRequest createSchedulers(
            com.zqnt.sdk.client.connector.domains.CreateSchedulersRequest request) {
        var builder = com.zqnt.utils.mission.proto.CreateSchedulersRequest.newBuilder()
                .setBase(base(request.getContext()));
        if (request.getSchedulers() != null) request.getSchedulers().stream()
                .map(value -> MissionAutonomyImpl.mapSchedulerDtoToProto(SchedulerProtoDTO.newBuilder(), value).build())
                .forEach(builder::addSchedulers);
        return builder.build();
    }

    com.zqnt.utils.mission.proto.UpdateSchedulerRequest updateScheduler(
            com.zqnt.sdk.client.connector.domains.UpdateSchedulerRequest request) {
        return com.zqnt.utils.mission.proto.UpdateSchedulerRequest.newBuilder()
                .setBase(base(request.getContext()))
                .setSchedulerId(request.getSchedulerId()).setScheduler(MissionAutonomyImpl.mapSchedulerDtoToProto(
                        SchedulerProtoDTO.newBuilder(), request.getScheduler())).build();
    }

    com.zqnt.utils.mission.proto.DeleteSchedulerRequest deleteScheduler(
            com.zqnt.sdk.client.connector.domains.DeleteSchedulerRequest request) {
        return com.zqnt.utils.mission.proto.DeleteSchedulerRequest.newBuilder()
                .setBase(base(request.getContext())).setSchedulerId(request.getSchedulerId()).build();
    }

    com.zqnt.utils.mission.proto.DeleteSchedulersRequest deleteSchedulers(
            com.zqnt.sdk.client.connector.domains.DeleteSchedulersRequest request) {
        var builder = com.zqnt.utils.mission.proto.DeleteSchedulersRequest.newBuilder()
                .setBase(base(request.getContext()));
        if (request.getSchedulerIds() != null) builder.addAllSchedulerIds(request.getSchedulerIds());
        return builder.build();
    }

    ConnectorGetPoliciesRequest policies(GetPoliciesRequest request) {
        return ConnectorGetPoliciesRequest.newBuilder().setBase(base(request.getContext()))
                .setPolicyType(request.getPolicyType()).build();
    }

    ConnectorGetAllPoliciesRequest allPolicies(GetAllActivePoliciesRequest request) {
        return ConnectorGetAllPoliciesRequest.newBuilder().setBase(base(request.getContext())).build();
    }

    ConnectorGetConfigsRequest configs(GetTechnicalConfigsRequest request) {
        var builder = ConnectorGetConfigsRequest.newBuilder().setBase(base(request.getContext()));
        set(builder::setScope, request.getScope());
        set(builder::setScopeTarget, request.getScopeTarget());
        return builder.build();
    }

    ConnectorStoreDetectionRequest detection(StoreDetectionRequest request) {
        var dto = request.getDetection();
        var builder = ConnectorStoreDetectionRequest.newBuilder().setBase(base(request.getContext()))
                .setAssetSn(dto.getAssetSn()).setObjectId(dto.getObjectId()).setObjectType(dto.getObjectType());
        set(builder::setSubAssetSn, dto.getSubAssetSn());
        set(builder::setTaskId, dto.getTaskId());
        set(builder::setConfidence, dto.getConfidence());
        set(builder::setBoundingBoxX, dto.getBoundingBoxX());
        set(builder::setBoundingBoxY, dto.getBoundingBoxY());
        set(builder::setBoundingBoxWidth, dto.getBoundingBoxWidth());
        set(builder::setBoundingBoxHeight, dto.getBoundingBoxHeight());
        set(builder::setStreamUrl, dto.getStreamUrl());
        builder.setDetectedAt(ProtobufHelpers.toTimestamp(dto.getDetectedAt()));
        return builder.build();
    }

    ConnectorStoreTelemetryRequest telemetry(StoreTelemetryRequest request) {
        TelemetryData telemetry = request.getTelemetry();
        var builder = ConnectorStoreTelemetryRequest.newBuilder().setBase(base(request.getContext()));
        if (request.getSourceType() == StoreTelemetryRequest.SourceType.ASSET) {
            builder.setType(TelemetryType.TELEMETRY_TYPE_ASSET).setAssetTelemetry(assetTelemetry(request, telemetry));
        } else {
            builder.setType(TelemetryType.TELEMETRY_TYPE_SUBASSET).setSubAssetTelemetry(subAssetTelemetry(request, telemetry));
        }
        return builder.build();
    }

    private AssetTelemetryProto assetTelemetry(StoreTelemetryRequest request, TelemetryData data) {
        var builder = AssetTelemetryProto.newBuilder().setAssetId(request.getAssetId())
                .setTimestamp(ProtobufHelpers.toTimestamp(data.getTimestamp()))
                .setSourceSystem(request.getSourceSystem() != null ? request.getSourceSystem() : "client-sdk");
        commonTelemetry(builder::setLatitude, builder::setLongitude, builder::setAltitude,
                builder::setRelativeAltitude, builder::setHeading, builder::setWindSpeed, data);
        if (data.getAsset() != null) {
            set(builder::setTemperature, data.getAsset().getEnvironmentTemp() == null ? null : data.getAsset().getEnvironmentTemp().doubleValue());
            set(builder::setHumidity, data.getAsset().getHumidity() == null ? null : data.getAsset().getHumidity().doubleValue());
            set(builder::setOperationalMode, data.getAsset().getMode() == null ? null : data.getAsset().getMode().name());
        }
        if (request.getAdditionalData() != null) builder.putAllTelemetryData(request.getAdditionalData());
        return builder.build();
    }

    private SubAssetTelemetryProto subAssetTelemetry(StoreTelemetryRequest request, TelemetryData data) {
        var builder = SubAssetTelemetryProto.newBuilder().setAssetId(request.getAssetId())
                .setTimestamp(ProtobufHelpers.toTimestamp(data.getTimestamp()))
                .setSourceSystem(request.getSourceSystem() != null ? request.getSourceSystem() : "client-sdk");
        commonTelemetry(builder::setLatitude, builder::setLongitude, builder::setAltitude,
                builder::setRelativeAltitude, builder::setHeading, builder::setWindSpeed, data);
        if (data.getSubAsset() != null) {
            set(builder::setHorizontalSpeed, number(data.getSubAsset().getHorizontalSpeed()));
            set(builder::setVerticalSpeed, number(data.getSubAsset().getVerticalSpeed()));
            set(builder::setOperationalMode, data.getSubAsset().getMode() == null ? null : data.getSubAsset().getMode().name());
            if (data.getSubAsset().getBatteryInformation() != null) {
                try {
                    set(builder::setBatteryPercentage,
                            Double.valueOf(data.getSubAsset().getBatteryInformation().getPercentage()));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (request.getAdditionalData() != null) builder.putAllTelemetryData(request.getAdditionalData());
        return builder.build();
    }

    private void commonTelemetry(java.util.function.DoubleConsumer latitude,
                                 java.util.function.DoubleConsumer longitude,
                                 java.util.function.DoubleConsumer altitude,
                                 java.util.function.DoubleConsumer relativeAltitude,
                                 java.util.function.DoubleConsumer heading,
                                 java.util.function.DoubleConsumer windSpeed,
                                 TelemetryData data) {
        if (data.getLatitude() != null) latitude.accept(data.getLatitude());
        if (data.getLongitude() != null) longitude.accept(data.getLongitude());
        if (data.getAbsoluteAltitude() != null) altitude.accept(data.getAbsoluteAltitude());
        if (data.getRelativeAltitude() != null) relativeAltitude.accept(data.getRelativeAltitude());
        if (data.getHeading() != null) heading.accept(data.getHeading());
        if (data.getWindSpeed() != null) windSpeed.accept(data.getWindSpeed());
    }

    ProduceNotificationRequest notification(StoreNotificationRequest request) {
        NotificationEvent.Builder event = NotificationEvent.newBuilder();
        if (request.getAssetStatus() != null) {
            var source = request.getAssetStatus();
            var value = AssetStatusEvent.newBuilder().setSn(source.getSn());
            set(value::setAssetId, source.getAssetId()); set(value::setOnline, source.getOnline());
            set(value::setMessage, source.getMessage()); event.setAssetStatus(value);
        } else if (request.getCommandExecution() != null) {
            var source = request.getCommandExecution();
            var value = com.zqnt.utils.events.proto.CommandExecutionEvent.newBuilder()
                    .setExternalExecutionId(source.getExternalExecutionId())
                    .setStatus(CommandExecutionStatus.valueOf(source.getStatus().name()))
                    .setAssetSn(source.getAssetSn())
                    .setOccurredAt(source.getOccurredAt() != null
                            ? ProtobufHelpers.toTimestamp(source.getOccurredAt()) : ProtobufHelpers.now());
            set(value::setCommandId, source.getCommandId()); set(value::setProgress, source.getProgress());
            set(value::setMessage, source.getMessage());
            if (source.getOutput() != null) value.setOutput(struct(source.getOutput()));
            event.setCommandExecution(value);
        } else if (request.getMission() != null) {
            var source = request.getMission();
            var value = MissionEvent.newBuilder().setMissionId(source.getMissionId())
                    .setMissionType(MissionType.valueOf(source.getMissionType().name()))
                    .setStatus(MissionStatus.valueOf(source.getStatus().name()));
            set(value::setMessage, source.getMessage()); event.setMission(value);
        } else {
            throw new IllegalArgumentException("A notification event is required");
        }
        return ProduceNotificationRequest.newBuilder().setBase(base(request.getContext())).setEvent(event)
                .setEventType(NotificationEventType.valueOf("NOTIFICATION_EVENT_" + request.getEventType().name()))
                .setSeverity(NotificationSeverity.valueOf("NOTIFICATION_SEVERITY_" + request.getSeverity().name()))
                .build();
    }

    com.zqnt.sdk.client.connector.domains.ConnectorResponse connectorResponse(
            com.zqnt.utils.connector.proto.ConnectorResponse proto) {
        return com.zqnt.sdk.client.connector.domains.ConnectorResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .id(proto.hasId() ? proto.getId() : null).timestamp(time(proto.getTimestamp()))
                .assetId(proto.hasAssetId() ? proto.getAssetId() : null)
                .message(proto.hasResponseMessage() ? proto.getResponseMessage() : null)
                .asset(proto.hasAsset() ? asset(proto.getAsset()) : null)
                .subAsset(proto.hasSubAsset() ? subAsset(proto.getSubAsset()) : null)
                .organization(proto.hasOrganization() ? organization(proto.getOrganization()) : null)
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    com.zqnt.sdk.client.connector.domains.AssetMonitoringResponse monitoring(
            com.zqnt.utils.connector.proto.AssetMonitoringResponse proto) {
        return com.zqnt.sdk.client.connector.domains.AssetMonitoringResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .timestamp(time(proto.getTimestamp()))
                .assets(proto.hasAssets() ? proto.getAssets().getAssetsList().stream().map(this::asset).toList() : List.of())
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    com.zqnt.sdk.client.connector.domains.AssetPayloadResponse payloadResponse(
            com.zqnt.utils.connector.proto.AssetPayloadResponse proto) {
        return com.zqnt.sdk.client.connector.domains.AssetPayloadResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .payload(proto.hasPayload() ? payload(proto.getPayload()) : null)
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    com.zqnt.sdk.client.connector.domains.AssetPayloadListResponse payloadListResponse(
            com.zqnt.utils.connector.proto.AssetPayloadListResponse proto) {
        return com.zqnt.sdk.client.connector.domains.AssetPayloadListResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .payloads(proto.getPayloadsList().stream().map(this::payload).toList())
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    com.zqnt.sdk.client.connector.domains.ConnectorPolicyResponse policyResponse(
            com.zqnt.utils.connector.proto.ConnectorPolicyResponse proto) {
        List<com.zqnt.sdk.client.connector.domains.ConnectorPolicyResponse.Policy> policies = proto.hasPolicyList()
                ? proto.getPolicyList().getPoliciesList().stream().map(value ->
                com.zqnt.sdk.client.connector.domains.ConnectorPolicyResponse.Policy.builder()
                .id(value.getId()).name(value.getName()).description(value.getDescription())
                .policyType(value.getPolicyType()).scope(value.getScope())
                .scopeTarget(value.hasScopeTarget() ? value.getScopeTarget() : null).priority(value.getPriority())
                .active(value.getActive()).strategyType(value.getStrategyType())
                .conditions(value.hasConditions() ? value.getConditions() : null)
                .constraints(value.hasConstraints() ? value.getConstraints() : null)
                .actions(value.hasActions() ? value.getActions() : null).build()).toList() : List.of();
        return com.zqnt.sdk.client.connector.domains.ConnectorPolicyResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .timestamp(time(proto.getTimestamp())).policies(policies)
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    com.zqnt.sdk.client.connector.domains.ConnectorConfigResponse configResponse(
            com.zqnt.utils.connector.proto.ConnectorConfigResponse proto) {
        List<com.zqnt.sdk.client.connector.domains.ConnectorConfigResponse.TechnicalConfig> configs = proto.hasConfigList()
                ? proto.getConfigList().getConfigsList().stream().map(value ->
                com.zqnt.sdk.client.connector.domains.ConnectorConfigResponse.TechnicalConfig.builder()
                .id(value.getId()).configKey(value.getConfigKey())
                .configValue(value.hasConfigValue() ? value.getConfigValue() : null).valueType(value.getValueType())
                .scope(value.getScope()).scopeTarget(value.hasScopeTarget() ? value.getScopeTarget() : null)
                .active(value.getActive()).description(value.hasDescription() ? value.getDescription() : null).build()).toList()
                : List.of();
        return com.zqnt.sdk.client.connector.domains.ConnectorConfigResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid())
                .timestamp(time(proto.getTimestamp())).configs(configs)
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    MissionResponse missionResponse(com.zqnt.utils.mission.proto.MissionResponse proto) {
        return MissionResponse.builder().success(!proto.getHasErrors()).tid(proto.getTid()).missionId(proto.getMissionId())
                .timestamp(time(proto.getTimestamp())).error(proto.hasError() ? MissionResponse.ErrorInfo.builder()
                        .errorCode(proto.getError().getErrorCode().name()).errorMessage(proto.getError().getErrorMessage())
                        .timestamp(time(proto.getError().getTimestamp())).build() : null)
                .progress(proto.hasProgress() ? MissionResponse.ProgressInfo.builder().progress(proto.getProgress().getProgress())
                        .state(proto.getProgress().getState()).leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds()).build() : null)
                .missionData(proto.hasMission() ? JsonUtils.fromJson(ProtoJsonUtils.toJson(proto.getMission()), MissionDTO.class) : null)
                .build();
    }

    TaskResponse taskResponse(com.zqnt.utils.mission.proto.TaskResponse proto) {
        return TaskResponse.builder().success(!proto.getHasErrors()).tid(proto.getTid()).taskId(proto.getTaskId())
                .timestamp(time(proto.getTimestamp())).error(proto.hasError() ? TaskResponse.ErrorInfo.builder()
                        .errorCode(proto.getError().getErrorCode().name()).errorMessage(proto.getError().getErrorMessage())
                        .timestamp(time(proto.getError().getTimestamp())).build() : null)
                .progress(proto.hasProgress() ? TaskResponse.ProgressInfo.builder().progress(proto.getProgress().getProgress())
                        .state(proto.getProgress().getState()).leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds()).build() : null)
                .taskData(proto.hasTask() ? MissionAutonomyImpl.mapTaskProtoToDto(proto.getTask()) : null)
                .build();
    }

    SchedulerResponse schedulerResponse(com.zqnt.utils.mission.proto.SchedulerResponse proto) {
        return SchedulerResponse.builder().success(!proto.getHasErrors()).tid(proto.getTid()).schedulerId(proto.getSchedulerId())
                .timestamp(time(proto.getTimestamp())).error(proto.hasError() ? SchedulerResponse.ErrorInfo.builder()
                        .errorCode(proto.getError().getErrorCode().name()).errorMessage(proto.getError().getErrorMessage())
                        .timestamp(time(proto.getError().getTimestamp())).build() : null)
                .progress(proto.hasProgress() ? SchedulerResponse.ProgressInfo.builder().progress(proto.getProgress().getProgress())
                        .state(proto.getProgress().getState()).leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds()).build() : null)
                .schedulerData(proto.hasScheduler() ? JsonUtils.fromJson(ProtoJsonUtils.toJson(proto.getScheduler()), SchedulerDTO.class) : null)
                .build();
    }

    com.zqnt.sdk.client.connector.domains.WaypointsResponse waypointsResponse(
            com.zqnt.utils.mission.proto.WaypointsResponse proto) {
        List<WaypointDTO> waypoints = proto.hasWaypoints() ? proto.getWaypoints().getWaypointsList().stream()
                .map(value -> JsonUtils.fromJson(ProtoJsonUtils.toJson(value), WaypointDTO.class)).toList() : List.of();
        return com.zqnt.sdk.client.connector.domains.WaypointsResponse.builder()
                .success(!proto.getHasErrors()).tid(proto.getTid()).taskId(proto.getTaskId())
                .timestamp(time(proto.getTimestamp())).waypoints(waypoints)
                .error(proto.hasError() ? error(proto.getError()) : null).build();
    }

    private com.zqnt.sdk.client.connector.domains.ConnectorResponse.Organization organization(OrganizationProtoDTO proto) {
        return com.zqnt.sdk.client.connector.domains.ConnectorResponse.Organization.builder()
                .id(proto.hasId() ? proto.getId() : null)
                .name(proto.getName()).description(proto.getDescription()).assetIds(proto.getAssetsList()).build();
    }

    private com.zqnt.sdk.client.connector.domains.ConnectorResponse.ErrorInfo error(GlobalErrorMessage proto) {
        return com.zqnt.sdk.client.connector.domains.ConnectorResponse.ErrorInfo.builder()
                .errorCode(proto.getErrorCode().name())
                .errorMessage(proto.getErrorMessage()).timestamp(time(proto.getTimestamp())).build();
    }

    private AssetDTO asset(AssetProtoDTO proto) {
        return AssetDTO.builder().id(uuid(proto.hasId() ? proto.getId() : null)).sn(proto.hasSn() ? proto.getSn() : null)
                .name(proto.hasName() ? proto.getName() : null).type(proto.hasType() ? proto.getType() : null)
                .vendor(proto.hasVendor() ? proto.getVendor() : null).connection(proto.hasConnection() ? proto.getConnection() : null)
                .systemConnectionString(proto.hasSystemConnectionString() ? proto.getSystemConnectionString() : null)
                .model(proto.hasModel() ? proto.getModel() : null).liveStreamPushUrl(proto.hasLiveStreamPushUrl() ? proto.getLiveStreamPushUrl() : null)
                .liveStreamPullUrl(proto.hasLiveStreamPullUrl() ? proto.getLiveStreamPullUrl() : null)
                .externalId(proto.hasExternalId() ? proto.getExternalId() : null)
                .externalDeviceType(proto.hasExternalDeviceType() ? proto.getExternalDeviceType() : null)
                .externalDeviceSubType(proto.hasExternalDeviceSubType() ? proto.getExternalDeviceSubType() : null)
                .organization(uuid(proto.hasOrganization() ? proto.getOrganization() : null))
                .subAssets(proto.getSubAssetsList().stream().map(this::subAsset).toList())
                .payloads(proto.getPayloadsList().stream().map(this::payload).toList())
                .createdAt(proto.hasCreatedAt() ? time(proto.getCreatedAt()) : null)
                .modifiedAt(proto.hasModifiedAt() ? time(proto.getModifiedAt()) : null)
                .modifiedFrom(proto.hasModifiedFrom() ? proto.getModifiedFrom() : null).build();
    }

    private AssetProtoDTO asset(AssetDTO dto) {
        var builder = AssetProtoDTO.newBuilder();
        set(builder::setId, string(dto.getId())); set(builder::setSn, dto.getSn()); set(builder::setName, dto.getName());
        set(builder::setType, dto.getType()); set(builder::setVendor, dto.getVendor()); set(builder::setConnection, dto.getConnection());
        set(builder::setSystemConnectionString, dto.getSystemConnectionString()); set(builder::setModel, dto.getModel());
        set(builder::setLiveStreamPushUrl, dto.getLiveStreamPushUrl()); set(builder::setLiveStreamPullUrl, dto.getLiveStreamPullUrl());
        set(builder::setExternalId, dto.getExternalId()); set(builder::setExternalDeviceType, dto.getExternalDeviceType());
        set(builder::setExternalDeviceSubType, dto.getExternalDeviceSubType()); set(builder::setOrganization, string(dto.getOrganization()));
        if (dto.getSubAssets() != null) dto.getSubAssets().stream().map(this::subAsset).forEach(builder::addSubAssets);
        if (dto.getPayloads() != null) dto.getPayloads().stream().map(this::payload).forEach(builder::addPayloads);
        if (dto.getCreatedAt() != null) builder.setCreatedAt(ProtobufHelpers.toTimestamp(dto.getCreatedAt()));
        if (dto.getModifiedAt() != null) builder.setModifiedAt(ProtobufHelpers.toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom()); return builder.build();
    }

    private SubAssetDTO subAsset(SubAssetProtoDTO proto) {
        return SubAssetDTO.builder().id(uuid(proto.hasId() ? proto.getId() : null)).sn(proto.hasSn() ? proto.getSn() : null)
                .name(proto.hasName() ? proto.getName() : null).type(proto.hasType() ? proto.getType() : null)
                .vendor(proto.hasVendor() ? proto.getVendor() : null).connection(proto.hasConnection() ? proto.getConnection() : null)
                .systemConnectionString(proto.hasSystemConnectionString() ? proto.getSystemConnectionString() : null)
                .model(proto.hasModel() ? proto.getModel() : null).liveStreamPushUrl(proto.hasLiveStreamPushUrl() ? proto.getLiveStreamPushUrl() : null)
                .liveStreamPullUrl(proto.hasLiveStreamPullUrl() ? proto.getLiveStreamPullUrl() : null)
                .streamUrlPredefined(proto.hasStreamUrlPredefined() ? proto.getStreamUrlPredefined() : null)
                .externalId(proto.hasExternalId() ? proto.getExternalId() : null)
                .externalDeviceType(proto.hasExternalDeviceType() ? proto.getExternalDeviceType() : null)
                .externalDeviceSubType(proto.hasExternalDeviceSubType() ? proto.getExternalDeviceSubType() : null)
                .payloads(proto.getPayloadsList().stream().map(this::payload).toList())
                .createdAt(proto.hasCreatedAt() ? time(proto.getCreatedAt()) : null)
                .modifiedAt(proto.hasModifiedAt() ? time(proto.getModifiedAt()) : null)
                .modifiedFrom(proto.hasModifiedFrom() ? proto.getModifiedFrom() : null).build();
    }

    private SubAssetProtoDTO subAsset(SubAssetDTO dto) {
        var builder = SubAssetProtoDTO.newBuilder();
        set(builder::setId, string(dto.getId())); set(builder::setSn, dto.getSn()); set(builder::setName, dto.getName());
        set(builder::setType, dto.getType()); set(builder::setVendor, dto.getVendor()); set(builder::setConnection, dto.getConnection());
        set(builder::setSystemConnectionString, dto.getSystemConnectionString()); set(builder::setModel, dto.getModel());
        set(builder::setLiveStreamPushUrl, dto.getLiveStreamPushUrl()); set(builder::setLiveStreamPullUrl, dto.getLiveStreamPullUrl());
        set(builder::setStreamUrlPredefined, dto.getStreamUrlPredefined()); set(builder::setExternalId, dto.getExternalId());
        set(builder::setExternalDeviceType, dto.getExternalDeviceType()); set(builder::setExternalDeviceSubType, dto.getExternalDeviceSubType());
        if (dto.getPayloads() != null) dto.getPayloads().stream().map(this::payload).forEach(builder::addPayloads);
        if (dto.getCreatedAt() != null) builder.setCreatedAt(ProtobufHelpers.toTimestamp(dto.getCreatedAt()));
        if (dto.getModifiedAt() != null) builder.setModifiedAt(ProtobufHelpers.toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom()); return builder.build();
    }

    private AssetPayloadDTO payload(AssetPayloadProtoDTO proto) {
        return AssetPayloadDTO.builder().id(uuid(proto.hasId() ? proto.getId() : null))
                .externalId(proto.hasExternalId() ? proto.getExternalId() : null).externalType(proto.hasExternalType() ? proto.getExternalType() : null)
                .slotIndex(proto.hasSlotIndex() ? proto.getSlotIndex() : null).name(proto.hasName() ? proto.getName() : null)
                .serialNumber(proto.hasSerialNumber() ? proto.getSerialNumber() : null).kind(proto.hasKind() ? proto.getKind() : null)
                .vendor(proto.hasVendor() ? proto.getVendor() : null).model(proto.hasModel() ? proto.getModel() : null)
                .firmwareVersion(proto.hasFirmwareVersion() ? proto.getFirmwareVersion() : null)
                .libraryVersion(proto.hasLibraryVersion() ? proto.getLibraryVersion() : null)
                .state(jsonMap(proto.hasStateJson() ? proto.getStateJson() : null))
                .active(proto.getActive())
                .lastSeenAt(proto.hasLastSeenAt() ? time(proto.getLastSeenAt()) : null)
                .createdAt(proto.hasCreatedAt() ? time(proto.getCreatedAt()) : null)
                .modifiedAt(proto.hasModifiedAt() ? time(proto.getModifiedAt()) : null)
                .modifiedFrom(proto.hasModifiedFrom() ? proto.getModifiedFrom() : null).build();
    }

    private AssetPayloadProtoDTO payload(AssetPayloadDTO dto) {
        var builder = AssetPayloadProtoDTO.newBuilder().setActive(Boolean.TRUE.equals(dto.getActive()));
        set(builder::setId, string(dto.getId())); set(builder::setExternalId, dto.getExternalId()); set(builder::setExternalType, dto.getExternalType());
        set(builder::setSlotIndex, dto.getSlotIndex()); set(builder::setName, dto.getName()); set(builder::setSerialNumber, dto.getSerialNumber());
        set(builder::setKind, dto.getKind()); set(builder::setVendor, dto.getVendor()); set(builder::setModel, dto.getModel());
        set(builder::setFirmwareVersion, dto.getFirmwareVersion()); set(builder::setLibraryVersion, dto.getLibraryVersion());
        if (dto.getState() != null) builder.setStateJson(JsonUtils.toJson(dto.getState()));
        if (dto.getLastSeenAt() != null) builder.setLastSeenAt(ProtobufHelpers.toTimestamp(dto.getLastSeenAt()));
        if (dto.getCreatedAt() != null) builder.setCreatedAt(ProtobufHelpers.toTimestamp(dto.getCreatedAt()));
        if (dto.getModifiedAt() != null) builder.setModifiedAt(ProtobufHelpers.toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom()); return builder.build();
    }

    private FieldMask mask(List<String> paths) {
        return FieldMask.newBuilder().addAllPaths(paths != null ? paths : List.of()).build();
    }

    private LocalDateTime time(Timestamp value) { return ProtobufHelpers.toLocalDateTime(value); }
    private UUID uuid(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
    private String string(UUID value) { return value != null ? value.toString() : null; }
    private Double number(Number value) { return value != null ? value.doubleValue() : null; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String json) {
        return json == null || json.isBlank() ? Collections.emptyMap() : JsonUtils.fromJson(json, Map.class);
    }

    private Struct struct(Map<String, Object> value) {
        return (Struct) ProtoJsonUtils.fromJson(JsonUtils.toJson(value), Struct.newBuilder());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Struct value) {
        return JsonUtils.fromJson(ProtoJsonUtils.toJson(value), Map.class);
    }

    private <T> void set(java.util.function.Consumer<T> setter, T value) {
        if (value != null) setter.accept(value);
    }
}
