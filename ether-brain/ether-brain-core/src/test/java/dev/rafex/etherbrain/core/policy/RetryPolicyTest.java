package dev.rafex.etherbrain.core.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void noneNeverRetries() {
        RetryPolicy policy = RetryPolicy.none();
        assertFalse(policy.shouldRetry("tool", 0, new RuntimeException("fail")));
        assertFalse(policy.shouldRetry("tool", 5, new RuntimeException("fail")));
    }

    @Test
    void fixedRetriesUpToMax() {
        RetryPolicy policy = RetryPolicy.fixed(3, 0);
        assertTrue(policy.shouldRetry("t", 0, new RuntimeException()));
        assertTrue(policy.shouldRetry("t", 1, new RuntimeException()));
        assertTrue(policy.shouldRetry("t", 2, new RuntimeException()));
        assertFalse(policy.shouldRetry("t", 3, new RuntimeException()));
    }

    @Test
    void fixedUsesConstantDelay() {
        RetryPolicy policy = RetryPolicy.fixed(2, 250);
        // delay must be constant regardless of attempt
        org.junit.jupiter.api.Assertions.assertEquals(250, policy.retryDelayMillis(0));
        org.junit.jupiter.api.Assertions.assertEquals(250, policy.retryDelayMillis(1));
    }

    @Test
    void exponentialBackoffDoublesDelay() {
        RetryPolicy policy = RetryPolicy.exponentialBackoff(4, 100);
        org.junit.jupiter.api.Assertions.assertEquals(100,  policy.retryDelayMillis(0));
        org.junit.jupiter.api.Assertions.assertEquals(200,  policy.retryDelayMillis(1));
        org.junit.jupiter.api.Assertions.assertEquals(400,  policy.retryDelayMillis(2));
        org.junit.jupiter.api.Assertions.assertEquals(800,  policy.retryDelayMillis(3));
    }

    @Test
    void exponentialBackoffRetriesUpToMax() {
        RetryPolicy policy = RetryPolicy.exponentialBackoff(2, 50);
        assertTrue(policy.shouldRetry("t", 0, new RuntimeException()));
        assertTrue(policy.shouldRetry("t", 1, new RuntimeException()));
        assertFalse(policy.shouldRetry("t", 2, new RuntimeException()));
    }

    @Test
    void defaultRetryDelayIsZero() {
        // The noop policy uses the default interface method
        RetryPolicy policy = RetryPolicy.none();
        org.junit.jupiter.api.Assertions.assertEquals(0, policy.retryDelayMillis(0));
    }
}
