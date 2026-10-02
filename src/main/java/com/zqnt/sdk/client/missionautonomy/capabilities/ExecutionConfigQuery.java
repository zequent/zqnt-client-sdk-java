package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.zqnt.utils.execution.proto.ExecutionConfigContextProto;

import java.util.List;
import java.util.Objects;

/** Request for resolving effective execution configuration and its sources. */
public record ExecutionConfigQuery(String assetSn, ExecutionConfigContextProto context, List<String> keys) {
    public ExecutionConfigQuery {
        assetSn = SkillExecutionCommand.requireText(assetSn, "assetSn");
        context = Objects.requireNonNull(context, "context must not be null");
        keys = keys == null ? List.of() : List.copyOf(keys);
        if (keys.isEmpty() || keys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("keys must contain at least one non-blank key");
        }
    }
}
