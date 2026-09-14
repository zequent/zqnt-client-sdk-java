package com.zqnt.sdk.client.missionautonomy.capabilities;

/** Application-level error returned by Mission Autonomy. */
public final class MissionAutonomyClientException extends RuntimeException {
    private final String errorCode;
    private final String transactionId;

    public MissionAutonomyClientException(String errorCode, String message, String transactionId) {
        super(message == null || message.isBlank() ? "Capability operation failed" : message);
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
