package com.zqnt.sdk.client.missionautonomy.capabilities;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.sdk.client.ZequentClient;
import com.zqnt.utils.execution.proto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end test for a real, multi-node capability graph: registers a two-command capability
 * package (flight.takeoff -&gt; flight.return_to_home -&gt; end) with mission-autonomy, executes it
 * against a real asset, and polls the execution until it reaches a terminal status.
 *
 * <p>Unlike {@link com.zqnt.sdk.client.SimpleFlightIntegrationTest} (a single-node graph built
 * implicitly by RemoteControl), this exercises the full authoring path: capability package
 * registration ({@link com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy#upsertApplication})
 * -&gt; graph expansion/validation -&gt; packaged execution
 * ({@link com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy#executeSkill}) -&gt;
 * status polling ({@link com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy#getSkillExecution}).
 * The package is registered directly via mission-autonomy's gRPC API (no admin-console REST hop
 * needed). {@code upsertApplication}'s {@code expectedRevision} follows standard
 * optimistic-concurrency semantics: {@code null} means "must not exist yet" and a repeat run
 * against an existing fixture fails with "Capability package revision conflict" — so this test
 * first looks up the fixture's current revision (treating "not found" as fine) and upserts against
 * that, letting repeated runs simply overwrite instead of colliding.</p>
 *
 * <p>Requires the full local stack running (connector, live-data, remote-control and
 * mission-autonomy services, plus an edge adapter registered for {@code integration.test.sn}) and
 * a valid license lease — {@code ExecuteCapability} is license-gated.</p>
 *
 * <pre>
 * mvn -Dtest=CapabilityGraphExecutionIntegrationTest \
 *     -Dtest.excludedGroups= \
 *     -Dintegration.tests.enabled=true \
 *     -Dintegration.test.sn=YOUR_DRONE_SN test
 * </pre>
 */
@Slf4j
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class CapabilityGraphExecutionIntegrationTest {

    private static final String TEST_ASSET_SN = System.getProperty(
            "integration.test.sn", "8UUXN2Q00A01FZ");
    private static final float TAKEOFF_LATITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.latitude", "47.775249"));
    private static final float TAKEOFF_LONGITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.longitude", "9.265800"));
    private static final float TAKEOFF_ALTITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.altitude", "60"));
    private static final long EXECUTION_TIMEOUT_SECONDS = Long.getLong(
            "integration.test.execution.timeout-seconds", 120L);
    private static final long POLL_INTERVAL_SECONDS = Long.getLong(
            "integration.test.execution.poll-interval-seconds", 3L);

    private static final String PACKAGE_ID = "zqnt.integration-test.simple-flight";
    private static final String PACKAGE_VERSION = "1.0.0";
    private static final String CAPABILITY_ID = "takeoff-then-return-home";

    private static ZequentClient client;

    static {
        // Avoid the JBoss LogManager warning when running the test directly from IntelliJ.
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
    }

    @BeforeAll
    static void createClient() {
        client = ZequentClient.builder()
                .missionAutonomy()
                    .host(System.getProperty("mission.autonomy.test.host", "localhost"))
                    .port(Integer.getInteger("mission.autonomy.test.port", 8004))
                    .usePlaintext(true)
                    .useStork(false)
                    .done()
                .maxRetryAttempts(0)
                .requestTimeoutSeconds(15)
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void registersAndExecutesTwoNodeCapabilityGraph() throws Exception {
        String expectedRevision = currentFixtureRevision();
        ApplicationProtoDTO upserted = client.missionAutonomy()
                .upsertApplication(buildTakeoffThenReturnHomePackage(), expectedRevision)
                .get(15, TimeUnit.SECONDS);
        assertEquals(PACKAGE_ID, upserted.getId());
        log.info("Registered capability package {}:{} (revision {})",
                PACKAGE_ID, PACKAGE_VERSION, upserted.getRevision());

        var command = SkillExecutionCommand.packaged(TEST_ASSET_SN, PACKAGE_ID, CAPABILITY_ID,
                PACKAGE_VERSION, Struct.getDefaultInstance(), "integration-test-" + UUID.randomUUID());
        SkillExecutionProtoDTO started = client.missionAutonomy().executeSkill(command)
                .get(60, TimeUnit.SECONDS);
        assertNotNull(started.getId());
        log.info("Started capability execution {} for {} (status {})",
                started.getId(), TEST_ASSET_SN, started.getStatus());

        SkillExecutionProtoDTO finished = awaitTerminalStatus(started.getId());
        finished.getNodeStatesList().forEach(node -> log.info("Node {} ({}) -> {}",
                node.getNodeId(), node.hasCommandId() ? node.getCommandId() : "n/a", node.getStatus()));

        assertEquals(SkillExecutionStatusProto.SKILL_EXECUTION_STATUS_SUCCEEDED, finished.getStatus(),
                () -> finished.hasError()
                        ? finished.getError().getErrorCode() + ": " + finished.getError().getErrorMessage()
                        : "Execution " + finished.getId() + " ended with status " + finished.getStatus());
        log.info("Capability execution {} succeeded", finished.getId());
    }

    /**
     * The revision to upsert against, or {@code null} if the fixture doesn't exist yet.
     * {@code upsertApplication} treats a {@code null} expected revision as "must not exist
     * yet"; upserting an already-existing package with {@code null} fails with "Capability package
     * revision conflict", so a repeat run has to read-modify-write the current revision instead.
     */
    private static String currentFixtureRevision() {
        try {
            return client.missionAutonomy().getApplication(PACKAGE_ID, PACKAGE_VERSION)
                    .get(15, TimeUnit.SECONDS).getRevision();
        } catch (Exception notFoundOrUnavailable) {
            return null;
        }
    }

    /** takeoff --success--> return-home --success--> end */
    private static ApplicationProtoDTO buildTakeoffThenReturnHomePackage() {
        Struct takeoffParameters = Struct.newBuilder()
                .putFields("latitude", Value.newBuilder().setNumberValue(TAKEOFF_LATITUDE).build())
                .putFields("longitude", Value.newBuilder().setNumberValue(TAKEOFF_LONGITUDE).build())
                .putFields("altitude", Value.newBuilder().setNumberValue(TAKEOFF_ALTITUDE).build())
                .build();

        var takeoffNode = ExecutionNodeProtoDTO.newBuilder()
                .setId("takeoff").setName("Take off")
                .setType(ExecutionNodeTypeProto.EXECUTION_NODE_TYPE_COMMAND)
                .setCommand(CommandNodeConfigProto.newBuilder()
                        .setCommandId("flight.takeoff")
                        .setParameterDefaults(takeoffParameters));
        var returnHomeNode = ExecutionNodeProtoDTO.newBuilder()
                .setId("return-home").setName("Return to home")
                .setType(ExecutionNodeTypeProto.EXECUTION_NODE_TYPE_COMMAND)
                .setCommand(CommandNodeConfigProto.newBuilder().setCommandId("flight.return_to_home"));
        var endNode = ExecutionNodeProtoDTO.newBuilder()
                .setId("end").setName("End")
                .setType(ExecutionNodeTypeProto.EXECUTION_NODE_TYPE_END);

        var graph = ExecutionGraphProtoDTO.newBuilder()
                .setStartNodeId("takeoff")
                .addNodes(takeoffNode).addNodes(returnHomeNode).addNodes(endNode)
                .addEdges(ExecutionEdgeProtoDTO.newBuilder().setId("takeoff-to-return-home")
                        .setSourceNodeId("takeoff").setTargetNodeId("return-home")
                        .setType(ExecutionEdgeTypeProto.EXECUTION_EDGE_TYPE_SUCCESS))
                .addEdges(ExecutionEdgeProtoDTO.newBuilder().setId("return-home-to-end")
                        .setSourceNodeId("return-home").setTargetNodeId("end")
                        .setType(ExecutionEdgeTypeProto.EXECUTION_EDGE_TYPE_SUCCESS));

        var capability = SkillProtoDTO.newBuilder()
                .setId(CAPABILITY_ID).setName("Takeoff then return home")
                .setDescription("Integration-test fixture: two-node capability graph chaining "
                        + "flight.takeoff -> flight.return_to_home.")
                .setGraph(graph);

        return ApplicationProtoDTO.newBuilder()
                .setId(PACKAGE_ID).setVersion(PACKAGE_VERSION).setName("Zequent integration test package")
                .setEnabled(true)
                .addSkills(capability)
                .build();
    }

    private static SkillExecutionProtoDTO awaitTerminalStatus(String executionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXECUTION_TIMEOUT_SECONDS);
        SkillExecutionProtoDTO last = null;
        while (System.nanoTime() < deadline) {
            last = client.missionAutonomy().getSkillExecution(executionId).get(15, TimeUnit.SECONDS);
            if (isTerminal(last.getStatus())) return last;
            log.info("Execution {} still {} ({}%), polling again in {}s",
                    executionId, last.getStatus(), Math.round(last.getProgress() * 100), POLL_INTERVAL_SECONDS);
            TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
        }
        throw new AssertionError("Capability execution " + executionId + " did not reach a terminal status within "
                + EXECUTION_TIMEOUT_SECONDS + "s (last status: " + (last != null ? last.getStatus() : "unknown") + ")");
    }

    private static boolean isTerminal(SkillExecutionStatusProto status) {
        return switch (status) {
            case SKILL_EXECUTION_STATUS_SUCCEEDED, SKILL_EXECUTION_STATUS_FAILED,
                    SKILL_EXECUTION_STATUS_CANCELLED -> true;
            default -> false;
        };
    }
}
