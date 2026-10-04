package com.zqnt.sdk.client.livedata.application;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.zqnt.sdk.client.livedata.domains.StreamNotificationResponse;
import com.zqnt.sdk.client.livedata.domains.StreamTelemetryResponse;
import com.zqnt.utils.events.proto.MissionEvent;
import com.zqnt.utils.events.proto.NotificationEvent;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.events.proto.NotificationResponse;
import com.zqnt.utils.execution.proto.SkillExecutionEventProto;
import com.zqnt.utils.execution.proto.SkillExecutionEventTypeProto;
import com.zqnt.utils.execution.proto.SkillExecutionStatusProto;
import com.zqnt.utils.livedata.proto.*;
import com.zqnt.utils.mission.proto.MissionStatus;
import com.zqnt.utils.mission.proto.MissionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveDataMapperTest {

    private static final Timestamp NOW = Timestamp.newBuilder().setSeconds(123).setNanos(456).build();

    @Test
    void mapsStreamHeartbeatSeparatelyFromTelemetry() {
        LiveDataTelemetryResponse proto = LiveDataTelemetryResponse.newBuilder()
                .setSn("*")
                .setTimestamp(NOW)
                .setStreamHeartbeat(LiveDataStreamHeartbeat.newBuilder().setTimestamp(NOW))
                .build();

        StreamTelemetryResponse mapped = LiveDataMapper.INSTANCE.fromProtoResponse(proto);

        assertEquals(StreamTelemetryResponse.StreamEventType.HEARTBEAT, mapped.getEventType());
        assertEquals(123, mapped.getStreamHeartbeat().getTimestamp().getEpochSecond());
        assertNull(mapped.getTelemetry());
    }

    @Test
    void mapsNoDataSourceStatusWithoutInventingLastTelemetryTimestamp() {
        TelemetrySourceStatus status = TelemetrySourceStatus.newBuilder()
                .setSn("EDGE-1")
                .setState(TelemetrySourceState.TELEMETRY_SOURCE_STATE_NO_DATA)
                .setObservedAt(NOW)
                .build();
        LiveDataTelemetryResponse proto = LiveDataTelemetryResponse.newBuilder()
                .setSn("EDGE-1")
                .setTimestamp(NOW)
                .setSourceStatus(status)
                .build();

        StreamTelemetryResponse mapped = LiveDataMapper.INSTANCE.fromProtoResponse(proto);

        assertEquals(StreamTelemetryResponse.StreamEventType.SOURCE_STATUS, mapped.getEventType());
        assertEquals(TelemetrySourceState.TELEMETRY_SOURCE_STATE_NO_DATA, mapped.getSourceStatus().getState());
        assertNull(mapped.getSourceStatus().getLastTelemetryAt());
    }

    @Test
    void mapsUnifiedSubAssetAndComponentTelemetry() {
        ComponentTelemetry component = ComponentTelemetry.newBuilder()
                .setComponentId("camera-1")
                .setKind("camera")
                .setTimestamp(NOW)
                .setAttributes(Struct.newBuilder()
                        .putFields("temperature", Value.newBuilder().setNumberValue(42.5).build()))
                .build();
        Telemetry telemetry = Telemetry.newBuilder()
                .setId("telemetry-1")
                .setSn("UAV-1")
                .setTimestamp(NOW)
                .setLatitude(47.1)
                .setSubAsset(SubAssetTelemetryDetails.newBuilder()
                        .setHorizontalSpeed(12.5f)
                        .addComponentTelemetry(component))
                .build();
        LiveDataTelemetryResponse proto = LiveDataTelemetryResponse.newBuilder()
                .setSn("UAV-1")
                .setTimestamp(NOW)
                .setData(telemetry)
                .build();

        StreamTelemetryResponse mapped = LiveDataMapper.INSTANCE.fromProtoResponse(proto);

        assertEquals(StreamTelemetryResponse.StreamEventType.TELEMETRY, mapped.getEventType());
        assertEquals("UAV-1", mapped.getTelemetry().getSn());
        assertEquals(47.1, mapped.getTelemetry().getLatitude());
        assertNotNull(mapped.getTelemetry().getSubAsset());
        assertNull(mapped.getTelemetry().getAsset());
        assertEquals(12.5f, mapped.getTelemetry().getSubAsset().getHorizontalSpeed());
        assertEquals(42.5, mapped.getTelemetry().getSubAsset().getComponentTelemetry()
                .getFirst().getAttributes().get("temperature"));
    }

    @Test
    void mapsMissionNotification() {
        NotificationResponse proto = NotificationResponse.newBuilder()
                .setSn("UAV-1")
                .setTimestamp(NOW)
                .setEvent(NotificationEvent.newBuilder()
                        .setMission(MissionEvent.newBuilder()
                                .setMissionId("mission-1")
                                .setMissionType(MissionType.MISSION_TYPE_ROUTE_INSPECTION)
                                .setStatus(MissionStatus.MISSION_STATUS_ACTIVE)))
                .build();

        StreamNotificationResponse mapped = LiveDataMapper.INSTANCE.fromProtoNotificationResponse(proto);

        assertEquals(NotificationEventType.NOTIFICATION_EVENT_MISSION, mapped.getEventType());
        assertEquals("mission-1", mapped.getMissionEvent().getMissionId());
        assertEquals(MissionStatus.MISSION_STATUS_ACTIVE, mapped.getMissionEvent().getStatus());
    }

    @Test
    void mapsSkillExecutionProgressNotification() {
        NotificationResponse proto = NotificationResponse.newBuilder()
                .setSn("UAV-1")
                .setTimestamp(NOW)
                .setEvent(NotificationEvent.newBuilder().setSkillExecution(
                        SkillExecutionEventProto.newBuilder()
                                .setEventId("event-1")
                                .setExecutionId("execution-1")
                                .setAssetSn("UAV-1")
                                .setType(SkillExecutionEventTypeProto
                                        .SKILL_EXECUTION_EVENT_TYPE_NODE_PROGRESS)
                                .setExecutionStatus(SkillExecutionStatusProto
                                        .SKILL_EXECUTION_STATUS_RUNNING)
                                .setNodeId("takeoff")
                                .setProgress(42.5f)
                                .setOccurredAt(NOW)))
                .build();

        StreamNotificationResponse mapped = LiveDataMapper.INSTANCE.fromProtoNotificationResponse(proto);

        assertEquals(NotificationEventType.NOTIFICATION_EVENT_CAPABILITY_EXECUTION, mapped.getEventType());
        assertEquals("execution-1", mapped.getSkillExecutionEvent().getExecutionId());
        assertEquals("takeoff", mapped.getSkillExecutionEvent().getNodeId());
        assertEquals(42.5f, mapped.getSkillExecutionEvent().getProgress());
    }
}
