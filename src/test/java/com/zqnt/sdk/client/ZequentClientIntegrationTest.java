package com.zqnt.sdk.client;

import com.zqnt.sdk.client.livedata.domains.StreamHandle;
import com.zqnt.sdk.client.livedata.domains.StreamTelemetryRequest;
import com.zqnt.sdk.client.missionautonomy.domains.MissionResponse;
import com.zqnt.sdk.client.missionautonomy.domains.TaskResponse;
import com.zqnt.sdk.client.remotecontrol.domains.DockOperationRequest;
import com.zqnt.sdk.client.remotecontrol.domains.LiveStreamSplitScreenRequest;
import com.zqnt.sdk.client.remotecontrol.domains.RemoteControlResponse;
import com.zqnt.sdk.client.testdata.MissionNfzTestData;
import com.zqnt.utils.devicecontrol.proto.LiveDataServiceCommand;
import com.zqnt.utils.missionautonomy.domains.WaypointDTO;
import com.zqnt.utils.missionautonomy.domains.config.WaypointTaskConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
		assertNotNull(client.remoteControl());
		assertNotNull(client.missionAutonomy());
		assertNotNull(client.liveData());
	}

	@Test
	void changesRemoteDebugMode() throws Exception {
		RemoteControlResponse response = client.remoteControl().debugMode(
				DockOperationRequest.builder()
						.sn(TEST_ASSET_SN)
						.value(true)
						.build())
				.get(15, TimeUnit.SECONDS);

		assertValidCommandResponse(response);
	}

	@Test
	void changesLiveStreamSplitScreenMode() throws Exception {
		var request = LiveStreamSplitScreenRequest.builder()
				.sn(TEST_ASSET_SN)
				.enabled(SPLIT_SCREEN_ENABLED)
				.build();

		RemoteControlResponse response = client.remoteControl()
				.liveStreamSplitScreen(request)
				.get(15, TimeUnit.SECONDS);

		assertSuccessfulCommand(response);
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
		CountDownLatch receivedFiveMessages = new CountDownLatch(5);
		AtomicInteger receivedMessages = new AtomicInteger();
		AtomicReference<Throwable> streamError = new AtomicReference<>();

		var request = new StreamTelemetryRequest(
				TEST_ASSET_SN,
				UUID.randomUUID().toString(),
				100,
				100,
				LocalDateTime.now(),
				LiveDataServiceCommand.LIVE_DATA_COMMAND_START_TELEMETRY_STREAM);

		try (StreamHandle ignored = client.liveData().streamTelemetryData(request, response -> {
			assertNotNull(response);
			assertNotNull(response.getTid());
			assertNotNull(response.getSn());
			int count = receivedMessages.incrementAndGet();
			log.info("Telemetry #{}: tid={}, sn={}, hasErrors={}",
					count, response.getTid(), response.getSn(), response.isHasErrors());
			receivedFiveMessages.countDown();
		}, error -> {
			streamError.compareAndSet(null, error);
			while (receivedFiveMessages.getCount() > 0) {
				receivedFiveMessages.countDown();
			}
		})) {
			assertTrue(receivedFiveMessages.await(15, TimeUnit.SECONDS),
					"Expected five telemetry messages within 15 seconds");
		}

		assertNull(streamError.get(), () -> "Telemetry stream failed: " + streamError.get());
		assertTrue(receivedMessages.get() >= 5,
				() -> "Expected at least five telemetry messages, got " + receivedMessages.get());
	}

	@Test
	void returnsAStructuredErrorForUnknownAsset() throws Exception {
		RemoteControlResponse response = client.remoteControl().debugMode(
				DockOperationRequest.builder()
						.sn("INVALID_SN_DOES_NOT_EXIST")
						.value(true)
						.build())
				.get(15, TimeUnit.SECONDS);

		assertNotNull(response);
		assertFalse(response.isSuccess());
		assertNotNull(response.getError());
		assertNotNull(response.getError().getErrorCode());
	}

	private static void assertValidCommandResponse(RemoteControlResponse response) {
		assertNotNull(response);
		assertNotNull(response.getTid());
		if (response.isSuccess()) {
			assertEquals(TEST_ASSET_SN, response.getSn());
		} else {
			assertNotNull(response.getError());
		}
	}

	private static void assertSuccessfulCommand(RemoteControlResponse response) {
		assertValidCommandResponse(response);
		assertTrue(response.isSuccess(), () -> response.getError() != null
				? response.getError().getErrorCode() + ": " + response.getError().getErrorMessage()
				: response.getMessage());
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
