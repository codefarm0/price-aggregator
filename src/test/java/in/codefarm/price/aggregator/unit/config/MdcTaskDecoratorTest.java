package in.codefarm.price.aggregator.unit.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import in.codefarm.price.aggregator.config.MdcTaskDecorator;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MdcTaskDecorator Unit Tests")
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    @DisplayName("Should propagate MDC context from parent to child thread")
    void shouldPropagateMdcToChildThread() throws InterruptedException {
        MDC.put("traceId", "test-trace-123");
        MDC.put("userId", "user-456");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> traceIdInChild = new AtomicReference<>();
        AtomicReference<String> userIdInChild = new AtomicReference<>();

        Runnable task = () -> {
            try {
                traceIdInChild.set(MDC.get("traceId"));
                userIdInChild.set(MDC.get("userId"));
            } finally {
                latch.countDown();
            }
        };

        Runnable wrappedTask = decorator.decorate(task);
        Thread childThread = new Thread(wrappedTask);
        childThread.start();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals("test-trace-123", traceIdInChild.get());
        assertEquals("user-456", userIdInChild.get());
    }

    @Test
    @DisplayName("Should capture traceId in child thread correctly")
    void shouldCaptureTraceIdInChildThread() throws InterruptedException {
        MDC.put("traceId", "test-trace-789");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> traceIdInChild = new AtomicReference<>();

        Runnable task = () -> {
            try {
                traceIdInChild.set(MDC.get("traceId"));
            } finally {
                latch.countDown();
            }
        };

        Runnable wrappedTask = decorator.decorate(task);
        Thread childThread = new Thread(wrappedTask);
        childThread.start();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals("test-trace-789", traceIdInChild.get());
    }

    @Test
    @DisplayName("Should return null when parent MDC context is empty")
    void shouldReturnNullWhenParentMdcIsEmpty() throws InterruptedException {
        MDC.clear();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> traceIdInChild = new AtomicReference<>("not-null");

        Runnable task = () -> {
            try {
                traceIdInChild.set(MDC.get("traceId"));
            } finally {
                latch.countDown();
            }
        };

        Runnable wrappedTask = decorator.decorate(task);
        Thread childThread = new Thread(wrappedTask);
        childThread.start();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertNull(traceIdInChild.get());
    }

    @Test
    @DisplayName("Should set parent traceId in child thread before task modification")
    void shouldSetParentTraceIdBeforeTaskModification() throws InterruptedException {
        MDC.put("traceId", "parent-trace");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> traceIdBefore = new AtomicReference<>();

        Runnable task = () -> {
            try {
                traceIdBefore.set(MDC.get("traceId"));
                MDC.put("traceId", "child-trace");
            } finally {
                latch.countDown();
            }
        };

        Runnable wrappedTask = decorator.decorate(task);
        Thread childThread = new Thread(wrappedTask);
        childThread.start();

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals("parent-trace", traceIdBefore.get());
    }
}
