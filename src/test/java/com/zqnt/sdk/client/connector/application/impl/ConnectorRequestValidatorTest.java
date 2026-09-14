package com.zqnt.sdk.client.connector.application.impl;

import com.zqnt.sdk.client.connector.domains.ConnectorRequestContext;
import com.zqnt.sdk.client.connector.domains.GetAssetBySnRequest;
import com.zqnt.sdk.client.connector.domains.StoreNotificationRequest;
import com.zqnt.sdk.client.connector.domains.StoreTelemetryRequest;
import com.zqnt.utils.edge.sdk.domains.TelemetryData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorRequestValidatorTest {

    @Test
    void requiresSnForSerialNumberOperations() {
        var request = GetAssetBySnRequest.builder()
                .context(ConnectorRequestContext.builder().sn(" ").build())
                .build();

        var error = assertThrows(IllegalArgumentException.class,
                () -> ConnectorRequestValidator.validate(request));

        assertEquals("Invalid Connector request: context.sn must not be null or blank", error.getMessage());
    }

    @Test
    void rejectsTelemetryWithoutSourceTypeInsteadOfTreatingItAsSubAsset() {
        var request = StoreTelemetryRequest.builder()
                .assetId("asset-1")
                .telemetry(TelemetryData.builder().timestamp(LocalDateTime.now()).build())
                .build();

        var error = assertThrows(IllegalArgumentException.class,
                () -> ConnectorRequestValidator.validate(request));

        assertTrue(error.getMessage().contains("sourceType"));
    }

    @Test
    void rejectsMismatchingOrMultipleNotificationEvents() {
        var mismatching = StoreNotificationRequest.builder()
                .eventType(StoreNotificationRequest.EventType.COMMAND_EXECUTION)
                .severity(StoreNotificationRequest.Severity.INFO)
                .assetStatus(StoreNotificationRequest.AssetStatusEvent.builder().sn("asset-sn").build())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> ConnectorRequestValidator.validate(mismatching));

        var multiple = StoreNotificationRequest.builder()
                .eventType(StoreNotificationRequest.EventType.ASSET_STATUS)
                .severity(StoreNotificationRequest.Severity.INFO)
                .assetStatus(StoreNotificationRequest.AssetStatusEvent.builder().sn("asset-sn").build())
                .commandExecution(StoreNotificationRequest.CommandExecutionEvent.builder()
                        .externalExecutionId("execution-1")
                        .status(StoreNotificationRequest.CommandExecutionStatus.COMMAND_EXECUTION_STATUS_RUNNING)
                        .assetSn("asset-sn")
                        .build())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> ConnectorRequestValidator.validate(multiple));
    }

    @Test
    void acceptsTypeSafeCommandExecutionNotification() {
        var request = StoreNotificationRequest.builder()
                .eventType(StoreNotificationRequest.EventType.COMMAND_EXECUTION)
                .severity(StoreNotificationRequest.Severity.WARN)
                .commandExecution(StoreNotificationRequest.CommandExecutionEvent.builder()
                        .externalExecutionId("execution-1")
                        .status(StoreNotificationRequest.CommandExecutionStatus.COMMAND_EXECUTION_STATUS_RUNNING)
                        .assetSn("asset-sn")
                        .build())
                .build();

        assertDoesNotThrow(() -> ConnectorRequestValidator.validate(request));
    }
}
