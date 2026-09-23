package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DockOperationRequest {
    private String sn;
    private String assetId;
    private Boolean value;
    /** Only read by {@code changeAcMode}. */
    private AirConditionerMode acMode;
}

