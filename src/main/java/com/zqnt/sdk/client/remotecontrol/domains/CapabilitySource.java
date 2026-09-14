package com.zqnt.sdk.client.remotecontrol.domains;

/** Where a Capability's implementation actually comes from — see CapabilityDescriptor#getSource(). */
public enum CapabilitySource {
    UNSPECIFIED,
    BUILT_IN,
    EDGE_ADAPTER,
    RUNTIME,
    USER,
    APPLICATION,
    INTEGRATION,
    AI_GENERATED
}
