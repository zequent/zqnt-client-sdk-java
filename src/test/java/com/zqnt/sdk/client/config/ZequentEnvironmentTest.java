package com.zqnt.sdk.client.config;

import com.zqnt.sdk.client.ZequentClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nothing set is the local development stack; a deployment sets the same variables every Zequent
 * client SDK reads.
 */
class ZequentEnvironmentTest {

    static final ServiceConfig.LoadBalancerType RR = ServiceConfig.LoadBalancerType.ROUND_ROBIN;

    @Test
    void nothingSetIsTheLocalStack() {
        ServiceConfig config = ZequentEnvironment.fromEnvironment(name -> null, ZequentEnvironment.REMOTE_CONTROL,
                "remote-control", RR);
        assertEquals("localhost", config.getHost());
        assertEquals(8002, config.getPort());
        assertTrue(config.isUsePlaintext());
        assertEquals(8010, ZequentEnvironment.local("connector", RR).getPort());
        assertEquals(8003, ZequentEnvironment.local("live-data", RR).getPort());
        assertEquals(8004, ZequentEnvironment.local("mission-autonomy", RR).getPort());
    }

    @Test
    void aDeploymentOverridesHostPortAndTransport() {
        Map<String, String> env = Map.of(
                "CONNECTOR_SERVICE_HOST", "connector.zequent.internal",
                "CONNECTOR_SERVICE_PORT", " 443 ",
                "CONNECTOR_SERVICE_USE_PLAINTEXT", "false");
        ServiceConfig config = ZequentEnvironment.fromEnvironment(env::get, ZequentEnvironment.CONNECTOR, "connector", RR);
        assertEquals("connector.zequent.internal", config.getHost());
        assertEquals(443, config.getPort());
        assertFalse(config.isUsePlaintext(), "TLS");
    }

    @Test
    void aMalformedPortSaysWhichVariable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ZequentEnvironment.fromEnvironment(Map.of("LIVE_DATA_SERVICE_PORT", "80a3")::get,
                        ZequentEnvironment.LIVE_DATA, "live-data", RR));
        assertTrue(failure.getMessage().contains("LIVE_DATA_SERVICE_PORT"), failure.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    void theBuilderTakesUnconfiguredServicesAndTheTokenFromTheEnvironment() {
        Map<String, String> env = Map.of(
                "REMOTE_CONTROL_SERVICE_HOST", "rc.example.com",
                "ZQNT_CLIENT_TOKEN", "env-token");
        try (ZequentClient client = ZequentClient.builder().fromEnvironment(env::get)
                .connector().host("explicit").port(9999).done()
                .build()) {
            GrpcClientConfig config = client.getConfig();
            assertEquals("rc.example.com", config.getRemoteControlConfig().getHost());
            assertEquals(8002, config.getRemoteControlConfig().getPort());
            assertEquals("explicit", config.getConnectorConfig().getHost(), "explicit configuration wins");
            assertEquals("localhost", config.getLiveDataConfig().getHost());
            assertEquals("env-token", config.getClientToken());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void anExplicitTokenWinsOverTheEnvironment() {
        try (ZequentClient client = ZequentClient.builder().clientToken("explicit")
                .fromEnvironment(Map.of("ZQNT_CLIENT_TOKEN", "env-token")::get).build()) {
            assertEquals("explicit", client.getConfig().getClientToken());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void anUnconfiguredBuilderPointsAtTheLocalStack() {
        try (ZequentClient client = ZequentClient.builder().build()) {
            assertEquals(8002, client.getConfig().getRemoteControlConfig().getPort());
            assertEquals(8003, client.getConfig().getLiveDataConfig().getPort());
            assertEquals(8004, client.getConfig().getMissionAutonomyConfig().getPort());
        }
    }
}
