package com.zqnt.sdk.client.commands.domains;

import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.Error;
import com.zqnt.protos.common.v3.ErrorCategory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A command call the platform refused, a command it {@code REJECTED} before it started, or (from
 * {@code executeAndWait}) a run that ended {@code FAILED}, {@code CANCELLED} or {@code TIMED_OUT}.
 * {@link #getCategory()} says what kind of refusal, {@link #getCode()} the stable machine-readable
 * code (e.g. {@code command.invalid_params}), {@link #getResult()} the final result when there is one.
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
        return of(result);
    }

    /**
     * A command that did not succeed, from its final result: {@code REJECTED}, {@code FAILED},
     * {@code CANCELLED} or {@code TIMED_OUT}. Without a category on the error, a rejection counts as
     * {@code INVALID_ARGUMENT}, a failure as {@code ASSET}, a timeout as {@code TIMEOUT}.
     */
    public static CommandException of(CommandResult result) {
        Error error = result.getError();
        String message = error.getMessage().isBlank()
                ? result.getCommandId() + " " + outcomeOf(result.getState())
                : error.getMessage();
        ErrorCategory category = error.getCategory() == ErrorCategory.ERROR_CATEGORY_UNSPECIFIED
                ? defaultCategory(result.getState())
                : error.getCategory();
        return new CommandException(message, category, error.getCode(), error.getRetryable(), null, result, null);
    }

    public static CommandException waitTimedOut(String message) {
        return new CommandException(message, ErrorCategory.ERROR_CATEGORY_TIMEOUT, "", false, null, null, null);
    }

    public static CommandException watchEnded(String message) {
        return new CommandException(message, ErrorCategory.ERROR_CATEGORY_SERVICE, "", true, null, null, null);
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

    private static String outcomeOf(CommandState state) {
        return switch (state) {
            case COMMAND_STATE_FAILED -> "failed";
            case COMMAND_STATE_CANCELLED -> "was cancelled";
            case COMMAND_STATE_TIMED_OUT -> "timed out";
            default -> "was rejected";
        };
    }

    private static ErrorCategory defaultCategory(CommandState state) {
        return switch (state) {
            case COMMAND_STATE_FAILED -> ErrorCategory.ERROR_CATEGORY_ASSET;
            case COMMAND_STATE_TIMED_OUT -> ErrorCategory.ERROR_CATEGORY_TIMEOUT;
            case COMMAND_STATE_CANCELLED -> ErrorCategory.ERROR_CATEGORY_UNSPECIFIED;
            default -> ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT;
        };
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

    /** The command's final result; {@code null} when the call itself was refused or the wait ended without one. */
    public CommandResult getResult() {
        return result;
    }
}
