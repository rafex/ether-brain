package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void noopIsNeverCancelled() {
        assertFalse(CancellationToken.noop().isCancelled());
    }

    @Test
    void mutableStartsNotCancelled() {
        CancellationToken.Mutable token = CancellationToken.create();
        assertFalse(token.isCancelled());
    }

    @Test
    void mutableIsCancelledAfterCancel() {
        CancellationToken.Mutable token = CancellationToken.create();
        token.cancel();
        assertTrue(token.isCancelled());
    }

    @Test
    void cancelIsIdempotent() {
        CancellationToken.Mutable token = CancellationToken.create();
        token.cancel();
        token.cancel(); // second call must not throw
        assertTrue(token.isCancelled());
    }

    @Test
    void cancelFromSeparateThreadIsVisible() throws InterruptedException {
        CancellationToken.Mutable token = CancellationToken.create();
        Thread t = Thread.ofVirtual().start(token::cancel);
        t.join();
        assertTrue(token.isCancelled());
    }
}
