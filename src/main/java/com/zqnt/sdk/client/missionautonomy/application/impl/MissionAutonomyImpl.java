package com.zqnt.sdk.client.missionautonomy.application.impl;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy;
import com.zqnt.sdk.client.missionautonomy.capabilities.*;
import com.zqnt.sdk.client.missionautonomy.domains.MissionResponse;
import com.zqnt.sdk.client.missionautonomy.domains.SchedulerResponse;
import com.zqnt.sdk.client.missionautonomy.domains.TaskResponse;
import com.zqnt.utils.JsonUtils;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.core.ProtoJsonUtils;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.execution.proto.*;
import com.zqnt.utils.mission.proto.*;
import com.zqnt.utils.missionautonomy.domains.MissionDTO;
import com.zqnt.utils.missionautonomy.domains.MissionZoneDTO;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;
import com.zqnt.utils.missionautonomy.domains.TaskDTO;
import com.zqnt.utils.missionautonomy.domains.config.*;
import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Mission Autonomy client implementation using standard gRPC stubs.
 * Performance optimizations:
 * - Uses FutureStub for optimal async unary calls
 * - CompletableFuture for framework-agnostic async operations
 * - Built-in resilience with retry and circuit breaker
 * - Dedicated executor for callback handling
 */
@Slf4j
public class MissionAutonomyImpl implements MissionAutonomy {

    private final MissionAutonomyServiceGrpc.MissionAutonomyServiceFutureStub futureStub;
    private final GrpcResilience resilience;
    private final GrpcClientConfig config;
    private final ExecutorService callbackExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * Private constructor - use create() factory method.
     */
    private MissionAutonomyImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.config = config;
        this.resilience = new GrpcResilience(
                config.getMaxRetryAttempts(),
                config.getRetryDelayMillis(),
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerWaitDurationMillis()
        );
        this.futureStub = MissionAutonomyServiceGrpc.newFutureStub(channel);

        // Dedicated executor for gRPC callbacks
        this.callbackExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mission-autonomy-callback");
            t.setDaemon(true);
            return t;
        });

        // Scheduler for timeout handling (shared)
        this.timeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "mission-autonomy-timeout");
            t.setDaemon(true);
            return t;
        });

        log.debug("MissionAutonomy created with channel for {}:{}",
                config.getMissionAutonomyConfig().getHost(),
                config.getMissionAutonomyConfig().getPort());
    }

    public static MissionAutonomy create(GrpcClientConfig config, ManagedChannel channel) {
        return new MissionAutonomyImpl(config, channel);
    }

    @Override
    public CompletableFuture<ApplicationProtoDTO> upsertApplication(
            ApplicationProtoDTO application, String expectedRevision) {
        if (application == null) throw new IllegalArgumentException("application must not be null");
        if (application.getId().isBlank()) throw new IllegalArgumentException("package id must not be blank");
        if (application.getVersion().isBlank()) throw new IllegalArgumentException("package version must not be blank");
        var request = UpsertApplicationRequest.newBuilder().setBase(buildBase())
                .setApplication(application);
        setIfPresent(expectedRevision, request::setExpectedRevision);
        return executeAsync(() -> futureStub.upsertApplication(request.build()))
                .thenApply(this::requirePackage);
    }

    @Override
    public CompletableFuture<ApplicationProtoDTO> getApplication(String applicationId, String version) {
        var request = GetApplicationRequest.newBuilder().setBase(buildBase())
                .setApplicationId(requireText(applicationId, "applicationId"));
        setIfPresent(version, request::setVersion);
        return executeAsync(() -> futureStub.getApplication(request.build()))
                .thenApply(this::requirePackage);
    }

    @Override
    public CompletableFuture<ResultPage<ApplicationProtoDTO>> listApplications(
            ApplicationQuery query) {
        ApplicationQuery safe = query == null ? ApplicationQuery.firstPage() : query;
        var request = ListApplicationsRequest.newBuilder().setBase(buildBase());
        if (safe.scope() != null) request.setScope(safe.scope());
        if (safe.enabledOnly() != null) request.setEnabledOnly(safe.enabledOnly());
        if (safe.pageSize() != null) request.setPageSize(safe.pageSize());
        setIfPresent(safe.pageToken(), request::setPageToken);
        return executeAsync(() -> futureStub.listApplications(request.build())).thenApply(response -> {
            requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                    response.getMeta().getTid());
            if (!response.hasResult()) return new ResultPage<>(List.of(), "");
            return new ResultPage<>(response.getResult().getApplicationsList(),
                    response.getResult().hasNextPageToken() ? response.getResult().getNextPageToken() : "");
        });
    }

    @Override
    public CompletableFuture<Void> deleteApplication(String applicationId, String version,
            String expectedRevision) {
        var request = DeleteApplicationRequest.newBuilder().setBase(buildBase())
                .setApplicationId(requireText(applicationId, "applicationId"));
        setIfPresent(version, request::setVersion);
        setIfPresent(expectedRevision, request::setExpectedRevision);
        return executeAsync(() -> futureStub.deleteApplication(request.build())).thenApply(response -> {
            requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                    response.getMeta().getTid());
            return null;
        });
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> createSkillExecution(
            SkillExecutionCommand command) {
        var request = createExecutionRequest(command);
        return executeAsync(() -> futureStub.createSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> executeSkill(SkillExecutionCommand command) {
        requireCommand(command);
        var request = ExecuteSkillRequest.newBuilder().setBase(buildBase(command.assetSn()))
                .setSpec(command.spec()).setOptions(command.options()).setIdempotencyKey(command.idempotencyKey());
        applyScope(command, request::setOrganizationId, request::setLocationId, request::setTheatreId);
        return executeAsync(() -> futureStub.executeSkill(request.build())).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> getSkillExecution(String executionId) {
        var request = GetSkillExecutionRequest.newBuilder().setBase(buildBase())
                .setExecutionId(requireText(executionId, "executionId")).build();
        return executeAsync(() -> futureStub.getSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<ResultPage<SkillExecutionProtoDTO>> listSkillExecutions(
            SkillExecutionQuery query) {
        SkillExecutionQuery safe = query == null ? SkillExecutionQuery.firstPage() : query;
        var request = ListSkillExecutionsRequest.newBuilder().setBase(buildBase());
        setIfPresent(safe.assetSn(), request::setAssetSn);
        setIfPresent(safe.organizationId(), request::setOrganizationId);
        if (safe.status() != null) request.setStatus(safe.status());
        setIfPresent(safe.applicationId(), request::setApplicationId);
        setIfPresent(safe.skillId(), request::setSkillId);
        setIfPresent(safe.theatreId(), request::setTheatreId);
        if (safe.pageSize() != null) request.setPageSize(safe.pageSize());
        setIfPresent(safe.pageToken(), request::setPageToken);
        return executeAsync(() -> futureStub.listSkillExecutions(request.build())).thenApply(response -> {
            requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                    response.getMeta().getTid());
            if (!response.hasResult()) return new ResultPage<>(List.of(), "");
            return new ResultPage<>(response.getResult().getExecutionsList(),
                    response.getResult().hasNextPageToken() ? response.getResult().getNextPageToken() : "");
        });
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> startSkillExecution(
            SkillExecutionLifecycleCommand command) {
        var request = lifecycleRequest(command);
        return executeAsync(() -> futureStub.startSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> pauseSkillExecution(
            SkillExecutionLifecycleCommand command) {
        var request = lifecycleRequest(command);
        return executeAsync(() -> futureStub.pauseSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> resumeSkillExecution(
            SkillExecutionLifecycleCommand command) {
        var request = lifecycleRequest(command);
        return executeAsync(() -> futureStub.resumeSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> cancelSkillExecution(
            SkillExecutionLifecycleCommand command) {
        var request = lifecycleRequest(command);
        return executeAsync(() -> futureStub.cancelSkillExecution(request)).thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<SkillExecutionProtoDTO> signalSkillExecution(SkillExecutionSignalCommand command) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
        var request = SignalSkillExecutionRequest.newBuilder().setBase(buildBase())
                .setExecutionId(command.executionId()).setData(command.data());
        setIfPresent(command.nodeId(), request::setNodeId);
        setIfPresent(command.eventType(), request::setEventType);
        if (command.approved() != null) request.setApproved(command.approved());
        setIfPresent(command.idempotencyKey(), request::setIdempotencyKey);
        return executeAsync(() -> futureStub.signalSkillExecution(request.build()))
                .thenApply(this::requireExecution);
    }

    @Override
    public CompletableFuture<ResolvedExecutionConfigProtoDTO> resolveExecutionConfig(ExecutionConfigQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        var request = ResolveExecutionConfigRequest.newBuilder().setBase(buildBase(query.assetSn()))
                .setContext(query.context()).addAllKeys(query.keys()).build();
        return executeAsync(() -> futureStub.resolveExecutionConfig(request)).thenApply(response -> {
            requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                    response.getMeta().getTid());
            if (!response.hasConfig()) throw malformed("Missing resolved configuration", response.getMeta().getTid());
            return response.getConfig();
        });
    }

    private CreateSkillExecutionRequest createExecutionRequest(SkillExecutionCommand command) {
        requireCommand(command);
        var request = CreateSkillExecutionRequest.newBuilder().setBase(buildBase(command.assetSn()))
                .setSpec(command.spec()).setOptions(command.options()).setIdempotencyKey(command.idempotencyKey());
        applyScope(command, request::setOrganizationId, request::setLocationId, request::setTheatreId);
        return request.build();
    }

    private SkillExecutionLifecycleRequest lifecycleRequest(SkillExecutionLifecycleCommand command) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
        var request = SkillExecutionLifecycleRequest.newBuilder().setBase(buildBase())
                .setExecutionId(command.executionId());
        setIfPresent(command.reason(), request::setReason);
        setIfPresent(command.idempotencyKey(), request::setIdempotencyKey);
        return request.build();
    }

    private ApplicationProtoDTO requirePackage(ApplicationResponse response) {
        requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                response.getMeta().getTid());
        if (!response.hasApplication()) throw malformed("Missing capability package", response.getMeta().getTid());
        return response.getApplication();
    }

    private SkillExecutionProtoDTO requireExecution(SkillExecutionResponse response) {
        requireSuccess(response.getHasErrors(), response.hasError() ? response.getError() : null,
                response.getMeta().getTid());
        if (!response.hasExecution()) throw malformed("Missing capability execution", response.getMeta().getTid());
        return response.getExecution();
    }

    private void requireSuccess(boolean hasErrors, com.zqnt.utils.common.proto.GlobalErrorMessage error,
            String transactionId) {
        if (!hasErrors && error == null) return;
        throw new MissionAutonomyClientException(error == null ? "" : error.getErrorCode().name(),
                error == null ? "Capability operation failed" : error.getErrorMessage(), transactionId);
    }

    private MissionAutonomyClientException malformed(String message, String transactionId) {
        return new MissionAutonomyClientException("MALFORMED_RESPONSE", message, transactionId);
    }

    private void requireCommand(SkillExecutionCommand command) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
    }

    private void applyScope(SkillExecutionCommand command,
            java.util.function.Consumer<String> organization,
            java.util.function.Consumer<String> location,
            java.util.function.Consumer<String> theatre) {
        setIfPresent(command.organizationId(), organization);
        setIfPresent(command.locationId(), location);
        setIfPresent(command.theatreId(), theatre);
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) setter.accept(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }


    @Override
    public CompletableFuture<MissionResponse> createMission(MissionDTO missionDTO) {
        missionDTO.validate();
        log.info("Creating mission: name={}", missionDTO.getName());

        var missionBuilder = mapMissionDtoToProto(MissionProtoDTO.newBuilder(), missionDTO);

        var protoRequest = CreateMissionRequest.newBuilder()
                .setBase(buildBase())
                .setMission(missionBuilder.build())
                .build();

        return removedLegacyOperation("createMission");
    }

    @Override
    public CompletableFuture<MissionResponse> updateMission(String missionId, MissionDTO missionDTO) {
        missionDTO.validate();
        log.info("Updating mission: missionId={}", missionId);

        var missionBuilder = mapMissionDtoToProto(MissionProtoDTO.newBuilder()
                .setId(missionId), missionDTO);

        var protoRequest = UpdateMissionRequest.newBuilder()
                .setBase(buildBase())
                .setMissionId(missionId)
                .setMission(missionBuilder.build())
                .build();

        return removedLegacyOperation("updateMission");
    }

    public static MissionProtoDTO.Builder mapMissionDtoToProto(MissionProtoDTO.Builder missionId, MissionDTO missionDTO) {
        var missionBuilder = missionId
                .setName(missionDTO.getName() != null ? missionDTO.getName() : "")
                .setDescription(missionDTO.getDescription() != null ? missionDTO.getDescription() : "");

        if (missionDTO.getStatus() != null) {
            missionBuilder.setStatus(missionDTO.getStatus());
        }
        if (missionDTO.getType() != null) {
            missionBuilder.setType(missionDTO.getType());
        }
        if (missionDTO.getGeoJson() != null) {
            missionBuilder.setGeoJson(missionDTO.getGeoJson());
        }
        if (missionDTO.getStartDate() != null) {
            missionBuilder.setStartDate(ProtobufHelpers.toTimestamp(missionDTO.getStartDate()));
        }
        if (missionDTO.getEndDate() != null) {
            missionBuilder.setEndDate(ProtobufHelpers.toTimestamp(missionDTO.getEndDate()));
        }
        if (missionDTO.getAssignedAssets() != null && !missionDTO.getAssignedAssets().isEmpty()) {
            missionBuilder.addAllAssignedAssets(missionDTO.getAssignedAssets());
        }
        if (missionDTO.getCreatedAt() != null) {
            missionBuilder.setCreatedAt(ProtobufHelpers.toTimestamp(missionDTO.getCreatedAt()));
        }
        if (missionDTO.getModifiedAt() != null) {
            missionBuilder.setModifiedAt(ProtobufHelpers.toTimestamp(missionDTO.getModifiedAt()));
        }
        if (missionDTO.getModifiedFrom() != null) {
            missionBuilder.setUpdatedUser(missionDTO.getModifiedFrom());
        }
        if (missionDTO.getMissionConfig() != null) {
            missionBuilder.setMissionConfig((DynamicConfigProto) ProtoJsonUtils.fromJson(
                    JsonUtils.toJson(missionDTO.getMissionConfig()), DynamicConfigProto.newBuilder()));
        }
        if (missionDTO.getAutonomyConfig() != null) {
            missionBuilder.setAutonomyConfig((AutonomyConfigProto) ProtoJsonUtils.fromJson(
                    JsonUtils.toJson(missionDTO.getAutonomyConfig()), AutonomyConfigProto.newBuilder()));
        }
        if (missionDTO.getExternalId() != null) {
            missionBuilder.setExternalId(missionDTO.getExternalId());
        }
        if (missionDTO.getExternalMissionType() != null) {
            missionBuilder.setExternalMissionType(missionDTO.getExternalMissionType());
        }
        if (missionDTO.getTasks() != null) {
            missionBuilder.addAllTasks(missionDTO.getTasks().stream()
                    .map(task -> mapTaskDtoToProto(TaskProtoDTO.newBuilder(), task).build())
                    .toList());
        }
        if (missionDTO.getZones() != null) {
            missionBuilder.addAllZones(missionDTO.getZones().stream()
                    .map(MissionAutonomyImpl::mapMissionZoneDtoToProto)
                    .toList());
        }
        return missionBuilder;
    }

    public static MissionZoneProtoDTO mapMissionZoneDtoToProto(MissionZoneDTO zone) {
        return (MissionZoneProtoDTO) ProtoJsonUtils.fromJson(
                JsonUtils.toJson(zone), MissionZoneProtoDTO.newBuilder());
    }

    @Override
    public CompletableFuture<MissionResponse> getMission(String missionId) {
        log.info("Getting mission: missionId={}", missionId);

        var protoRequest = GetMissionRequest.newBuilder()
                .setBase(buildBase())
                .setMissionId(missionId)
                .build();

        return removedLegacyOperation("getMission");
    }

    @Override
    public CompletableFuture<MissionResponse> deleteMission(String missionId) {
        log.info("Deleting mission: missionId={}", missionId);

        var protoRequest = DeleteMissionRequest.newBuilder()
                .setBase(buildBase())
                .setMissionId(missionId)
                .build();

        return removedLegacyOperation("deleteMission");
    }

    @Override
    public CompletableFuture<MissionResponse> uploadMissionNfzZones(
            String missionId, List<MissionZoneDTO> zones, boolean replaceExisting) {
        log.info("Uploading mission NFZ zones: missionId={}, count={}, replaceExisting={}",
                missionId, zones != null ? zones.size() : 0, replaceExisting);

        var protoRequest = mapUploadMissionNfzZonesRequest(
                buildBase(), missionId, zones, replaceExisting);

        return removedLegacyOperation("uploadMissionNfzZones");
    }

    static UploadMissionNfzZonesRequest mapUploadMissionNfzZonesRequest(
            RequestBase base, String missionId, List<MissionZoneDTO> zones, boolean replaceExisting) {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId must not be null or blank");
        }
        if (zones == null) {
            throw new IllegalArgumentException("zones must not be null");
        }
        for (int index = 0; index < zones.size(); index++) {
            MissionZoneDTO zone = zones.get(index);
            if (zone == null) {
                throw new IllegalArgumentException("zones[" + index + "] must not be null");
            }
            zone.validate();
        }

        return UploadMissionNfzZonesRequest.newBuilder()
                .setBase(base)
                .setMissionId(missionId)
                .addAllZones(zones.stream()
                        .map(MissionAutonomyImpl::mapMissionZoneDtoToProto)
                        .toList())
                .setReplaceExisting(replaceExisting)
                .build();
    }

    @Override
    public CompletableFuture<TaskResponse> createTask(TaskDTO taskDTO) {
        log.info("Creating task: name={}", taskDTO.getName());
        taskDTO.validate();
        var taskProtoBuilder = mapTaskDtoToProto(TaskProtoDTO.newBuilder(), taskDTO);

        var protoRequest = CreateTaskRequest.newBuilder()
                .setBase(buildBase())
                .setTask(taskProtoBuilder.build())
                .build();

        return removedLegacyOperation("createTask");
    }

    @Override
    public CompletableFuture<TaskResponse> updateTask(String taskId, TaskDTO taskDTO) {
        log.info("Updating task: taskId={}", taskId);
        taskDTO.validate();

        var taskProtoBuilder = mapTaskDtoToProto(TaskProtoDTO.newBuilder()
                .setId(taskId), taskDTO);

        var protoRequest = UpdateTaskRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .setTask(taskProtoBuilder.build())
                .build();

        return removedLegacyOperation("updateTask");
    }


    public static TaskProtoDTO.Builder mapTaskDtoToProto(TaskProtoDTO.Builder taskId, TaskDTO taskDTO) {
        var taskProtoBuilder = taskId
                .setName(taskDTO.getName() != null ? taskDTO.getName() : "")
                .setSnNumber(taskDTO.getSnNumber() != null ? taskDTO.getSnNumber() : "")
                .setAssetId(taskDTO.getAssetId() != null ? taskDTO.getAssetId() : "")
                .setDescription(taskDTO.getDescription() != null ? taskDTO.getDescription() : "")
                .setCurrentStep(taskDTO.getCurrentStep() != null ? taskDTO.getCurrentStep() : "")
                .setModifiedFrom(taskDTO.getModifiedFrom() != null ? taskDTO.getModifiedFrom() : "");

        if (taskDTO.getMissionId() != null) {
            taskProtoBuilder.setMissionId(taskDTO.getMissionId().toString());
        }
        if (taskDTO.getTaskType() != null) {
            taskProtoBuilder.setTaskType(TaskTypeProto.valueOf(taskDTO.getTaskType().name()));
        }
        mapTaskConfig(taskProtoBuilder, taskDTO);
        if (taskDTO.getStatus() != null) {
            taskProtoBuilder.setStatus(taskDTO.getStatus());
        }
        if (taskDTO.getCreatedAt() != null) {
            taskProtoBuilder.setCreatedAt(ProtobufHelpers.toTimestamp(taskDTO.getCreatedAt()));
        }
        if (taskDTO.getModifiedAt() != null) {
            taskProtoBuilder.setModifiedAt(ProtobufHelpers.toTimestamp(taskDTO.getModifiedAt()));
        }

        if (taskDTO.getCurrentProgress() != null) {
            taskProtoBuilder.setCurrentProgress(taskDTO.getCurrentProgress());
        }
        if (taskDTO.getBreakReason() != null) {
            taskProtoBuilder.setBreakReason(taskDTO.getBreakReason());
        }
        if (taskDTO.getExternalTaskId() != null) {
            taskProtoBuilder.setExternalTaskId(taskDTO.getExternalTaskId());
        }
        if (taskDTO.getTaskConfigTemplate() != null) {
            taskProtoBuilder.setTaskConfigTemplate((DynamicConfigProto) ProtoJsonUtils.fromJson(
                    JsonUtils.toJson(taskDTO.getTaskConfigTemplate()), DynamicConfigProto.newBuilder()));
        }
        if (taskDTO.getAutonomyConfig() != null) {
            taskProtoBuilder.setAutonomyConfig((AutonomyConfigProto) ProtoJsonUtils.fromJson(
                    JsonUtils.toJson(taskDTO.getAutonomyConfig()), AutonomyConfigProto.newBuilder()));
        }
        if (taskDTO.getExecutionOrder() != null) {
            taskProtoBuilder.setExecutionOrder(taskDTO.getExecutionOrder());
        }
        if (taskDTO.getDecisionEngineEnabled() != null) {
            taskProtoBuilder.setDecisionEngineEnabled(taskDTO.getDecisionEngineEnabled());
        }
        return taskProtoBuilder;
    }

    private static void mapTaskConfig(TaskProtoDTO.Builder builder, TaskDTO taskDTO) {
        var config = taskDTO.getConfig();
        if (config == null) {
            return;
        }
        String json = JsonUtils.toJson(config);
        switch (config) {
            case WaypointTaskConfig ignored -> builder.setWaypointConfig((WaypointTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, WaypointTaskConfigProto.newBuilder()));
            case DetectTaskConfig ignored -> builder.setDetectConfig((DetectTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, DetectTaskConfigProto.newBuilder()));
            case AreaMappingTaskConfig ignored -> builder.setAreaMappingConfig((AreaMappingTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, AreaMappingTaskConfigProto.newBuilder()));
            case PoiTaskConfig ignored -> builder.setPoiConfig((PoiTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, PoiTaskConfigProto.newBuilder()));
            case FollowTaskConfig ignored -> builder.setFollowConfig((FollowTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, FollowTaskConfigProto.newBuilder()));
            case TrackTaskConfig ignored -> builder.setTrackConfig((TrackTaskConfigProto)
                    ProtoJsonUtils.fromJson(json, TrackTaskConfigProto.newBuilder()));
            default -> throw new IllegalArgumentException("Unsupported task config: " + config.getClass().getName());
        }
    }


    @Override
    public CompletableFuture<TaskResponse> getTask(String taskId) {
        log.info("Getting task: taskId={}", taskId);

        var protoRequest = GetTaskRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();

        return removedLegacyOperation("getTask");
    }

    @Override
    public CompletableFuture<TaskResponse> getTaskByFlightId(String flightId) {
        log.info("Getting task by flightId: flightId={}", flightId);

        var protoRequest = GetTaskByFlightIdRequest.newBuilder()
                .setBase(buildBase())
                .setFlightId(flightId)
                .build();

        return removedLegacyOperation("getTaskByFlightId");
    }

    @Override
    public CompletableFuture<TaskResponse> deleteTask(String taskId) {
        log.info("Deleting task: taskId={}", taskId);

        var protoRequest = DeleteTaskRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();

        return removedLegacyOperation("deleteTask");
    }

    @Override
    public CompletableFuture<TaskResponse> startTask(String taskId) {
        log.info("Starting task: taskId={}", taskId);

        var protoRequest = TaskLifecycleRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();

        return removedLegacyOperation("startTask");
    }

    @Override
    public CompletableFuture<TaskResponse> stopTask(String taskId) {
        log.info("Stopping task: taskId={}", taskId);

        var protoRequest = TaskLifecycleRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();

        return removedLegacyOperation("stopTask");
    }

    @Override
    public CompletableFuture<TaskResponse> pauseTask(String taskId) {
        log.info("Pausing task: taskId={}", taskId);
        var protoRequest = TaskLifecycleRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();
        return removedLegacyOperation("pauseTask");
    }

    @Override
    public CompletableFuture<TaskResponse> resumeTask(String taskId) {
        log.info("Resume task: taskId={}", taskId);
        var protoRequest = TaskLifecycleRequest.newBuilder()
                .setBase(buildBase())
                .setTaskId(taskId)
                .build();

        return removedLegacyOperation("resumeTask");
    }

    @Override
    public CompletableFuture<SchedulerResponse> createScheduler(SchedulerDTO schedulerDTO) {
        log.info("Creating scheduler: name={}", schedulerDTO.getName());
        schedulerDTO.validate();
        var schedulerBuilder = mapSchedulerDtoToProto(SchedulerProtoDTO.newBuilder(), schedulerDTO);
        var protoRequest = CreateSchedulerRequest.newBuilder()
                .setBase(buildBase())
                .setScheduler(schedulerBuilder.build())
                .build();

        return executeAsync(() -> futureStub.createScheduler(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    public static SchedulerProtoDTO.Builder mapSchedulerDtoToProto(SchedulerProtoDTO.Builder newBuilder, SchedulerDTO schedulerDTO) {
        var schedulerBuilder = newBuilder
                .setName(schedulerDTO.getName() != null ? schedulerDTO.getName() : "")
                .setCronExpression(schedulerDTO.getCronExpression() != null ? schedulerDTO.getCronExpression() : "")
                .setClientTimeZone(schedulerDTO.getClientTimeZone() != null ? schedulerDTO.getClientTimeZone() : "");

        if (schedulerDTO.getType() != null) {
            schedulerBuilder.setType(schedulerDTO.getType());
        }
        if (schedulerDTO.getActive() != null) {
            schedulerBuilder.setActive(schedulerDTO.getActive());
        }
        if (schedulerDTO.getAssetSn() != null) schedulerBuilder.setAssetSn(schedulerDTO.getAssetSn());
        if (schedulerDTO.getCommandId() != null) schedulerBuilder.setCommandId(schedulerDTO.getCommandId());
        if (schedulerDTO.getCapabilityPackageId() != null) {
            schedulerBuilder.setApplicationId(schedulerDTO.getCapabilityPackageId());
        }
        if (schedulerDTO.getCapabilityId() != null) schedulerBuilder.setSkillId(schedulerDTO.getCapabilityId());
        if (schedulerDTO.getExecutionParametersJson() != null && !schedulerDTO.getExecutionParametersJson().isBlank()) {
            schedulerBuilder.setExecutionParameters((com.google.protobuf.Struct) ProtoJsonUtils.fromJson(
                    schedulerDTO.getExecutionParametersJson(), com.google.protobuf.Struct.newBuilder()));
        }
        if (schedulerDTO.getAutoStart() != null) schedulerBuilder.setAutoStart(schedulerDTO.getAutoStart());
        if (schedulerDTO.getCreatedAt() != null) {
            schedulerBuilder.setCreatedAt(ProtobufHelpers.toTimestamp(schedulerDTO.getCreatedAt()));
        }
        if (schedulerDTO.getModifiedAt() != null) {
            schedulerBuilder.setModifiedAt(ProtobufHelpers.toTimestamp(schedulerDTO.getModifiedAt()));
        }
        if (schedulerDTO.getCreatedAt() != null) {
            schedulerBuilder.setCreatedAt(ProtobufHelpers.toTimestamp(schedulerDTO.getCreatedAt()));
        }
        if (schedulerDTO.getModifiedAt() != null) {
            schedulerBuilder.setModifiedAt(ProtobufHelpers.toTimestamp(schedulerDTO.getModifiedAt()));
        }

        return schedulerBuilder;
    }

    @Override
    public CompletableFuture<SchedulerResponse> updateScheduler(String schedulerId, SchedulerDTO schedulerDTO) {
        log.info("Updating scheduler: schedulerId={}", schedulerId);
        schedulerDTO.validate();
        var schedulerBuilder = mapSchedulerDtoToProto(SchedulerProtoDTO.newBuilder()
                .setId(schedulerId), schedulerDTO);

        var protoRequest = UpdateSchedulerRequest.newBuilder()
                .setBase(buildBase())
                .setSchedulerId(schedulerId)
                .setScheduler(schedulerBuilder.build())
                .build();

        return executeAsync(() -> futureStub.updateScheduler(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    @Override
    public CompletableFuture<SchedulerResponse> getScheduler(String schedulerId) {
        log.info("Getting scheduler: schedulerId={}", schedulerId);

        var protoRequest = GetSchedulerRequest.newBuilder()
                .setBase(buildBase())
                .setSchedulerId(schedulerId)
                .build();

        return executeAsync(() -> futureStub.getScheduler(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    @Override
    public CompletableFuture<SchedulerResponse> deleteScheduler(String schedulerId) {
        log.info("Deleting scheduler: schedulerId={}", schedulerId);

        var protoRequest = DeleteSchedulerRequest.newBuilder()
                .setBase(buildBase())
                .setSchedulerId(schedulerId)
                .build();

        return executeAsync(() -> futureStub.deleteScheduler(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    @Override
    public CompletableFuture<SchedulerResponse> createSchedulers(List<SchedulerDTO> schedulerDTOS) {
        log.info("Creating schedulers: count={}", schedulerDTOS.size());
        var protoRequest = CreateSchedulersRequest.newBuilder()
                .setBase(buildBase())
                .addAllSchedulers(schedulerDTOS.stream().map(this::toSchedulerProtoDTO)
                .collect(Collectors.toList()))
                .build();
        return executeAsync(() -> futureStub.createSchedulers(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    @Override
    public CompletableFuture<SchedulerResponse> deleteSchedulers(List<String> schedulerIds) {
        log.info("Deleting schedulers of count={}", schedulerIds.size());
        var protoRequest = DeleteSchedulersRequest.newBuilder()
                .setBase(buildBase())
                .addAllSchedulerIds(schedulerIds)
                .build();

        return executeAsync(() -> futureStub.deleteSchedulers(protoRequest))
                .thenApply(this::toSchedulerResponse);
    }

    private RequestBase buildBase() {
        return buildBase(null);
    }

    private RequestBase buildBase(String assetSn) {
        var builder = RequestBase.newBuilder()
                .setTid(UUID.randomUUID().toString())
                .setTimestamp(ProtobufHelpers.now());
        if (assetSn != null && !assetSn.isBlank()) builder.setSn(assetSn);
        return builder.build();
    }

    /**
     * Execute async gRPC call with resilience and timeout.
     * Converts ListenableFuture to CompletableFuture with proper resource management.
     */
    private <T> CompletableFuture<T> executeAsync(java.util.function.Supplier<com.google.common.util.concurrent.ListenableFuture<T>> futureSupplier) {
        int timeout = config != null ? config.getRequestTimeoutSeconds() : 30;

        return resilience.executeWithResilienceAsync(() -> {
            CompletableFuture<T> future = new CompletableFuture<>();

            ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
                future.completeExceptionally(new TimeoutException("Request timed out after " + timeout + "s"));
            }, timeout, TimeUnit.SECONDS);

            // Convert ListenableFuture to CompletableFuture
            com.google.common.util.concurrent.Futures.addCallback(
                    futureSupplier.get(),
                    new com.google.common.util.concurrent.FutureCallback<T>() {
                        @Override
                        public void onSuccess(T result) {
                            timeoutTask.cancel(false);
                            future.complete(result);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            timeoutTask.cancel(false);
                            future.completeExceptionally(t);
                        }
                    },
                    callbackExecutor
            );

            return future;
        });
    }

    private <T> CompletableFuture<T> removedLegacyOperation(String operation) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                operation + " was removed; use capability package and execution APIs"));
    }

    /**
     * Shutdown executors when done.
     * Should be called when closing the client.
     */
    public void shutdown() {
        callbackExecutor.shutdown();
        timeoutScheduler.shutdown();
        try {
            if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                callbackExecutor.shutdownNow();
            }
            if (!timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            callbackExecutor.shutdownNow();
            timeoutScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private SchedulerProtoDTO toSchedulerProtoDTO(SchedulerDTO schedulerDTO) {
        return mapSchedulerDtoToProto(SchedulerProtoDTO.newBuilder(), schedulerDTO).build();
    }


    private MissionResponse toMissionResponse(com.zqnt.utils.mission.proto.MissionResponse proto) {
        var builder = MissionResponse.builder()
                .success(!proto.getHasErrors())
                .tid(proto.getTid())
                .missionId(proto.getMissionId())
                .timestamp(ProtobufHelpers.toLocalDateTime(proto.getTimestamp()));

        if (proto.hasError()) {
            builder.error(MissionResponse.ErrorInfo.builder()
                    .errorCode(proto.getError().getErrorCode().name())
                    .errorMessage(proto.getError().getErrorMessage())
                    .timestamp(ProtobufHelpers.toLocalDateTime(proto.getError().getTimestamp()))
                    .build());
        }

        if (proto.hasProgress()) {
            builder.progress(MissionResponse.ProgressInfo.builder()
                    .progress(proto.getProgress().getProgress())
                    .state(proto.getProgress().getState())
                    .leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds())
                    .build());
        }

        if (proto.hasMission()) {
            var json = ProtoJsonUtils.toJson(proto.getMission());
            var missionDTO = JsonUtils.fromJson(json, MissionDTO.class);
            applyTimestamps(proto.getMission(), missionDTO);

            builder.missionData(missionDTO);
        }

        return builder.build();
    }

    private TaskResponse toTaskResponse(com.zqnt.utils.mission.proto.TaskResponse proto) {
        var builder = TaskResponse.builder()
                .success(!proto.getHasErrors())
                .tid(proto.getTid())
                .taskId(proto.getTaskId())
                .timestamp(ProtobufHelpers.toLocalDateTime(proto.getTimestamp()));

        if (proto.hasError()) {
            builder.error(TaskResponse.ErrorInfo.builder()
                    .errorCode(proto.getError().getErrorCode().name())
                    .errorMessage(proto.getError().getErrorMessage())
                    .timestamp(ProtobufHelpers.toLocalDateTime(proto.getError().getTimestamp()))
                    .build());
        }

        if (proto.hasProgress()) {
            builder.progress(TaskResponse.ProgressInfo.builder()
                    .progress(proto.getProgress().getProgress())
                    .state(proto.getProgress().getState())
                    .leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds())
                    .build());
        }

        if (proto.hasTask()) {
            builder.taskData(mapTaskProtoToDto(proto.getTask()));
        }

        return builder.build();
    }

    /**
     * Re-reads a mission's timestamps straight off the proto instead of trusting the JSON
     * round-trip above.
     *
     * <p>Outbound, a {@code LocalDateTime} is converted with {@link ProtobufHelpers#toTimestamp}
     * which applies {@link java.time.ZoneId#systemDefault()}. Inbound, {@code ProtoJsonUtils}
     * renders a proto {@code Timestamp} as an RFC-3339 string in UTC, and Jackson parses that into
     * a {@code LocalDateTime} by discarding the offset rather than converting it. The offset was
     * therefore applied on the way out and dropped on the way back, so every timestamp returned by
     * the server came back shifted by the caller's own UTC offset -- a mission created with
     * {@code startDate=13:56} from a UTC+2 machine came back as {@code 11:56}, and a scheduler
     * built from it would have fired two hours early.
     *
     * <p>Using {@link ProtobufHelpers#toLocalDateTime} here makes the two directions symmetric.
     */
    private static void applyTimestamps(MissionProtoDTO proto, MissionDTO dto) {
        if (proto.hasStartDate()) {
            dto.setStartDate(ProtobufHelpers.toLocalDateTime(proto.getStartDate()));
        }
        if (proto.hasEndDate()) {
            dto.setEndDate(ProtobufHelpers.toLocalDateTime(proto.getEndDate()));
        }
        if (proto.hasCreatedAt()) {
            dto.setCreatedAt(ProtobufHelpers.toLocalDateTime(proto.getCreatedAt()));
        }
        if (proto.hasModifiedAt()) {
            dto.setModifiedAt(ProtobufHelpers.toLocalDateTime(proto.getModifiedAt()));
        }
    }

    public static TaskDTO mapTaskProtoToDto(TaskProtoDTO proto) {
        TaskDTO task = JsonUtils.fromJson(ProtoJsonUtils.toJson(proto), TaskDTO.class);
        if (proto.hasCreatedAt()) {
            task.setCreatedAt(ProtobufHelpers.toLocalDateTime(proto.getCreatedAt()));
        }
        if (proto.hasModifiedAt()) {
            task.setModifiedAt(ProtobufHelpers.toLocalDateTime(proto.getModifiedAt()));
        }
        if (proto.hasWaypointConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getWaypointConfig()), WaypointTaskConfig.class));
        } else if (proto.hasDetectConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getDetectConfig()), DetectTaskConfig.class));
        } else if (proto.hasAreaMappingConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getAreaMappingConfig()), AreaMappingTaskConfig.class));
        } else if (proto.hasPoiConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getPoiConfig()), PoiTaskConfig.class));
        } else if (proto.hasFollowConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getFollowConfig()), FollowTaskConfig.class));
        } else if (proto.hasTrackConfig()) {
            task.setConfig(JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getTrackConfig()), TrackTaskConfig.class));
        }
        return task;
    }

    private SchedulerResponse toSchedulerResponse(com.zqnt.utils.mission.proto.SchedulerResponse proto) {
        var builder = SchedulerResponse.builder()
                .success(!proto.getHasErrors())
                .tid(proto.getTid())
                .schedulerId(proto.getSchedulerId())
                .timestamp(ProtobufHelpers.toLocalDateTime(proto.getTimestamp()));

        if (proto.hasError()) {
            builder.error(SchedulerResponse.ErrorInfo.builder()
                    .errorCode(proto.getError().getErrorCode().name())
                    .errorMessage(proto.getError().getErrorMessage())
                    .timestamp(ProtobufHelpers.toLocalDateTime(proto.getError().getTimestamp()))
                    .build());
        }

        if (proto.hasProgress()) {
            builder.progress(SchedulerResponse.ProgressInfo.builder()
                    .progress(proto.getProgress().getProgress())
                    .state(proto.getProgress().getState())
                    .leftTimeInSeconds(proto.getProgress().getLeftTimeInSeconds())
                    .build());
        }

        if (proto.hasScheduler()) {
            var json = ProtoJsonUtils.toJson(proto.getScheduler());
            builder.schedulerData(JsonUtils.fromJson(json, SchedulerDTO.class));
        }

        return builder.build();
    }
}
