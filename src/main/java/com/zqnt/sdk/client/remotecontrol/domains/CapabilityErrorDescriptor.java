package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One error a command's execution can end in — part of its contract alongside input/output schema. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CapabilityErrorDescriptor {
    private String code;
    private String description;
}
