package com.zqnt.sdk.client;

import com.zqnt.sdk.client.remotecontrol.domains.RemoteControlResponse;
import com.zqnt.sdk.client.remotecontrol.domains.ReturnToHomeRequest;
import com.zqnt.sdk.client.remotecontrol.domains.TakeoffRequest;
import com.zqnt.sdk.client.remotecontrol.domains.TakeoffResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the simplest capability-execution flow: a plain takeoff followed by
 * return-to-home, driven through {@link com.zqnt.sdk.client.remotecontrol.application.RemoteControl}.
 *
 * <p>Both calls are thin client-facing wrappers that build a single-node capability-execution graph
 * server-side (see {@code RemoteControlGrpcService} -&gt; {@code MissionAutonomyService.ExecuteCapability})
 * and dispatch it all the way down to the edge adapter (e.g. edge-dji's {@code flight.takeoff} /
 * {@code flight.return_to_home} commands). It exercises the same client -&gt; remote-control -&gt;
 * mission-autonomy -&gt; edge chain as {@link com.zqnt.sdk.client.missionautonomy.capabilities.CapabilityGraphExecutionIntegrationTest},
 * just through the simpler RemoteControl facade instead of the raw capability-execution API.</p>
 *
 * <p>Requires the full local stack running (connector, live-data, remote-control and mission-autonomy
 * services, plus an edge adapter registered for {@code integration.test.sn}) and a valid license
 * lease — {@code ExecuteCapability} is not currently in the license interceptor's safety-command
 * allowlist, so both calls are license-gated even though ReturnToHome is nominally meant to be
 * safety-exempt. {@code integration.test.sn} must be the <b>gateway's</b> SN (a standalone aircraft's
 * own SN, or a docked aircraft's <b>dock</b> SN) — the asset SN is always the gateway clients address,
 * never a sub-asset's own SN. {@code EdgeAdapterDjiImpl#getCapabilities} folds a dock's paired
 * aircraft's capabilities into the dock's own gateway snapshot, and the dock's own OSD telemetry
 * already carries the aircraft's position/battery while docked, so a dock SN alone is sufficient for
 * {@code flight.takeoff}/{@code flight.return_to_home}.</p>
 *
 * <pre>
 * mvn -Dtest=SimpleFlightIntegrationTest \
 *     -Dtest.excludedGroups= \
 *     -Dintegration.tests.enabled=true \
 *     -Dintegration.test.sn=YOUR_DRONE_SN test
 * </pre>
 */
@Slf4j
@Tag("integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class SimpleFlightIntegrationTest {

    private static final String TEST_ASSET_SN = System.getProperty(
            "integration.test.sn", "8UUXN2Q00A01FZ");
    private static final float TAKEOFF_LATITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.latitude", "47.775249"));
    private static final float TAKEOFF_LONGITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.longitude", "9.265800"));
    private static final float TAKEOFF_ALTITUDE = Float.parseFloat(
            System.getProperty("integration.test.takeoff.altitude", "60"));
    private static final long SETTLE_MILLIS = Long.getLong(
            "integration.test.flight.settle-millis", 60_000L);

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
                .maxRetryAttempts(0)
                .requestTimeoutSeconds(15)
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void takesOffAndReturnsHome() throws Exception {
        TakeoffResponse takeoff = client.remoteControl().takeoff(TakeoffRequest.builder()
                .sn(TEST_ASSET_SN)
                        .latitude(TAKEOFF_LATITUDE)
                        .longitude(TAKEOFF_LONGITUDE)
                        .altitude(TAKEOFF_ALTITUDE)
                        .build())
                .get(30, TimeUnit.SECONDS);
        assertSuccessfulTakeoff(takeoff);
        log.info("Takeoff accepted for {}: {}", TEST_ASSET_SN, takeoff.getMessage());

        TimeUnit.MILLISECONDS.sleep(SETTLE_MILLIS);

        RemoteControlResponse returnToHome = client.remoteControl()
                .returnToHome(ReturnToHomeRequest.builder().sn(TEST_ASSET_SN).build())
                .get(20, TimeUnit.SECONDS);
        assertSuccessfulCommand(returnToHome);
        log.info("Return-to-home accepted for {}: {}", TEST_ASSET_SN, returnToHome.getMessage());
    }

    private static void assertSuccessfulTakeoff(TakeoffResponse response) {
        assertNotNull(response);
        assertNotNull(response.getTid());
        assertTrue(response.isSuccess(), () -> response.getError() != null
                ? response.getError().getErrorCode() + ": " + response.getError().getErrorMessage()
                : response.getMessage());
    }

    private static void assertSuccessfulCommand(RemoteControlResponse response) {
        assertNotNull(response);
        assertNotNull(response.getTid());
        assertTrue(response.isSuccess(), () -> response.getError() != null
                ? response.getError().getErrorCode() + ": " + response.getError().getErrorMessage()
                : response.getMessage());
    }
}
