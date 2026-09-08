package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualControlInput {

	private String sn;
	private Float roll;
	private Float pitch;
	private Float yaw;
	private Float throttle;
	private Float gimbalPitch;

}
