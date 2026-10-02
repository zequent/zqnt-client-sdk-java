package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** One event a command (or the skill it belongs to) can emit while or after executing. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CapabilityEventDescriptor {
    private String name;
    private String description;
    /** JSON Schema describing the event payload. */
    private Map<String, Object> payloadSchema;
}
