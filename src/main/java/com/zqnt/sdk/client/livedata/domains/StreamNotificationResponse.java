package com.zqnt.sdk.client.livedata.domains;

import com.zqnt.utils.common.proto.ErrorCode;
import com.zqnt.utils.events.proto.CommandExecutionStatus;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.execution.proto.ExecutionNodeStatusProto;
import com.zqnt.utils.execution.proto.SkillExecutionEventTypeProto;
import com.zqnt.utils.execution.proto.SkillExecutionStatusProto;
import com.zqnt.utils.mission.proto.MissionStatus;
import com.zqnt.utils.mission.proto.MissionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamNotificationResponse {

    private String tid;
    private Instant timestamp;
    private boolean hasErrors;
    private String sn;
    private String assetId;
    private NotificationEventType eventType;
    private AssetStatusEvent assetStatus;
    private MissionEvent missionEvent;
    private SkillExecutionEvent skillExecutionEvent;
    private CommandExecutionEvent commandExecutionEvent;
    private ErrorInfo error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetStatusEvent {
        private String sn;
        private String assetId;
        private Boolean online;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissionEvent {
        private String missionId;
        private MissionType missionType;
        private MissionStatus status;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillExecutionEvent {
        private String eventId;
        private String executionId;
        private String assetSn;
        private SkillExecutionEventTypeProto type;
        private SkillExecutionStatusProto executionStatus;
        private String nodeId;
        private ExecutionNodeStatusProto nodeStatus;
        private Float progress;
        private Instant occurredAt;
        private ErrorInfo error;
        private Map<String, Object> data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommandExecutionEvent {
        private String externalExecutionId;
        private String commandId;
        private CommandExecutionStatus status;
        private Float progress;
        private String message;
        private Map<String, Object> output;
        private ErrorInfo error;
        private Instant occurredAt;
        private String assetSn;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorInfo {
        private ErrorCode errorCode;
        private String errorMessage;
        private LocalDateTime timestamp;
    }
}
