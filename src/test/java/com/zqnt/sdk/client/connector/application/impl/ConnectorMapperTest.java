package com.zqnt.sdk.client.connector.application.impl;

import com.zqnt.sdk.client.connector.domains.ConnectorRequestContext;
import com.zqnt.sdk.client.connector.domains.StoreDetectionRequest;
import com.zqnt.sdk.client.connector.domains.StoreNotificationRequest;
import com.zqnt.sdk.client.connector.domains.StoreTelemetryRequest;
import com.zqnt.utils.common.proto.AssetPayloadProtoDTO;
import com.zqnt.utils.common.proto.AssetProtoDTO;
import com.zqnt.utils.connector.proto.TelemetryType;
import com.zqnt.utils.detections.domains.DetectionDTO;
import com.zqnt.utils.edge.sdk.domains.TelemetryData;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.events.proto.NotificationSeverity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorMapperTest {

    private final ConnectorMapper mapper = new ConnectorMapper();

    @Test
    void mapsPayloadInventoryWithoutDuplicatingCapabilityContracts() {
        var proto = com.zqnt.utils.connector.proto.AssetPayloadListResponse.newBuilder()
                .addPayloads(AssetPayloadProtoDTO.newBuilder()
                        .setExternalId("parachute-1")
                        .setKind("PARACHUTE")
                        .setStateJson("{\"armed\":true}")
                        .setActive(true))
                .build();

        var payload = mapper.payloadListResponse(proto).getPayloads().getFirst();

        assertEquals("parachute-1", payload.getExternalId());
        assertEquals("PARACHUTE", payload.getKind());
        assertEquals(true, payload.getState().get("armed"));
    }

    @Test
    void mapsPojoContextToInternalRequestBase() {
        var context = ConnectorRequestContext.builder()
                .tid("transaction-1")
                .sn("asset-sn")
                .assetId("asset-1")
                .clientId("client-1")
                .build();

        var proto = mapper.base(context);

        assertEquals("transaction-1", proto.getTid());
        assertEquals("asset-sn", proto.getSn());
        assertEquals("asset-1", proto.getAssetId());
        assertEquals("client-1", proto.getClientId());
        assertTrue(proto.hasTimestamp());
    }

    @Test
    void mapsInternalConnectorResponseToPojo() {
        UUID assetId = UUID.randomUUID();
        var proto = com.zqnt.utils.connector.proto.ConnectorResponse.newBuilder()
                .setTid("transaction-2")
                .setId("result-1")
                .setAssetId(assetId.toString())
                .setResponseMessage("stored")
                .setAsset(AssetProtoDTO.newBuilder().setId(assetId.toString()).setSn("asset-sn"))
                .build();

        var response = mapper.connectorResponse(proto);

        assertTrue(response.isSuccess());
        assertEquals("transaction-2", response.getTid());
        assertEquals("result-1", response.getId());
        assertEquals(assetId.toString(), response.getAssetId());
        assertEquals("stored", response.getMessage());
        assertEquals(assetId, response.getAsset().getId());
        assertEquals("asset-sn", response.getAsset().getSn());
    }

    @Test
    void mapsPojoTelemetryToInternalBatchRequest() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 25, 12, 30);
        TelemetryData telemetry = TelemetryData.builder()
                .timestamp(timestamp)
                .latitude(47.3769)
                .longitude(8.5417)
                .absoluteAltitude(510.5f)
                .asset(TelemetryData.AssetDetails.builder().environmentTemp(24.5f).build())
                .build();
        var request = StoreTelemetryRequest.builder()
                .context(ConnectorRequestContext.builder().sn("asset-sn").build())
                .sourceType(StoreTelemetryRequest.SourceType.ASSET)
                .assetId("asset-1")
                .sourceSystem("test-client")
                .telemetry(telemetry)
                .additionalData(Map.of("signal", "good"))
                .build();

        var proto = mapper.telemetry(request);

        assertEquals(TelemetryType.TELEMETRY_TYPE_ASSET, proto.getType());
        assertTrue(proto.hasAssetTelemetry());
        assertEquals("asset-1", proto.getAssetTelemetry().getAssetId());
        assertEquals(47.3769, proto.getAssetTelemetry().getLatitude());
        assertEquals(24.5, proto.getAssetTelemetry().getTemperature());
        assertEquals("good", proto.getAssetTelemetry().getTelemetryDataOrThrow("signal"));
        assertNotNull(proto.getAssetTelemetry().getTimestamp());
    }

    @Test
    void mapsPojoDetectionAndNotificationToInternalBatchRequests() {
        LocalDateTime detectedAt = LocalDateTime.of(2026, 7, 25, 12, 45);
        var detection = DetectionDTO.builder()
                .assetSn("asset-sn")
                .objectId("object-1")
                .objectType("person")
                .confidence(0.92f)
                .detectedAt(detectedAt)
                .build();

        var detectionProto = mapper.detection(StoreDetectionRequest.builder().detection(detection).build());

        assertEquals("asset-sn", detectionProto.getAssetSn());
        assertEquals("object-1", detectionProto.getObjectId());
        assertEquals("person", detectionProto.getObjectType());
        assertEquals(0.92f, detectionProto.getConfidence());
        assertTrue(detectionProto.hasDetectedAt());

        var notification = StoreNotificationRequest.builder()
                .eventType(StoreNotificationRequest.EventType.ASSET_STATUS)
                .severity(StoreNotificationRequest.Severity.INFO)
                .assetStatus(StoreNotificationRequest.AssetStatusEvent.builder()
                        .sn("asset-sn")
                        .online(true)
                        .message("online")
                        .build())
                .build();

        var notificationProto = mapper.notification(notification);

        assertEquals(NotificationEventType.NOTIFICATION_EVENT_ASSET_STATUS, notificationProto.getEventType());
        assertEquals(NotificationSeverity.NOTIFICATION_SEVERITY_INFO, notificationProto.getSeverity());
        assertTrue(notificationProto.getEvent().hasAssetStatus());
        assertTrue(notificationProto.getEvent().getAssetStatus().getOnline());
        assertFalse(notificationProto.getEvent().hasCommandExecution());
    }
}
