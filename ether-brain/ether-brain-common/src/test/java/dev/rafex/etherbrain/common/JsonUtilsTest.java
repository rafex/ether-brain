package dev.rafex.etherbrain.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonUtilsTest {

    // ── extractField ──────────────────────────────────────────────────────────

    @Test
    void extractsSimpleField() {
        assertEquals("hello", JsonUtils.extractField("{\"key\":\"hello\"}", "key"));
    }

    @Test
    void extractsFieldWithSpacesAroundColon() {
        assertEquals("value", JsonUtils.extractField("{ \"key\" : \"value\" }", "key"));
    }

    @Test
    void extractsFirstMatchWhenMultipleFields() {
        String json = "{\"a\":\"1\",\"b\":\"2\",\"a\":\"3\"}";
        assertEquals("1", JsonUtils.extractField(json, "a"));
    }

    @Test
    void returnsNullForMissingField() {
        assertNull(JsonUtils.extractField("{\"other\":\"x\"}", "missing"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(JsonUtils.extractField(null, "key"));
    }

    @Test
    void returnsNullForBlankInput() {
        assertNull(JsonUtils.extractField("   ", "key"));
    }

    @Test
    void unescapesDoubleQuote() {
        String json = "{\"msg\":\"say \\\"hi\\\"\"}";
        assertEquals("say \"hi\"", JsonUtils.extractField(json, "msg"));
    }

    @Test
    void unescapesNewline() {
        assertEquals("line1\nline2",
                JsonUtils.extractField("{\"msg\":\"line1\\nline2\"}", "msg"));
    }

    @Test
    void unescapesTab() {
        assertEquals("col1\tcol2",
                JsonUtils.extractField("{\"msg\":\"col1\\tcol2\"}", "msg"));
    }

    @Test
    void unescapesCarriageReturn() {
        assertEquals("a\rb",
                JsonUtils.extractField("{\"msg\":\"a\\rb\"}", "msg"));
    }

    @Test
    void unescapesBackslash() {
        assertEquals("C:\\path",
                JsonUtils.extractField("{\"msg\":\"C:\\\\path\"}", "msg"));
    }

    @Test
    void fieldNameWithSpecialRegexChars() {
        // field name contains a dot — should be quoted correctly
        assertNull(JsonUtils.extractField("{\"a.b\":\"v\"}", "a"));
        assertEquals("v", JsonUtils.extractField("{\"a.b\":\"v\"}", "a.b"));
    }

    // ── toJsonString ──────────────────────────────────────────────────────────

    @Test
    void wrapsSimpleStringInQuotes() {
        assertEquals("\"hello\"", JsonUtils.toJsonString("hello"));
    }

    @Test
    void returnsNullLiteralForNull() {
        assertEquals("null", JsonUtils.toJsonString(null));
    }

    @Test
    void escapesDoubleQuote() {
        assertEquals("\"say \\\"hi\\\"\"", JsonUtils.toJsonString("say \"hi\""));
    }

    @Test
    void escapesBackslash() {
        assertEquals("\"C:\\\\path\"", JsonUtils.toJsonString("C:\\path"));
    }

    @Test
    void escapesNewline() {
        String result = JsonUtils.toJsonString("a\nb");
        assertEquals("\"a\\nb\"", result);
    }

    @Test
    void escapesCarriageReturn() {
        assertEquals("\"a\\rb\"", JsonUtils.toJsonString("a\rb"));
    }

    @Test
    void escapesTab() {
        assertEquals("\"a\\tb\"", JsonUtils.toJsonString("a\tb"));
    }

    @Test
    void roundTripExtractAndSerialize() {
        String original  = "hello\nworld \"test\"";
        String serialized = JsonUtils.toJsonString(original);
        String json      = "{\"msg\":" + serialized + "}";
        assertEquals(original, JsonUtils.extractField(json, "msg"));
    }
}
