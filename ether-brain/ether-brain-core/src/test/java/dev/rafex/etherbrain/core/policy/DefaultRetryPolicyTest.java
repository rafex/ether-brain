package dev.rafex.etherbrain.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefaultRetryPolicyTest {

    @Test
    void defaultConstructorNeverRetries() {
        DefaultRetryPolicy policy = new DefaultRetryPolicy();
        assertEquals(0, policy.maxRetries());
        assertFalse(policy.shouldRetry("t", 0, new RuntimeException()));
    }

    @Test
    void retriesUpToMaxRetries() {
        DefaultRetryPolicy policy = new DefaultRetryPolicy(3, 0);
        assertTrue(policy.shouldRetry("t", 0, new RuntimeException()));
        assertTrue(policy.shouldRetry("t", 1, new RuntimeException()));
        assertTrue(policy.shouldRetry("t", 2, new RuntimeException()));
        assertFalse(policy.shouldRetry("t", 3, new RuntimeException()));
    }

    @Test
    void respectsConfiguredDelay() {
        DefaultRetryPolicy policy = new DefaultRetryPolicy(2, 750);
        assertEquals(750, policy.retryDelayMillis(0));
        assertEquals(750, policy.retryDelayMillis(1));
    }

    @Test
    void negativesClampedToZero() {
        DefaultRetryPolicy policy = new DefaultRetryPolicy(-5, -100);
        assertEquals(0, policy.maxRetries());
        assertEquals(0, policy.delayMillis());
        assertFalse(policy.shouldRetry("t", 0, new RuntimeException()));
    }

    @Test
    void toolNameIsIgnored() {
        // DefaultRetryPolicy does not discriminate by tool name
        DefaultRetryPolicy policy = new DefaultRetryPolicy(1, 0);
        assertTrue(policy.shouldRetry("tool_a", 0, new RuntimeException()));
        assertTrue(policy.shouldRetry("tool_b", 0, new RuntimeException()));
        assertFalse(policy.shouldRetry("tool_a", 1, new RuntimeException()));
    }
}
