package com.zqnt.sdk.client.commands.domains;

import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.common.v3.Error;
import com.zqnt.protos.common.v3.ErrorCategory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A command call the platform refused, or a command it {@code REJECTED} before it started.
 * {@link #getCategory()} says what kind of refusal, {@link #getCode()} the stable machine-readable
 * code (e.g. {@code command.invalid_params}), {@link #getResult()} the rejected result when there is one.
 */
public final class CommandException extends RuntimeException {

    private final ErrorCategory category;
    private final String code;
    private final boolean retryable;
    private final Status.Code status;
    private final transient CommandResult result;

    private CommandException(String message, ErrorCategory category, String code, boolean retryable,
                             Status.Code status, CommandResult result, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.code = code;
        this.retryable = retryable;
        this.status = status;
        this.result = result;
    }

    public static CommandException rejected(CommandResult result) {
        Error error = result.getError();
        String message = error.getMessage().isBlank()
                ? result.getCommandId() + " was rejected"
                : error.getMessage();
        ErrorCategory category = error.getCategory() == ErrorCategory.ERROR_CATEGORY_UNSPECIFIED
                ? ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT
                : error.getCategory();
        return new CommandException(message, category, error.getCode(), error.getRetryable(), null, result, null);
    }

    public static CommandException fromStatus(StatusRuntimeException failure) {
        Status status = failure.getStatus();
        String message = status.getDescription() == null ? status.getCode().name() : status.getDescription();
        return new CommandException(message, categoryOf(status.getCode()), status.getCode().name(),
                isRetryable(status.getCode()), status.getCode(), null, failure);
    }

    public static CommandException from(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CommandException command) return command;
            if (cause instanceof StatusRuntimeException status) return fromStatus(status);
        }
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new CommandException(message, ErrorCategory.ERROR_CATEGORY_SERVICE, "", true, null, null, failure);
    }

    static ErrorCategory categoryOf(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT;
            case PERMISSION_DENIED, UNAUTHENTICATED -> ErrorCategory.ERROR_CATEGORY_PERMISSION_DENIED;
            case FAILED_PRECONDITION, ABORTED, ALREADY_EXISTS -> ErrorCategory.ERROR_CATEGORY_PRECONDITION_FAILED;
            case NOT_FOUND, UNIMPLEMENTED -> ErrorCategory.ERROR_CATEGORY_NOT_FOUND;
            case DEADLINE_EXCEEDED -> ErrorCategory.ERROR_CATEGORY_TIMEOUT;
            default -> ErrorCategory.ERROR_CATEGORY_SERVICE;
        };
    }

    private static boolean isRetryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** The gRPC status the platform answered with; {@code null} for a rejected command. */
    public Status.Code getStatus() {
        return status;
    }

    /** The rejected command's result; {@code null} when the call itself was refused. */
    public CommandResult getResult() {
        return result;
    }
}
