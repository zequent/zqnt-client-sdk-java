package com.zqnt.sdk.client.remotecontrol.domains;

import com.zqnt.utils.common.proto.AssetAirConditionerStateEnum;

/**
 * The air conditioner modes a dock can be switched into. The other {@link AssetAirConditionerStateEnum}
 * values are transitional states a dock reports, not modes it can be told to enter.
 */
public enum AirConditionerMode {
    IDLE(AssetAirConditionerStateEnum.AIR_CONDITIONER_IDLE),
    COOL(AssetAirConditionerStateEnum.AIR_CONDITIONER_COOL),
    HEAT(AssetAirConditionerStateEnum.AIR_CONDITIONER_HEAT),
    DEHUMIDIFICATION(AssetAirConditionerStateEnum.AIR_CONDITIONER_DEHUMIDIFICATION);

    private final AssetAirConditionerStateEnum proto;

    AirConditionerMode(AssetAirConditionerStateEnum proto) {
        this.proto = proto;
    }

    public AssetAirConditionerStateEnum toProto() {
        return proto;
    }
}
