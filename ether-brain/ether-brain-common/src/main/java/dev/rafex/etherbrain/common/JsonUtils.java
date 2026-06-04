package dev.rafex.etherbrain.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON utility that works without an external parser.
 *
 * <p>Use this only for simple, flat JSON objects where a full Jackson parse
 * is not available (e.g. in the {@code ether-brain-common} and
 * {@code ether-brain-core} modules that have no Jackson dependency).
 *
 * <p>For complex, nested JSON use Jackson directly in the infrastructure modules.
 */
public final class JsonUtils {

    private JsonUtils() {}

    /**
     * Extracts a top-level string field from a simple JSON object.
     *
     * <p>Handles standard JSON string escapes: {@code \"}, {@code \n}, {@code \t},
     * {@code \\}. Does <em>not</em> handle nested objects, arrays, or
     * unicode code-point escapes ({@code &#92;uXXXX}) in the value.
     *
     * <p>Returns {@code null} if the field is absent or not a string.
     *
     * <h3>Example</h3>
     * <pre>
     * JsonUtils.extractField("{\"message\":\"hello\\nworld\"}", "message")
     * // → "hello\nworld"
     * </pre>
     *
     * @param json      raw JSON text; may be {@code null}
     * @param fieldName field to extract
     * @return the unescaped string value, or {@code null} if not found
     */
    public static String extractField(String json, String fieldName) {
        if (json == null || json.isBlank()) return null;
        Matcher m = Pattern.compile(
                        "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!m.find()) return null;
        return unescape(m.group(1));
    }

    /**
     * Escapes a string for safe embedding inside a JSON string value.
     *
     * <p>Escapes: {@code \}, {@code "}, newline ({@code \n}),
     * carriage-return ({@code \r}), tab ({@code \t}).
     *
     * @param s the raw string; {@code null} returns the literal {@code "null"}
     * @return a JSON-string representation including the surrounding quotes
     */
    public static String toJsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String unescape(String raw) {
        return raw.replace("\\\"", "\"")
                  .replace("\\n",  "\n")
                  .replace("\\t",  "\t")
                  .replace("\\r",  "\r")
                  .replace("\\\\", "\\");
    }
}
