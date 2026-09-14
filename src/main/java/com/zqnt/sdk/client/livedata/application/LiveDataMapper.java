package com.zqnt.sdk.client.livedata.application;

import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.zqnt.sdk.client.livedata.domains.*;
import com.zqnt.sdk.client.livedata.domains.StreamTelemetryRequest;
import com.zqnt.utils.common.proto.GlobalErrorMessage;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.devicecontrol.proto.ChangeCameraLensRequest;
import com.zqnt.utils.devicecontrol.proto.ChangeCameraZoomRequest;
import com.zqnt.utils.devicecontrol.proto.LiveDataServiceCommand;
import com.zqnt.utils.edge.sdk.domains.TelemetryData;
import com.zqnt.utils.events.proto.*;
import com.zqnt.utils.livedata.proto.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class LiveDataMapper {

    public static final LiveDataMapper INSTANCE = new LiveDataMapper();


    private LiveDataMapper() {

    }

    /**
     * Maps StreamTelemetryRequest POJO to proto LiveDataStreamTelemetryRequest
     */
    public com.zqnt.utils.livedata.proto.StreamTelemetryRequest toProtoRequest(StreamTelemetryRequest request) {
        if (request == null) {
            return null;
        }

        LiveDataServiceCommand command = request.getCommand() != null
                ? request.getCommand()
                : LiveDataServiceCommand.LIVE_DATA_COMMAND_START_TELEMETRY_STREAM;

        var builder = com.zqnt.utils.livedata.proto.StreamTelemetryRequest.newBuilder()
                .setBase(requestBase(request.getSn(), request.getTid(), false, false))
                .setCommand(command);

        if (request.getFrequencyMs() > 0) {
            builder.setFrequencyMs(request.getFrequencyMs());
        }

        if (request.getDuration() > 0) {
            builder.setDuration(request.getDuration());
        }

        return builder.build();
    }

    /**
     * Maps proto LiveDataTelemetryResponse to StreamTelemetryResponse POJO
     */
    public StreamTelemetryResponse fromProtoResponse(LiveDataTelemetryResponse protoResponse) {
        if (protoResponse == null) {
            return null;
        }

        StreamTelemetryResponse response = new StreamTelemetryResponse();
        response.setTid(protoResponse.getTid());
        response.setTimestamp(timestampToInstant(protoResponse.getTimestamp()));
        response.setHasErrors(protoResponse.getHasErrors());
        response.setSn(protoResponse.getSn());

        response.setAssetId(nullable(protoResponse.hasAssetId(), protoResponse.getAssetId()));

        // Map oneof telemetry fields
        switch (protoResponse.getTelemetryCase()) {
            case DATA:
                response.setTelemetry(mapTelemetry(protoResponse.getData()));
                response.setEventType(StreamTelemetryResponse.StreamEventType.TELEMETRY);
                break;
            case ERROR:
                response.setError(mapErrorInfo(protoResponse.getError()));
                response.setEventType(StreamTelemetryResponse.StreamEventType.ERROR);
                break;
            case STREAM_HEARTBEAT:
                response.setStreamHeartbeat(StreamTelemetryResponse.StreamHeartbeat.builder()
                        .timestamp(timestampToInstant(protoResponse.getStreamHeartbeat().getTimestamp()))
                        .build());
                response.setEventType(StreamTelemetryResponse.StreamEventType.HEARTBEAT);
                break;
            case SOURCE_STATUS:
                var protoStatus = protoResponse.getSourceStatus();
                response.setSourceStatus(StreamTelemetryResponse.SourceStatus.builder()
                        .sn(protoStatus.getSn())
                        .state(protoStatus.getState())
                        .observedAt(timestampToInstant(protoStatus.getObservedAt()))
                        .lastTelemetryAt(protoStatus.hasLastTelemetryAt()
                                ? timestampToInstant(protoStatus.getLastTelemetryAt())
                                : null)
                        .build());
                response.setEventType(StreamTelemetryResponse.StreamEventType.SOURCE_STATUS);
                break;
            case LIVE_STREAM_STATE:
            case TELEMETRY_NOT_SET:
                response.setEventType(StreamTelemetryResponse.StreamEventType.UNKNOWN);
                break;
        }

        return response;
    }

    /**
     * Maps StreamNotificationRequest POJO to proto LiveDataStreamNotificationsRequest
     */
    public StreamNotificationsRequest toProtoStreamNotificationsRequest(StreamNotificationRequest request) {
        if (request == null) {
            return null;
        }

        var builder = StreamNotificationsRequest.newBuilder()
                .setBase(requestBase(request.getSn(), request.getTid(), true, true));

        if (request.getEventTypes() != null && !request.getEventTypes().isEmpty()) {
            builder.addAllEventTypes(request.getEventTypes());
        }

        return builder.build();
    }

    /**
     * Maps proto LiveDataNotificationResponse to StreamNotificationResponse POJO
     */
    public StreamNotificationResponse fromProtoNotificationResponse(NotificationResponse protoResponse) {
        if (protoResponse == null) {
            return null;
        }

        var response = StreamNotificationResponse.builder()
                .tid(protoResponse.getTid())
                .timestamp(timestampToInstant(protoResponse.getTimestamp()))
                .hasErrors(protoResponse.getHasErrors())
                .sn(protoResponse.getSn())
                .assetId(nullable(protoResponse.hasAssetId(), protoResponse.getAssetId()))
                .build();

        if (protoResponse.hasEvent()) {
            NotificationEvent event = protoResponse.getEvent();
            switch (event.getEventCase()) {
                case ASSET_STATUS -> response.setAssetStatus(mapAssetStatusEvent(event.getAssetStatus()));
                case MISSION -> response.setMissionEvent(mapMissionEvent(event.getMission()));
                case SKILL_EXECUTION -> response.setSkillExecutionEvent(
                        mapSkillExecutionEvent(event.getSkillExecution()));
                case COMMAND_EXECUTION -> response.setCommandExecutionEvent(
                        mapCommandExecutionEvent(event.getCommandExecution()));
                case ERROR -> response.setError(mapNotificationErrorInfo(event.getError()));
                case ASSET_RUNTIME -> {
                    // Runtime changes are consumed through the runtime query API.
                }
                case EVENT_NOT_SET -> {
                    // No event set
                }
            }
        }

        response.setEventType(resolveNotificationEventType(protoResponse));
        return response;
    }

    /**
     * Maps the unified telemetry proto to the corresponding SDK domain.
     */
    private TelemetryData mapTelemetry(Telemetry proto) {
        if (proto == null) {
            return null;
        }

        TelemetryData data = TelemetryData.builder()
                .id(proto.getId())
                .timestamp(timestampToLocalDateTime(proto.getTimestamp()))
                .sn(proto.getSn())
                .latitude(nullable(proto.hasLatitude(), proto.getLatitude()))
                .longitude(nullable(proto.hasLongitude(), proto.getLongitude()))
                .absoluteAltitude(nullable(proto.hasAbsoluteAltitude(), proto.getAbsoluteAltitude()))
                .relativeAltitude(nullable(proto.hasRelativeAltitude(), proto.getRelativeAltitude()))
                .windSpeed(nullable(proto.hasWindSpeed(), proto.getWindSpeed()))
                .heading(nullable(proto.hasHeading(), proto.getHeading()))
                .build();

        if (proto.hasAsset()) {
            data.setAsset(mapAssetDetails(proto.getAsset()));
        } else if (proto.hasSubAsset()) {
            data.setSubAsset(mapSubAssetDetails(proto.getSubAsset()));
        }
        return data;
    }

    private TelemetryData.AssetDetails mapAssetDetails(AssetTelemetryDetails proto) {
        var data = TelemetryData.AssetDetails.builder()
                .environmentTemp(nullable(proto.hasEnvironmentTemp(), proto.getEnvironmentTemp()))
                .insideTemp(nullable(proto.hasInsideTemp(), proto.getInsideTemp()))
                .humidity(nullable(proto.hasHumidity(), proto.getHumidity()))
                .mode(nullable(proto.hasMode(), proto.getMode()))
                .rainfall(nullable(proto.hasRainfall(), proto.getRainfall()))
                .subAssetAtHome(nullable(proto.hasSubAssetAtHome(), proto.getSubAssetAtHome()))
                .subAssetCharging(nullable(proto.hasSubAssetCharging(), proto.getSubAssetCharging()))
                .subAssetPercentage(nullable(proto.hasSubAssetPercentage(), proto.getSubAssetPercentage()))
                .debugModeOpen(nullable(proto.hasDebugModeOpen(), proto.getDebugModeOpen()))
                .hasActiveManualControlSession(nullable(proto.hasHasActiveManualControlSession(), proto.getHasActiveManualControlSession()))
                .coverState(nullable(proto.hasCoverState(), proto.getCoverState()))
                .workingVoltage(nullable(proto.hasWorkingVoltage(), proto.getWorkingVoltage()))
                .workingCurrent(nullable(proto.hasWorkingCurrent(), proto.getWorkingCurrent()))
                .supplyVoltage(nullable(proto.hasSupplyVoltage(), proto.getSupplyVoltage()))
                .positionValid(nullable(proto.hasPositionValid(), proto.getPositionValid()))
                .manualControlState(nullable(proto.hasManualControlState(), proto.getManualControlState()))
                .build();

        if (proto.hasSubAssetInformation()) {
            data.setSubAssetInformation(TelemetryData.SubAssetInformation.builder()
                    .sn(proto.getSubAssetInformation().getSn())
                    .model(proto.getSubAssetInformation().getModel())
                    .paired(proto.getSubAssetInformation().getPaired())
                    .online(proto.getSubAssetInformation().getOnline())
                    .build());
        }

        if (proto.hasNetworkInformation()) {
            data.setNetworkInformation(TelemetryData.NetworkInformation.builder()
                    .type(proto.getNetworkInformation().getType())
                    .rate(proto.getNetworkInformation().getRate())
                    .quality(proto.getNetworkInformation().getQuality())
                    .build());
        }

        if (proto.hasAirConditioner()) {
            data.setAirConditioner(TelemetryData.AirConditioner.builder()
                    .state(proto.getAirConditioner().getState())
                    .switchTime(proto.getAirConditioner().getSwitchTime())
                    .build());
        }
        if (proto.hasWirelessLink()) {
            data.setWirelessLink(TelemetryData.WirelessLinkInformation.builder()
                    .sdrFreqBand(proto.getWirelessLink().getSdrFreqBand())
                    .sdrLinkState(proto.getWirelessLink().getSdrLinkState())
                    .sdrQuality(proto.getWirelessLink().getSdrQuality())
                    .fourthGenerationFreqBand(proto.getWirelessLink().getFourthGenerationFreqBand())
                    .fourthGenerationGndQuality(proto.getWirelessLink().getFourthGenerationGndQuality())
                    .fourthGenerationLinkState(proto.getWirelessLink().getFourthGenerationLinkState())
                    .fourthGenerationUavQuality(proto.getWirelessLink().getFourthGenerationUavQuality())
                    .linkWorkmode(proto.getWirelessLink().getLinkWorkmode())
                    .dongleNumber(proto.getWirelessLink().getDongleNumber())
                    .fourthGenerationQuality(proto.getWirelessLink().getFourthGenerationQuality())
                    .build());
        }

        if (proto.hasSdrState()) {
            data.setSdrState(TelemetryData.SdrState.builder()
                    .downQuality(proto.getSdrState().getDownQuality())
                    .upQuality(proto.getSdrState().getUpQuality())
                    .frequencyBand(proto.getSdrState().getFrequencyBand())
                    .build());
        }

        if (proto.hasPositionState()) {
            data.setPositionState(TelemetryData.PositionState.builder()
                    .gpsNumber(proto.getPositionState().getGpsNumber())
                    .rtkNumber(proto.getPositionState().getRtkNumber())
                    .quality(proto.getPositionState().getQuality())
                    .build());
        }

        return data;
    }

    /**
     * Maps sub-asset-specific telemetry details, including component telemetry.
     */
    private TelemetryData.SubAssetDetails mapSubAssetDetails(SubAssetTelemetryDetails proto) {
        TelemetryData.SubAssetDetails data = TelemetryData.SubAssetDetails.builder()
                .horizontalSpeed(nullable(proto.hasHorizontalSpeed(), proto.getHorizontalSpeed()))
                .verticalSpeed(nullable(proto.hasVerticalSpeed(), proto.getVerticalSpeed()))
                .windDirection(nullable(proto.hasWindDirection(), proto.getWindDirection()))
                .gear(nullable(proto.hasGear(), proto.getGear()))
                .heightLimit(nullable(proto.hasHeightLimit(), proto.getHeightLimit()))
                .homeDistance(nullable(proto.hasHomeDistance(), proto.getHomeDistance()))
                .totalMovementDistance(nullable(proto.hasTotalMovementDistance(), proto.getTotalMovementDistance()))
                .totalMovementTime(nullable(proto.hasTotalMovementTime(), proto.getTotalMovementTime()))
                .mode(nullable(proto.hasMode(), proto.getMode()))
                .country(nullable(proto.hasCountry(), proto.getCountry()))
                .build();

        // Map battery information
        if (proto.hasBatteryInformation()) {
            data.setBatteryInformation(TelemetryData.BatteryInformation.builder()
                    .percentage(proto.getBatteryInformation().getPercentage())
                    .remainingTime(proto.getBatteryInformation().getRemainingTime())
                    .returnToHomePower(proto.getBatteryInformation().getReturnToHomePower())
                    .build());
        }

        // Map payload telemetry
        if (proto.hasPayloadTelemetry()) {
            var payloadProto = proto.getPayloadTelemetry();
            var payload = TelemetryData.PayloadTelemetry.builder()
                    .id(payloadProto.getId())
                    .timestamp(timestampToLocalDateTime(payloadProto.getTimestamp()))
                    .name(payloadProto.getName())
                    .build();

            if (payloadProto.hasCameraData()) {
                payload.setCameraData(mapCameraData(payloadProto.getCameraData()));
            }

            if (payloadProto.hasRangeFinderData()) {
                var rangeFinderData = payloadProto.getRangeFinderData();
                payload.setRangeFinderData(mapRangeFinderData(rangeFinderData));
            }

            if (payloadProto.hasSensorData()) {
                payload.setSensorData(mapSensorData(payloadProto.getSensorData()));
            }

            data.setPayloadTelemetry(payload);
        }

        data.setComponentTelemetry(proto.getComponentTelemetryList().stream()
                .map(this::mapComponentTelemetry)
                .toList());

        return data;
    }

    private TelemetryData.ComponentTelemetryData mapComponentTelemetry(ComponentTelemetry proto) {
        var builder = TelemetryData.ComponentTelemetryData.builder()
                .componentId(proto.getComponentId())
                .externalId(nullable(proto.hasExternalId(), proto.getExternalId()))
                .kind(nullable(proto.hasKind(), proto.getKind()))
                .timestamp(proto.hasTimestamp() ? timestampToLocalDateTime(proto.getTimestamp()) : null)
                .attributes(proto.hasAttributes() ? structToMap(proto.getAttributes().getFieldsMap()) : Map.of());
        if (proto.hasCameraData()) builder.cameraData(mapCameraData(proto.getCameraData()));
        if (proto.hasRangeFinderData()) builder.rangeFinderData(mapRangeFinderData(proto.getRangeFinderData()));
        if (proto.hasSensorData()) builder.sensorData(mapSensorData(proto.getSensorData()));
        return builder.build();
    }

    private TelemetryData.CameraData mapCameraData(PayloadTelemetry.CameraData proto) {
        return TelemetryData.CameraData.builder()
                .currentLens(proto.getCurrentLens())
                .gimbalPitch(proto.getGimbalPitch())
                .gimbalYaw(proto.getGimbalYaw())
                .gimbalRoll(proto.getGimbalRoll())
                .zoomFactor(proto.getZoomFactor())
                .build();
    }

    private TelemetryData.RangeFinderData mapRangeFinderData(PayloadTelemetry.RangeFinderData proto) {
        return TelemetryData.RangeFinderData.builder()
                .targetLatitude(proto.getTargetLatitude())
                .targetLongitude(proto.getTargetLongitude())
                .targetDistance(proto.getTargetDistance())
                .targetAltitude(proto.getTargetAltitude())
                .build();
    }

    private TelemetryData.SensorData mapSensorData(PayloadTelemetry.SensorData proto) {
        return TelemetryData.SensorData.builder()
                .targetTemperature(proto.getTargetTemperature())
                .build();
    }

    private Map<String, Object> structToMap(Map<String, Value> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach((key, value) -> result.put(key, valueToObject(value)));
        return result;
    }

    private Object valueToObject(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> structToMap(value.getStructValue().getFieldsMap());
            case LIST_VALUE -> value.getListValue().getValuesList().stream().map(this::valueToObject).toList();
        };
    }

    /**
     * Maps proto GlobalErrorMessage to ErrorInfo POJO
     */
    private StreamTelemetryResponse.ErrorInfo mapErrorInfo(GlobalErrorMessage proto) {
        if (proto == null) {
            return null;
        }

        return StreamTelemetryResponse.ErrorInfo.builder()
                .errorCode(proto.getErrorCode())
                .errorMessage(proto.getErrorMessage())
                .timestamp(timestampToLocalDateTime(proto.getTimestamp()))
                .build();
    }

    private StreamNotificationResponse.AssetStatusEvent mapAssetStatusEvent(AssetStatusEvent proto) {
        if (proto == null) {
            return null;
        }

        return StreamNotificationResponse.AssetStatusEvent.builder()
                .sn(proto.getSn())
                .assetId(nullable(proto.hasAssetId(), proto.getAssetId()))
                .online(proto.getOnline())
                .message(nullable(proto.hasMessage(), proto.getMessage()))
                .build();
    }

    private StreamNotificationResponse.MissionEvent mapMissionEvent(MissionEvent proto) {
        if (proto == null) {
            return null;
        }

        return StreamNotificationResponse.MissionEvent.builder()
                .missionId(proto.getMissionId())
                .missionType(proto.getMissionType())
                .status(proto.getStatus())
                .message(nullable(proto.hasMessage(), proto.getMessage()))
                .build();
    }

    private StreamNotificationResponse.SkillExecutionEvent mapSkillExecutionEvent(
            com.zqnt.utils.execution.proto.SkillExecutionEventProto proto) {
        return StreamNotificationResponse.SkillExecutionEvent.builder()
                .eventId(proto.getEventId())
                .executionId(proto.getExecutionId())
                .assetSn(proto.getAssetSn())
                .type(proto.getType())
                .executionStatus(proto.getExecutionStatus())
                .nodeId(nullable(proto.hasNodeId(), proto.getNodeId()))
                .nodeStatus(proto.hasNodeStatus() ? proto.getNodeStatus() : null)
                .progress(nullable(proto.hasProgress(), proto.getProgress()))
                .occurredAt(timestampToInstant(proto.getOccurredAt()))
                .error(proto.hasError() ? mapNotificationErrorInfo(proto.getError()) : null)
                .data(structToMap(proto.getData().getFieldsMap()))
                .build();
    }

    private StreamNotificationResponse.CommandExecutionEvent mapCommandExecutionEvent(
            com.zqnt.utils.events.proto.CommandExecutionEvent proto) {
        return StreamNotificationResponse.CommandExecutionEvent.builder()
                .externalExecutionId(proto.getExternalExecutionId())
                .commandId(nullable(proto.hasCommandId(), proto.getCommandId()))
                .status(proto.getStatus())
                .progress(nullable(proto.hasProgress(), proto.getProgress()))
                .message(nullable(proto.hasMessage(), proto.getMessage()))
                .output(structToMap(proto.getOutput().getFieldsMap()))
                .error(proto.hasError() ? mapNotificationErrorInfo(proto.getError()) : null)
                .occurredAt(timestampToInstant(proto.getOccurredAt()))
                .assetSn(proto.getAssetSn())
                .build();
    }

    private StreamNotificationResponse.ErrorInfo mapNotificationErrorInfo(GlobalErrorMessage proto) {
        if (proto == null) {
            return null;
        }

        return StreamNotificationResponse.ErrorInfo.builder()
                .errorCode(proto.getErrorCode())
                .errorMessage(proto.getErrorMessage())
                .timestamp(timestampToLocalDateTime(proto.getTimestamp()))
                .build();
    }

    private NotificationEventType resolveNotificationEventType(NotificationResponse protoResponse) {
        if (!protoResponse.hasEvent()) {
            return null;
        }
        return switch (protoResponse.getEvent().getEventCase()) {
            case ASSET_STATUS -> NotificationEventType.NOTIFICATION_EVENT_ASSET_STATUS;
            case MISSION -> NotificationEventType.NOTIFICATION_EVENT_MISSION;
            case ASSET_RUNTIME -> NotificationEventType.NOTIFICATION_EVENT_ASSET_RUNTIME;
            case SKILL_EXECUTION -> NotificationEventType.NOTIFICATION_EVENT_CAPABILITY_EXECUTION;
            case COMMAND_EXECUTION -> NotificationEventType.NOTIFICATION_EVENT_COMMAND_EXECUTION;
            case ERROR, EVENT_NOT_SET -> null;
        };
    }

    /**
     * Converts protobuf Timestamp to Java Instant
     */
    private Instant timestampToInstant(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * Converts protobuf Timestamp to LocalDateTime
     */
    private LocalDateTime timestampToLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * Maps LiveDataStartLiveStreamRequest POJO to proto
     */
    public com.zqnt.utils.devicecontrol.proto.LiveStreamStartCommandRequest toProtoStartLiveStreamRequest(
            LiveDataStartLiveStreamRequest request) {
        if (request == null) {
            return null;
        }

        var requestBuilder = com.zqnt.utils.devicecontrol.proto.LiveStreamStartCommandPayload.newBuilder()
                .setVideoId(request.getVideoId())
                .setStreamServer(request.getStreamServer())
                .setStreamType(request.getStreamType())
                .setAssetType(request.getAssetType());

        return com.zqnt.utils.devicecontrol.proto.LiveStreamStartCommandRequest.newBuilder()
                .setBase(requestBase(request.getSn(), null, true, false))
                .setRequest(requestBuilder.build())
                .build();
    }

    /**
     * Maps LiveDataStopLiveStreamRequest POJO to proto
     */
    public com.zqnt.utils.devicecontrol.proto.LiveStreamStopCommandRequest toProtoStopLiveStreamRequest(
            LiveDataStopLiveStreamRequest request) {
        if (request == null) {
            return null;
        }

        var requestBuilder = com.zqnt.utils.devicecontrol.proto.LiveStreamStopCommandPayload.newBuilder()
                .setVideoId(request.getVideoId());

        return com.zqnt.utils.devicecontrol.proto.LiveStreamStopCommandRequest.newBuilder()
                .setBase(requestBase(request.getSn(), request.getTid(), true, false))
                .setRequest(requestBuilder.build())
                .build();
    }

    public com.zqnt.utils.devicecontrol.proto.ChangeCameraLensCommandRequest toProtoChangeLensRequest(ChangeLensRequest request) {
        return com.zqnt.utils.devicecontrol.proto.ChangeCameraLensCommandRequest.newBuilder()
                .setBase(requestBase(request.getSn(), request.getTid(), false, true))
                .setRequest(ChangeCameraLensRequest.newBuilder()
                        .setLens(request.getLens())
                        .build())
                .build();
    }


    public com.zqnt.utils.devicecontrol.proto.ChangeCameraZoomCommandRequest toProtoChangeZoomRequest(ChangeZoomRequest request) {
        return com.zqnt.utils.devicecontrol.proto.ChangeCameraZoomCommandRequest.newBuilder()
                .setBase(requestBase(request.getSn(), request.getTid(), false, true))
                .setRequest(ChangeCameraZoomRequest.newBuilder()
                        .setLens(request.getLens())
                        .setZoom(request.getZoom())
                        .build())
                .build();
    }

    private static <T> T nullable(boolean present, T value) {
        return present ? value : null;
    }

    private static RequestBase requestBase(String sn, String tid, boolean generateTidIfMissing, boolean includeTimestamp) {
        var builder = RequestBase.newBuilder()
                .setSn(sn)
                .setTid(generateTidIfMissing && tid == null ? UUID.randomUUID().toString() : tid);

        if (includeTimestamp) {
            builder.setTimestamp(ProtobufHelpers.now());
        }

        return builder.build();
    }
}
