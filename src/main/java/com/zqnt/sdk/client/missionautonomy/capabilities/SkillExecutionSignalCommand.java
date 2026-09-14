package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.google.protobuf.Struct;

/** Signal for event-wait and human-approval nodes. */
public record SkillExecutionSignalCommand(
        String executionId,
        String nodeId,
        String eventType,
        Struct data,
        Boolean approved,
        String idempotencyKey) {

    public SkillExecutionSignalCommand {
        executionId = SkillExecutionCommand.requireText(executionId, "executionId");
        data = data == null ? Struct.getDefaultInstance() : data;
        if ((eventType == null || eventType.isBlank()) && approved == null) {
            throw new IllegalArgumentException("eventType or approved must be supplied");
        }
    }
}
