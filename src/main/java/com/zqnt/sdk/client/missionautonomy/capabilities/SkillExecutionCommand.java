package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.google.protobuf.Struct;
import com.zqnt.utils.devicecontrol.proto.CapabilityTarget;
import com.zqnt.utils.execution.proto.ApplicationExecutionSpecProto;
import com.zqnt.utils.execution.proto.SimpleExecutionSpecProto;
import com.zqnt.utils.execution.proto.SkillExecutionOptionsProto;
import com.zqnt.utils.execution.proto.SkillExecutionSpecProto;

import java.util.Objects;
import java.util.UUID;

/** Type-safe input for creating or immediately executing a capability. */
public record SkillExecutionCommand(
        String assetSn,
        SkillExecutionSpecProto spec,
        SkillExecutionOptionsProto options,
        String idempotencyKey,
        String organizationId,
        String locationId,
        String theatreId) {

    public SkillExecutionCommand {
        assetSn = requireText(assetSn, "assetSn");
        spec = Objects.requireNonNull(spec, "spec must not be null");
        if (spec.getExecutionCase() == SkillExecutionSpecProto.ExecutionCase.EXECUTION_NOT_SET) {
            throw new IllegalArgumentException("spec must contain a simple or package execution");
        }
        options = options == null ? SkillExecutionOptionsProto.getDefaultInstance() : options;
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    }

    public static SkillExecutionCommand simple(String assetSn, String commandId,
            CapabilityTarget target, Struct parameters, String idempotencyKey) {
        String command = requireText(commandId, "commandId");
        var simple = SimpleExecutionSpecProto.newBuilder()
                .setCommandId(command)
                .setTarget(target == null ? CapabilityTarget.getDefaultInstance() : target)
                .setParameters(parameters == null ? Struct.getDefaultInstance() : parameters)
                .build();
        return new SkillExecutionCommand(assetSn,
                SkillExecutionSpecProto.newBuilder().setSimple(simple).build(),
                SkillExecutionOptionsProto.newBuilder().setAutoStart(true).build(),
                defaultKey(idempotencyKey), null, null, null);
    }

    public static SkillExecutionCommand packaged(String assetSn, String applicationId, String skillId,
            String packageVersion, Struct parameters, String idempotencyKey) {
        var packaged = ApplicationExecutionSpecProto.newBuilder()
                .setApplicationId(requireText(applicationId, "applicationId"))
                .setSkillId(requireText(skillId, "skillId"))
                .setParameters(parameters == null ? Struct.getDefaultInstance() : parameters);
        if (packageVersion != null && !packageVersion.isBlank()) packaged.setApplicationVersion(packageVersion);
        return new SkillExecutionCommand(assetSn,
                SkillExecutionSpecProto.newBuilder().setApplication(packaged).build(),
                SkillExecutionOptionsProto.newBuilder().setAutoStart(true).build(),
                defaultKey(idempotencyKey), null, null, null);
    }

    private static String defaultKey(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
