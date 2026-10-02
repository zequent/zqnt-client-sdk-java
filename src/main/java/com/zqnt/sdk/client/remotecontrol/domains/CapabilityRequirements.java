package com.zqnt.sdk.client.remotecontrol.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * What a command needs in order to be usable at all — feeds future "can this Skill run on this
 * Asset/Site?" checks. Purely declarative here, no enforcement implied.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CapabilityRequirements {
    private List<String> assetTypes;
    private List<String> payloads;
    private List<String> runtimeFeatures;
    /** Free-form property requirements, e.g. {"camera.resolution": {"supported": ["4K"]}}. */
    private Map<String, Object> properties;
}
