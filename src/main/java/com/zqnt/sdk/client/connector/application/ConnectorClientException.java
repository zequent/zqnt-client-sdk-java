package com.zqnt.sdk.client.connector.application;

/** Application-level error returned by Connector's raw-proto-typed endpoints (e.g. the Skill
 * Registry) — mirrors {@code MissionAutonomyClientException}. Connector's older, hand-mapped
 * endpoints don't use this; they surface errors via {@code ConnectorResponse}'s own error fields
 * instead. */
public final class ConnectorClientException extends RuntimeException {
    private final String errorCode;
    private final String transactionId;

    public ConnectorClientException(String errorCode, String message, String transactionId) {
        super(message == null || message.isBlank() ? "Connector operation failed" : message);
        this.errorCode = errorCode == null ? "" : errorCode;
        this.transactionId = transactionId == null ? "" : transactionId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
