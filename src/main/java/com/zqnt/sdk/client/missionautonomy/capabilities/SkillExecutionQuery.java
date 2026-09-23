package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.zqnt.utils.execution.proto.SkillExecutionStatusProto;

/** Optional filters and pagination for capability executions. */
public record SkillExecutionQuery(
        String assetSn,
        String organizationId,
        SkillExecutionStatusProto status,
        String applicationId,
        String skillId,
        String theatreId,
        Integer pageSize,
        String pageToken) {

    public SkillExecutionQuery {
        if (pageSize != null && (pageSize < 1 || pageSize > 200)) {
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
    }

    public static SkillExecutionQuery firstPage() {
        return new SkillExecutionQuery(null, null, null, null, null, null, null, null);
    }
}
