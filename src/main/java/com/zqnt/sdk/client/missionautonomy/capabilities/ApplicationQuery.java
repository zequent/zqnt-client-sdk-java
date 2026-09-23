package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.zqnt.utils.execution.proto.ApplicationScopeProtoDTO;

/** Optional filters and pagination for capability packages. */
public record ApplicationQuery(
        ApplicationScopeProtoDTO scope,
        Boolean enabledOnly,
        Integer pageSize,
        String pageToken) {

    public ApplicationQuery {
        if (pageSize != null && (pageSize < 1 || pageSize > 200)) {
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
    }

    public static ApplicationQuery firstPage() {
        return new ApplicationQuery(null, null, null, null);
    }
}
