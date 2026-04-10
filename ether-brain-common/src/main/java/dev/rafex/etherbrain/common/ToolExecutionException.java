package dev.rafex.etherbrain.common;

public final class ToolExecutionException extends AgentException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
