package com.zqnt.sdk.client.connector.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StoreNotificationRequest {
    /** @see #COMMAND_EXECUTION for the vendor-neutral event that replaced the legacy TASK notification. */
    public enum EventType { ASSET_STATUS, MISSION, COMMAND_EXECUTION }
    public enum Severity { INFO, WARN, CRITICAL }
    public enum CommandExecutionStatus {
        COMMAND_EXECUTION_STATUS_UNSPECIFIED,
        COMMAND_EXECUTION_STATUS_ACCEPTED,
        COMMAND_EXECUTION_STATUS_RUNNING,
        COMMAND_EXECUTION_STATUS_SUCCEEDED,
        COMMAND_EXECUTION_STATUS_FAILED,
        COMMAND_EXECUTION_STATUS_CANCELLED
    }
    public enum MissionType {
        MISSION_TYPE_UNSPECIFIED,
        MISSION_TYPE_AREA_SCAN,
        MISSION_TYPE_POINT_INSPECTION,
        MISSION_TYPE_ROUTE_INSPECTION,
        MISSION_TYPE_PERIMETER_PATROL,
        MISSION_TYPE_ROUTE_PATROL,
        MISSION_TYPE_LIVE_OBSERVATION,
        MISSION_TYPE_TARGET_TRACKING,
        MISSION_TYPE_CROWD_MONITORING,
        MISSION_TYPE_THERMAL_SCAN,
        MISSION_TYPE_MULTISPECTRAL_SCAN,
        MISSION_TYPE_3D_MAPPING,
        MISSION_TYPE_PHOTOGRAMMETRY,
        MISSION_TYPE_DAMAGE_ASSESSMENT,
        MISSION_TYPE_SITUATIONAL_ASSESSMENT,
        MISSION_TYPE_SEARCH,
        MISSION_TYPE_PAYLOAD_DELIVERY,
        MISSION_TYPE_COMMUNICATION_RELAY,
        MISSION_TYPE_DATA_COLLECTION,
        MISSION_TYPE_SECURITY_SWEEP,
        MISSION_TYPE_HAZARD_DETECTION,
        MISSION_TYPE_GAS_DETECTION,
        MISSION_TYPE_RADIATION_DETECTION,
        MISSION_TYPE_CUSTOM
    }
    public enum MissionStatus {
        MISSION_STATUS_UNKNOWN, MISSION_STATUS_DRAFT, MISSION_STATUS_ACTIVE,
        MISSION_STATUS_INACTIVE, MISSION_STATUS_ERROR
    }

    private ConnectorRequestContext context;
    private EventType eventType;
    private Severity severity;
    private AssetStatusEvent assetStatus;
    private MissionEvent mission;
    private CommandExecutionEvent commandExecution;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AssetStatusEvent {
        private String sn;
        private String assetId;
        private Boolean online;
        private String message;
    }

    /** Vendor-neutral lifecycle feedback for one physical command dispatched to an edge adapter. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CommandExecutionEvent {
        private String externalExecutionId;
        private String commandId;
        private CommandExecutionStatus status;
        private Float progress;
        private String message;
        private Map<String, Object> output;
        private LocalDateTime occurredAt;
        private String assetSn;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MissionEvent {
        private String missionId;
        private MissionType missionType;
        private MissionStatus status;
        private String message;
    }
}
