package com.zqnt.sdk.client;

import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.missionautonomy.domains.MissionResponse;
import com.zqnt.sdk.client.missionautonomy.domains.TaskResponse;
import com.zqnt.sdk.client.telemetry.domains.TelemetryWatch;
import com.zqnt.sdk.client.testdata.MissionNfzTestData;
import com.zqnt.utils.missionautonomy.domains.WaypointDTO;
import com.zqnt.utils.missionautonomy.domains.config.WaypointTaskConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests against locally running Zequent services.
 *
 * <p>Run explicitly because these tests send commands to a real dock:</p>
 * <pre>
 * mvn -Dtest.excludedGroups= \
 *     -Dintegration.tests.enabled=true \
 *     -Dintegration.test.sn=YOUR_DOCK_SN \
 *     test
 * </pre>
 *
 * <p>The service endpoints can be overridden with {@code *.test.host} and
 * {@code *.test.port} system properties.</p>
 */
@Slf4j
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class ZequentClientIntegrationTest {

	private static final String TEST_ASSET_SN = System.getProperty(
			"integration.test.sn", "8UUXN2Q00A01FZ");
	private static final boolean SPLIT_SCREEN_ENABLED = Boolean.parseBoolean(System.getProperty(
			"integration.test.split-screen.enabled", "true"));
	private static final String TEST_MISSION_ID = System.getProperty(
			"integration.test.mission-id", MissionNfzTestData.MISSION_ID);
	private static final boolean REPLACE_EXISTING_NFZ_ZONES = Boolean.parseBoolean(System.getProperty(
			"integration.test.nfz.replace-existing", "false"));
	private static final boolean DELETE_REROUTING_TASK = Boolean.parseBoolean(System.getProperty(
			"integration.test.reroute.delete-task", "false"));

	private static ZequentClient client;

	static {
		// Avoid the JBoss LogManager warning when running the test directly from IntelliJ.
		System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
	}

	@BeforeAll
	static void createClient() {
		client = ZequentClient.builder()
				.remoteControl()
					.host(System.getProperty("remote.control.test.host", "localhost"))
					.port(Integer.getInteger("remote.control.test.port", 8002))
					.usePlaintext(true)
					.useStork(false)
					.done()
				.missionAutonomy()
					.host(System.getProperty("mission.autonomy.test.host", "localhost"))
					.port(Integer.getInteger("mission.autonomy.test.port", 8004))
					.usePlaintext(true)
					.useStork(false)
					.done()
				.liveData()
					.host(System.getProperty("live.data.test.host", "localhost"))
					.port(Integer.getInteger("live.data.test.port", 8003))
					.usePlaintext(true)
					.useStork(false)
					.done()
				.maxRetryAttempts(3)
				.retryDelayMillis(500)
				.connectionTimeoutSeconds(5)
				.requestTimeoutSeconds(10)
				.build();
	}

	@AfterAll
	static void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	@Test
	void createsUsableServiceClients() {
		assertTrue(client.isConnected());
		assertNotNull(client.commands());
		assertNotNull(client.missionAutonomy());
		assertNotNull(client.telemetry());
	}

	@Test
	void changesRemoteDebugMode() throws Exception {
		CommandResult result = client.commands()
				.executeCommand(TEST_ASSET_SN, "asset.remote_debug", Map.of("enabled", true))
				.get(15, TimeUnit.SECONDS);

		assertEquals("asset.remote_debug", result.getCommandId());
		assertFalse(result.getCommandExecutionId().isBlank());
	}

	@Test
	void changesLiveStreamSplitScreenMode() throws Exception {
		CommandResult result = client.commands()
				.executeAndWait(TEST_ASSET_SN, "stream.split_screen", Map.of("enabled", SPLIT_SCREEN_ENABLED),
						Duration.ofSeconds(30))
				.get(35, TimeUnit.SECONDS);

		assertFalse(result.getCommandExecutionId().isBlank());
		log.info("Live stream split screen set to {} for {}", SPLIT_SCREEN_ENABLED, TEST_ASSET_SN);
	}

	@Test
	void uploadsAndReadsBackMissionNfzZone() throws Exception {
		var expectedZone = MissionNfzTestData.zone();

		MissionResponse uploadResponse = client.missionAutonomy()
				.uploadMissionNfzZones(
						TEST_MISSION_ID,
						java.util.List.of(expectedZone),
						REPLACE_EXISTING_NFZ_ZONES)
				.get(15, TimeUnit.SECONDS);
		assertSuccessfulMissionResponse(uploadResponse);

		MissionResponse getResponse = client.missionAutonomy()
				.getMission(TEST_MISSION_ID)
				.get(15, TimeUnit.SECONDS);
		assertSuccessfulMissionResponse(getResponse);
		assertNotNull(getResponse.getMissionData());

		var persistedZone = getResponse.getMissionData().getZones().stream()
				.filter(zone -> MissionNfzTestData.OPERATION_ID.equals(zone.getId()))
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"Uploaded NFZ zone was not returned by getMission"));
		assertEquals(expectedZone.getName(), persistedZone.getName());
		assertEquals(expectedZone.getType(), persistedZone.getType());
		assertEquals(expectedZone.getEnforcementType(), persistedZone.getEnforcementType());
		assertNotNull(persistedZone.getArea());
		assertEquals(expectedZone.getArea().getType(), persistedZone.getArea().getType());
		assertEquals(expectedZone.getArea().getVertices(), persistedZone.getArea().getVertices());

		TaskResponse createTaskResponse = client.missionAutonomy()
				.createTask(MissionNfzTestData.reroutingTask(TEST_MISSION_ID, TEST_ASSET_SN))
				.get(15, TimeUnit.SECONDS);
		assertSuccessfulTaskResponse(createTaskResponse);

		String taskId = createTaskResponse.getTaskId();
		boolean started = false;
		try {
			assertNotNull(createTaskResponse.getTaskData());
			WaypointTaskConfig reroutedConfig = assertInstanceOf(
					WaypointTaskConfig.class, createTaskResponse.getTaskData().getConfig());
			List<WaypointDTO> waypoints = reroutedConfig.getWaypoints();
			assertTrue(waypoints.size() > 2,
					() -> "Expected NFZ rerouting to add intermediate waypoints, got " + waypoints.size());
			assertWaypoint(waypoints.getFirst(),
					MissionNfzTestData.ROUTE_START_LATITUDE,
					MissionNfzTestData.ROUTE_START_LONGITUDE);
			assertWaypoint(waypoints.getLast(),
					MissionNfzTestData.ROUTE_DESTINATION_LATITUDE,
					MissionNfzTestData.ROUTE_DESTINATION_LONGITUDE);
			log.info("NFZ rerouting created task {} with {} waypoints", taskId, waypoints.size());

			TaskResponse startResponse = client.missionAutonomy()
					.startTask(taskId)
					.get(30, TimeUnit.SECONDS);
			assertSuccessfulTaskResponse(startResponse);
			started = true;
			log.info("Started NFZ rerouting task {} for asset {}", taskId, TEST_ASSET_SN);
		} finally {
			if (DELETE_REROUTING_TASK && !started && taskId != null && !taskId.isBlank()) {
				assertSuccessfulTaskResponse(client.missionAutonomy()
						.deleteTask(taskId)
						.get(15, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void receivesTelemetryData() throws Exception {
		CountDownLatch receivedFiveSamples = new CountDownLatch(5);
		AtomicInteger receivedSamples = new AtomicInteger();

		try (TelemetryWatch watch = client.telemetry().watchTelemetry(List.of(TEST_ASSET_SN), sample -> {
			assertEquals(TEST_ASSET_SN, sample.getAsset().getSn());
			receivedSamples.incrementAndGet();
			receivedFiveSamples.countDown();
		})) {
			assertTrue(receivedFiveSamples.await(15, TimeUnit.SECONDS),
					"Expected five telemetry samples within 15 seconds");
			assertFalse(watch.done().isCompletedExceptionally());
		}

		assertTrue(receivedSamples.get() >= 5,
				() -> "Expected at least five telemetry samples, got " + receivedSamples.get());
	}

	@Test
	void returnsAStructuredErrorForUnknownAsset() {
		CompletionException failure = assertThrows(CompletionException.class, () -> client.commands()
				.executeCommand("INVALID_SN_DOES_NOT_EXIST", "asset.remote_debug", Map.of("enabled", true))
				.join());

		CommandException refused = assertInstanceOf(CommandException.class, failure.getCause());
		assertNotNull(refused.getCode());
	}

	private static void assertSuccessfulMissionResponse(MissionResponse response) {
		assertNotNull(response);
		assertNotNull(response.getTid());
		assertEquals(TEST_MISSION_ID, response.getMissionId());
		assertTrue(response.isSuccess(), () -> response.getError() != null
				? response.getError().getErrorCode() + ": " + response.getError().getErrorMessage()
				: "Mission operation failed");
	}

	private static void assertSuccessfulTaskResponse(TaskResponse response) {
		assertNotNull(response);
		assertNotNull(response.getTid());
		assertTrue(response.isSuccess(), () -> response.getError() != null
				? response.getError().getErrorCode() + ": " + response.getError().getErrorMessage()
				: "Task operation failed");
	}

	private static void assertWaypoint(
			WaypointDTO waypoint,
			double expectedLatitude,
			double expectedLongitude) {
		assertEquals(expectedLatitude, waypoint.getLatitude(), 0.01);
		assertEquals(expectedLongitude, waypoint.getLongitude(), 0.01);
	}
}
