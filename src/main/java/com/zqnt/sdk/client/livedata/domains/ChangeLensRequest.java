package com.zqnt.sdk.client.livedata.domains;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeLensRequest {
    private String sn;
    private String tid;
    private String lens;
}
