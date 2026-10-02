package com.zqnt.sdk.client.missionautonomy.capabilities;

/** Idempotent lifecycle command for start, pause, resume, or cancel. */
public record SkillExecutionLifecycleCommand(String executionId, String reason, String idempotencyKey) {
    public SkillExecutionLifecycleCommand {
        executionId = SkillExecutionCommand.requireText(executionId, "executionId");
    }

    public static SkillExecutionLifecycleCommand forExecution(String executionId) {
        return new SkillExecutionLifecycleCommand(executionId, null, null);
    }
}
