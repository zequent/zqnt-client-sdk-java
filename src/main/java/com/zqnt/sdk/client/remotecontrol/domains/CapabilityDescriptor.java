package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CapabilityDescriptor {
    private String commandId;
    private String displayName;
    private String description;
    private String state;
    private String unavailableReason;
    private CapabilityTargetType targetType;
    private String targetRef;
    private String schemaVersion;
    private Map<String, String> metadata;
    private Map<String, Object> constraints;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    /** Errors this command's execution can end in. */
    private List<CapabilityErrorDescriptor> errors;
    /** Events this command (or its skill) can emit while/after executing. */
    private List<CapabilityEventDescriptor> events;
    private CapabilityRequirements requirements;
    /** Groups sibling commands into one logical Skill; falls back to the command id's leading
     * namespace segment (e.g. "flight" from "flight.takeoff") when the adapter didn't set one. */
    private String skillId;
    private CapabilitySource source;
    /** Human-readable origin, e.g. "DJI Adapter" or "Zequent Platform" for built-ins. */
    private String provider;
}
