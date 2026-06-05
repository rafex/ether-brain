package dev.rafex.etherbrain.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class MessageConstantsTest {

    private static final char SEP    = MessageConstants.TOOL_CALL_SEP;
    private static final char LEGACY = MessageConstants.TOOL_CALL_SEP_LEGACY;

    // ── Separator values ──────────────────────────────────────────────────────

    @Test
    void separatorIsControlChar0x1E() {
        assertEquals((char) 0x1E, SEP);
    }

    @Test
    void legacySeparatorIsPipe() {
        assertEquals('|', LEGACY);
    }

    @Test
    void separatorsAreDifferent() {
        assertNotEquals(SEP, LEGACY);
    }

    // ── splitToolCall — new separator ─────────────────────────────────────────

    @Test
    void splitsWithNewSeparator() {
        String content = "search_web" + SEP + "{\"q\":\"java\"}";
        String[] parts = MessageConstants.splitToolCall(content);
        assertArrayEquals(new String[]{"search_web", "{\"q\":\"java\"}"}, parts);
    }

    @Test
    void splitsWithNewSeparatorAndEmptyArgs() {
        String content = "current_time" + SEP + "{}";
        String[] parts = MessageConstants.splitToolCall(content);
        assertArrayEquals(new String[]{"current_time", "{}"}, parts);
    }

    @Test
    void argsDefaultToEmptyObjectWhenMissingSep() {
        // Only tool name, no separator
        String[] parts = MessageConstants.splitToolCall("echo");
        assertArrayEquals(new String[]{"echo", "{}"}, parts);
    }

    @Test
    void argsDefaultToEmptyObjectWhenNothingAfterNewSep() {
        String content = "mytool" + SEP;
        String[] parts = MessageConstants.splitToolCall(content);
        assertEquals("mytool", parts[0]);
        assertEquals("{}", parts[1]);
    }

    // ── splitToolCall — legacy separator ──────────────────────────────────────

    @Test
    void splitsWithLegacySeparator() {
        String content = "echo|hello world";
        String[] parts = MessageConstants.splitToolCall(content);
        assertArrayEquals(new String[]{"echo", "hello world"}, parts);
    }

    @Test
    void legacySplitOnlyOnFirstPipe() {
        // Arguments JSON might contain pipe characters (unlikely but possible)
        String content = "tool|{\"a\":\"x|y\"}";
        String[] parts = MessageConstants.splitToolCall(content);
        assertEquals("tool", parts[0]);
        assertEquals("{\"a\":\"x|y\"}", parts[1]);
    }

    @Test
    void newSeparatorTakesPrecedenceOverLegacy() {
        // content has both — new sep should win
        String content = "tool" + SEP + "{\"val\":\"pipe|here\"}";
        String[] parts = MessageConstants.splitToolCall(content);
        assertEquals("tool", parts[0]);
        assertEquals("{\"val\":\"pipe|here\"}", parts[1]);
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullContentReturnsEmptyToolAndEmptyArgs() {
        String[] parts = MessageConstants.splitToolCall(null);
        assertArrayEquals(new String[]{"", "{}"}, parts);
    }

    @Test
    void alwaysReturnsTwoElements() {
        assertEquals(2, MessageConstants.splitToolCall("abc").length);
        assertEquals(2, MessageConstants.splitToolCall("a|b").length);
        assertEquals(2, MessageConstants.splitToolCall("a" + SEP + "b").length);
        assertEquals(2, MessageConstants.splitToolCall(null).length);
    }
}
