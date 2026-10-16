package com.zqnt.sdk.client;

import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.connector.application.Connector;
import com.zqnt.sdk.client.missionautonomy.application.MissionAutonomy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests für den ZequentClient.
 * Diese Tests verwenden den Builder ohne echte Serververbindungen.
 * <p>
 * Für Integration-Tests siehe ZequentClientIntegrationTest.
 */
@Tag("unit")
class ZequentClientTest {

	private ZequentClient client;

	@BeforeEach
	void setUp() {
		// Client mit Builder erstellen (ohne echte Verbindung)
		client = ZequentClient.builder()
				.remoteControl()
				.port(8002)
				.usePlaintext(true)
				.useStork(false)
				.done()
				.missionAutonomy()
				.port(8004)
				.usePlaintext(true)
				.useStork(false)
				.done()
				.liveData()
				.port(8003)
				.usePlaintext(true)
				.useStork(false)
				.done()
				.connector()
				.port(8010)
				.usePlaintext(true)
				.useStork(false)
				.done()
				.maxRetryAttempts(3)
				.retryDelayMillis(1000)
				.connectionTimeoutSeconds(10)
				.requestTimeoutSeconds(30)
				.build();
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.close();
		}
	}

	@Test
	void testClientCreation() {
		// Client sollte erfolgreich erstellt worden sein
		assertNotNull(client, "Client sollte nicht null sein");
	}

	@Test
	void testCommandsAndTelemetry() {
		assertNotNull(client.commands());
		assertNotNull(client.telemetry());
	}

	@Test
	void testMissionAutonomyService() {
		// MissionAutonomy Service sollte verfügbar sein
		MissionAutonomy missionAutonomy = client.missionAutonomy();
		assertNotNull(missionAutonomy, "MissionAutonomy sollte nicht null sein");
	}

	@Test
	void testConnectorService() {
		Connector connector = client.connector();
		assertNotNull(connector, "Connector should not be null");
	}

	@Test
	void testClientConfiguration() {
		// Konfiguration sollte korrekt gesetzt sein
		assertNotNull(client.getConfig(), "Config sollte nicht null sein");
		assertEquals(3, client.getConfig().getMaxRetryAttempts());
		assertEquals(1000, client.getConfig().getRetryDelayMillis());
		assertEquals(10, client.getConfig().getConnectionTimeoutSeconds());
		assertEquals(30, client.getConfig().getRequestTimeoutSeconds());
	}

	@Test
	void testServiceConfigurations() {
		// RemoteControl Config überprüfen
		ServiceConfig rcConfig = client.getConfig().getRemoteControlConfig();
		assertNotNull(rcConfig);
		assertEquals("remote-control", rcConfig.getServiceName());
		assertEquals("localhost", rcConfig.getHost());
		assertEquals(8002, rcConfig.getPort());
		assertTrue(rcConfig.isUsePlaintext());
		assertFalse(rcConfig.isUseStork());

		// MissionAutonomy Config überprüfen
		ServiceConfig maConfig = client.getConfig().getMissionAutonomyConfig();
		assertNotNull(maConfig);
		assertEquals("mission-autonomy", maConfig.getServiceName());
		assertEquals(8004, maConfig.getPort());

		// LiveData Config überprüfen
		ServiceConfig ldConfig = client.getConfig().getLiveDataConfig();
		assertNotNull(ldConfig);
		assertEquals("live-data", ldConfig.getServiceName());
		assertEquals(8003, ldConfig.getPort());

		ServiceConfig connectorConfig = client.getConfig().getConnectorConfig();
		assertNotNull(connectorConfig);
		assertEquals("connector", connectorConfig.getServiceName());
		assertEquals(8010, connectorConfig.getPort());
	}

	@Test
	void testClientClose() {
		// Client sollte ohne Fehler geschlossen werden können
		assertDoesNotThrow(() -> client.close());
	}

	@Test
	void testBuilderWithCustomLoadBalancer() {
		// Test mit custom LoadBalancer
		ZequentClient customClient = ZequentClient.builder()
				.defaultLoadBalancerType(ServiceConfig.LoadBalancerType.ROUND_ROBIN)
				.remoteControl()
				.host("localhost")
				.port(9091)
				.done()
				.build();

		assertNotNull(customClient);
		assertEquals(ServiceConfig.LoadBalancerType.ROUND_ROBIN,
				customClient.getConfig().getDefaultLoadBalancerType());

		customClient.close();
	}

	@Test
	void testBuilderWithMinimalConfiguration() {
		// Test mit minimaler Konfiguration (nur Defaults)
		ZequentClient minimalClient = ZequentClient.builder().build();

		assertNotNull(minimalClient);
		assertNotNull(minimalClient.commands());
		assertNotNull(minimalClient.telemetry());
		assertNotNull(minimalClient.missionAutonomy());
		assertNotNull(minimalClient.connector());

		minimalClient.close();
	}
}
