package com.zqnt.sdk.client.livedata.domains;

import com.zqnt.utils.devicecontrol.proto.LiveDataServiceCommand;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;


@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StreamTelemetryRequest {

	private String sn;
	private String tid;
	private int frequencyMs;
	private int duration;
	private LocalDateTime timestamp;
	private LiveDataServiceCommand command;

}
