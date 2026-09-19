package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.google.protobuf.Struct;
import com.zqnt.utils.devicecontrol.proto.CapabilityTarget;
import com.zqnt.utils.execution.proto.ApplicationExecutionSpecProto;
import com.zqnt.utils.execution.proto.SimpleExecutionSpecProto;
import com.zqnt.utils.execution.proto.SkillExecutionOptionsProto;
import com.zqnt.utils.execution.proto.SkillExecutionSpecProto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityApiTest {

    @Test
    void createsAutoStartingSimpleExecution() {
        var parameters = Struct.newBuilder().putFields("altitude",
                com.google.protobuf.Value.newBuilder().setNumberValue(30).build()).build();

        var command = SkillExecutionCommand.simple("drone-1", "flight.takeoff",
                CapabilityTarget.getDefaultInstance(), parameters, "takeoff-42");

        assertEquals("drone-1", command.assetSn());
        assertEquals("takeoff-42", command.idempotencyKey());
        assertEquals("flight.takeoff", command.spec().getSimple().getCommandId());
        assertEquals(30, command.spec().getSimple().getParameters()
                .getFieldsOrThrow("altitude").getNumberValue());
        assertTrue(command.options().getAutoStart());
    }

    @Test
    void createsVersionedPackageExecution() {
        var command = SkillExecutionCommand.packaged("drone-1", "inspection", "perimeter",
                "2.1.0", Struct.getDefaultInstance(), "exec-1");

        assertTrue(command.spec().hasApplication());
        assertEquals("inspection", command.spec().getApplication().getApplicationId());
        assertEquals("perimeter", command.spec().getApplication().getSkillId());
        assertEquals("2.1.0", command.spec().getApplication().getApplicationVersion());
    }

    @Test
    void rejectsInvalidCommandsAndQueriesBeforeNetworkCall() {
        var validSpec = SkillExecutionSpecProto.newBuilder().setSimple(
                SimpleExecutionSpecProto.newBuilder().setCommandId("flight.takeoff")).build();

        assertThrows(IllegalArgumentException.class, () -> new SkillExecutionCommand(
                " ", validSpec, SkillExecutionOptionsProto.getDefaultInstance(), "key",
                null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SkillExecutionCommand(
                "drone-1", SkillExecutionSpecProto.getDefaultInstance(),
                SkillExecutionOptionsProto.getDefaultInstance(), "key", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationQuery(
                null, null, 201, null));
        assertThrows(IllegalArgumentException.class, () -> new SkillExecutionSignalCommand(
                "exec-1", null, null, null, null, null));
    }

    /** An Application may name no asset — the platform resolves one from its deployment scope, or by
     * policy when it declares none. Rejecting it here made that path unreachable through the SDK. */
    @Test
    void acceptsAnApplicationExecutionThatNamesNoAsset() {
        var applicationSpec = SkillExecutionSpecProto.newBuilder().setApplication(
                ApplicationExecutionSpecProto.newBuilder()
                        .setApplicationId("app-1").setSkillId("skill-1")).build();

        for (String noAsset : new String[] {null, "", " "}) {
            var command = new SkillExecutionCommand(noAsset, applicationSpec,
                    SkillExecutionOptionsProto.getDefaultInstance(), "key", null, null, null);
            assertTrue(command.assetSn() == null || command.assetSn().isBlank(),
                    "a blank assetSn must survive as blank so the server resolves it");
        }
    }

    @Test
    void pagesAreImmutableAndExposeContinuation() {
        var mutable = new ArrayList<>(List.of("first"));
        var page = new ResultPage<>(mutable, "next");
        mutable.add("second");

        assertEquals(List.of("first"), page.items());
        assertTrue(page.hasNextPage());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("third"));
    }
}
