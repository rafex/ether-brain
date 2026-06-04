package dev.rafex.etherbrain.common;

/**
 * Constants for the internal message format used to encode ASSISTANT
 * tool-call turns in the conversation history.
 *
 * <h2>ASSISTANT tool-call message format</h2>
 * When the model requests a tool call, {@code AgentLoop} stores it as an
 * ASSISTANT message with the content:
 * <pre>
 *   toolName + TOOL_CALL_SEP + argumentsJson
 * </pre>
 *
 * <h2>Why not the pipe character?</h2>
 * The pipe {@code |} is valid inside JSON string values, making it an
 * unreliable separator.  The chosen separator (ASCII 0x1E, Record Separator)
 * is a control character that the JSON specification forbids in unescaped
 * string values, so it can never occur in tool names or JSON arguments.
 *
 * <h2>Backward compatibility</h2>
 * Codecs fall back to {@code |} when 0x1E is absent, so existing session
 * files remain readable.
 */
public final class MessageConstants {

    private MessageConstants() {}

    /**
     * Separator between tool name and JSON arguments in ASSISTANT tool-call
     * message content.
     *
     * <p>Value: ASCII 0x1E (Record Separator — never appears in JSON strings).
     */
    public static final char TOOL_CALL_SEP = (char) 0x1E;

    /**
     * Legacy separator accepted on read for backward compatibility.
     * Never written by new code.
     */
    public static final char TOOL_CALL_SEP_LEGACY = '|';

    /**
     * Splits an ASSISTANT tool-call message content into
     * {@code [toolName, argumentsJson]}.
     *
     * <p>Tries {@link #TOOL_CALL_SEP} first; falls back to
     * {@link #TOOL_CALL_SEP_LEGACY} for sessions created by older versions.
     *
     * @param content {@code Message.content()} of an ASSISTANT tool-call turn
     * @return two-element array: {@code [0]=toolName}, {@code [1]=argumentsJson};
     *         {@code [1]} defaults to {@code "{}"} when no arguments are present.
     */
    public static String[] splitToolCall(String content) {
        if (content == null) return new String[]{"", "{}"};

        int idx = content.indexOf(TOOL_CALL_SEP);
        if (idx >= 0) {
            return new String[]{
                content.substring(0, idx),
                idx + 1 < content.length() ? content.substring(idx + 1) : "{}"
            };
        }

        idx = content.indexOf(TOOL_CALL_SEP_LEGACY);
        if (idx >= 0) {
            return new String[]{
                content.substring(0, idx),
                idx + 1 < content.length() ? content.substring(idx + 1) : "{}"
            };
        }

        return new String[]{content, "{}"};
    }
}
