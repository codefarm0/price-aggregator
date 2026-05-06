# Building an MDC-Aware Thread Pool for Traced Async Price Fetching in Spring Boot

Building a price aggregator sounds simple — call 3 vendor APIs, compare prices, return results. But when you need to call Amazon, Flipkart, and Walmart **in parallel** while maintaining **distributed tracing** across threads, things get interesting.

## Why This Matters

Imagine a user searches for "iPhone 15". Your service needs to:
1. Call Amazon API (500ms)
2. Call Flipkart API (500ms)  
3. Call Walmart API (500ms)

**Sequential calls:** 1.5 seconds (too slow!)
**Parallel calls:** 500ms (much better!)

But here's the catch: **How do you trace a request across multiple threads?**

When a request hits your controller, you generate a `traceId` and store it in MDC (Mapped Diagnostic Context). This works great — until you spawn async threads. MDC uses `ThreadLocal` under the hood, which means **it doesn't propagate to child threads**.

So when your async tasks run in different threads, the `traceId` is lost. Your logs become a nightmare to debug because you can't correlate log lines across threads for the same request.

In this article, I'll show you how I solved this with:
1. A custom thread pool (`ThreadPoolTaskExecutor`) for price fetching
2. An `MdcTaskDecorator` that propagates MDC context to async threads
3. Structured logging that shows `traceId` across all threads

By the end, you'll see log output like this:

```
2026-04-28 10:00:00.123 [http-nio-8080-exec-1][price-aggregator][traceId=abc-123] INFO  PriceController - GET /api/prices/iphone-15
2026-04-28 10:00:00.456 [price-fetch-1][price-aggregator][traceId=abc-123] INFO  AmazonClient - [AMAZON] productId=iphone-15
2026-04-28 10:00:00.789 [price-fetch-2][price-aggregator][traceId=abc-123] INFO  FlipkartClient - [FLIPKART] productId=iphone-15
```

Same `traceId` across ALL threads. Perfect for debugging!

## The Problem

Imagine you're building a price comparison service. When a user searches for "iPhone 15", you need to fetch prices from Amazon, Flipkart, and Walmart. If each API call takes 500ms, sequential calls would take 1.5 seconds. Call them in parallel? Now you're down to 500ms.

But there's a catch: **How do you trace a request across multiple threads?**

When a request hits your controller, you generate a `traceId` and store it in MDC (Mapped Diagnostic Context). But MDC uses `ThreadLocal` — it doesn't propagate to child threads. So when your async tasks run in different threads, the `traceId` is lost.

**Result:** Your logs become a nightmare to debug. You can't correlate log lines across threads for the same request.

## The Solution: A Custom Thread Pool with MDC Propagation

Here's what we'll build:

1. A custom thread pool (`ThreadPoolTaskExecutor`) for price fetching
2. An `MdcTaskDecorator` that propagates MDC context to async threads
3. Structured logging that shows `traceId` across all threads

## The Architecture

```
User Request
    ↓
PriceController (main thread - traceId in MDC)
    ↓
PriceService.fetchPrices()
    ↓
CompletableFuture.supplyAsync(() -> ..., priceTaskExecutor)
    ↓
priceTaskExecutor (custom thread pool)
    ↓
MdcTaskDecorator (propagates MDC to child thread)
    ↓
AmazonClient / FlipkartClient / WalmartClient (async threads - traceId available!)
```

## 1. The Thread Pool Configuration

First, we configure a custom thread pool in `PriceConfig.java`:

```java
@Configuration
@EnableAsync
public class PriceConfig {

    @Value("${price.pool.core-size:3}")
    private int corePoolSize;

    @Value("${price.pool.max-size:10}")
    private int maxPoolSize;

    @Value("${price.pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${price.pool.thread-name-prefix:price-fetch-}")
    private String threadNamePrefix;

    @Bean(name = "priceTaskExecutor")
    public Executor priceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);      // 3 always-alive threads
        executor.setMaxPoolSize(maxPoolSize);         // Max 10 threads under load
        executor.setQueueCapacity(queueCapacity);      // 100 tasks queue up
        executor.setThreadNamePrefix(threadNamePrefix); // "price-fetch-1", "price-fetch-2", ...
        
        // Custom thread factory
        executor.setThreadFactory(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, threadNamePrefix + counter.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        });
        
        // THIS IS THE KEY: MDC propagation
        executor.setTaskDecorator(new MdcTaskDecorator());
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
```

### Why Not Use the Default ForkJoinPool?

When you use `CompletableFuture.supplyAsync(() -> ...)` without specifying an executor, it uses `ForkJoinPool.commonPool()`. This is bad for several reasons:

1. **No isolation**: One component can starve others by hogging all threads
2. **No customization**: Can't set thread names, queue size, etc.
3. **No monitoring**: Hard to see what's running in common pool

With our custom pool:
- Thread names like `price-fetch-1` make debugging easy
- Configurable pool size based on expected load
- Isolation: Price fetching won't interfere with other async tasks

## 2. The MDC Propagation Problem

MDC (Mapped Diagnostic Context) is a SLF4J feature that lets you store key-value pairs per thread. It's perfect for storing `traceId`:

```java
// In TraceIdFilter (runs on main HTTP thread)
MDC.put("traceId", "abc-123");
```

But here's the problem:

```java
// In PriceService
public List<PriceResult> fetchPrices(String productId) {
    String traceId = MDC.get("traceId"); // "abc-123" - works!
    
    CompletableFuture<PriceResult> future = CompletableFuture.supplyAsync(() -> {
        String asyncTraceId = MDC.get("traceId"); // NULL! Different thread!
        return amazonClient.getPrice(productId);
    }, priceTaskExecutor);
}
```

**Why?** MDC uses `ThreadLocal` under the hood. Each thread has its own copy. When a new thread is created, it starts with an empty MDC.

## 3. The Solution: MdcTaskDecorator

Spring's `TaskDecorator` interface lets you wrap tasks before they're executed. Here's how we use it to propagate MDC:

```java
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // STEP 1: Capture parent thread's MDC context
        Map<String, String> parentMdcContext = MDC.getCopyOfContextMap();

        return () -> {
            // STEP 2: Preserve any existing MDC in child thread (for safety)
            Map<String, String> originalChildMdcContext = MDC.getCopyOfContextMap();
            
            try {
                // STEP 3: Restore parent MDC in child thread
                if (parentMdcContext != null) {
                    MDC.setContextMap(parentMdcContext);
                } else {
                    MDC.clear();
                }
                
                // STEP 4: Execute the original task
                runnable.run();
                
            } finally {
                // STEP 5: Restore original child MDC (prevent leaks)
                if (originalChildMdcContext != null) {
                    MDC.setContextMap(originalChildMdcContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
```

### How It Works (Step by Step):

1. **Before submitting the task**: Capture the parent thread's MDC context (contains `traceId=abc-123`)
2. **When task starts in child thread**: Save any existing MDC (just in case), then restore parent's MDC
3. **Task executes**: Now `MDC.get("traceId")` returns `"abc-123"` in the child thread!
4. **After task completes**: Restore original child MDC to prevent memory leaks

## 4. Using the Thread Pool in PriceService

Now let's see how `PriceService` uses this:

```java
@Service
public class PriceService {
    
    private final List<PriceAggregator> priceAggregators;
    private final Executor priceTaskExecutor;
    private final long timeoutMs;

    public PriceService(
            List<PriceAggregator> priceAggregators,
            @Qualifier("priceTaskExecutor") Executor priceTaskExecutor,
            @Value("${price.fetch.timeout-ms:1000}") long timeoutMs) {
        this.priceAggregators = priceAggregators;
        this.priceTaskExecutor = priceTaskExecutor;
        this.timeoutMs = timeoutMs;
    }

    public List<PriceResult> fetchPrices(String productId, boolean refreshCache) {
        long start = Instant.now().toEpochMilli();
        
        // Create async tasks for each vendor
        List<CompletableFuture<PriceResult>> futures = priceAggregators.stream()
                .map(client -> fetchWithFallback(client, productId, refreshCache))
                .toList();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect results
        List<PriceResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long duration = Instant.now().toEpochMilli() - start;
        log.info("Fetched {} results for product={} in {}ms", results.size(), productId, duration);

        return results;
    }

    private CompletableFuture<PriceResult> fetchWithFallback(
            PriceAggregator client, String productId, boolean refreshCache) {
        return CompletableFuture.supplyAsync(() -> {
            // MDC is automatically available here (thanks to MdcTaskDecorator)
            return client.getPrice(productId, refreshCache);
        }, priceTaskExecutor)  // <-- Custom executor with MDC propagation
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch price from {}, using fallback", 
                            client.getClass().getSimpleName());
                    return client.getFallbackPrice(productId, ex);
                });
    }
}
```

## 5. Setting Up TraceId (The Entry Point)

Before the thread pool can propagate MDC, we need to set the `traceId` in the first place. This happens in `TraceIdFilter.java`:

```java
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain) throws IOException, ServletException {

        // Generate or read traceId from header
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        // Set MDC for logging
        MDC.put(TRACE_ID, traceId);
        
        // Set response header so caller can see the traceId
        response.setHeader("X-Trace-Id", traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear(); // Always clean up!
        }
    }
}
```

This filter:
1. **Generates** a new `traceId` if not provided
2. **Stores** it in MDC (so logs pick it up)
3. **Returns** it in the response header
4. **Clears** MDC after request completes (prevents leaks)

## 6. Structured Logging Configuration

To make the `traceId` appear in every log line, configure `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATTERN" 
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread][%property{app-name}][traceId=%X{traceId:-N/A}] %-5level %logger{36} - %msg%n"/>

    <springProperty scope="context" name="app-name" 
                  source="spring.application.name" 
                  defaultValue="price-aggregator"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

**Key points:**
- `%X{traceId:-N/A}` reads from MDC (shows "N/A" if not set)
- `%thread` shows which thread (e.g., `price-fetch-1`)
- `%d{yyyy-MM-dd HH:mm:ss.SSS}` adds timestamp

## 7. The Result: Traced Logs Across All Threads

With everything wired up, here's what the logs look like:

```
2026-04-28 10:00:00.123 [http-nio-8080-exec-1][price-aggregator][traceId=abc-123] INFO  PriceController - GET /api/prices/iphone-15 refreshCache=false
2026-04-28 10:00:00.456 [price-fetch-1][price-aggregator][traceId=abc-123] INFO  AmazonClient - [AMAZON] productId=iphone-15 source=API price=999.99
2026-04-28 10:00:00.789 [price-fetch-2][price-aggregator][traceId=abc-123] INFO  FlipkartClient - [FLIPKART] productId=iphone-15 source=API price=899.99
2026-04-28 10:00:01.012 [price-fetch-3][price-aggregator][traceId=abc-123] WARN  WalmartClient - [WALMART] circuit breaker triggered
```

**Notice:**
- Same `traceId=abc-123` across ALL threads
- Thread names clearly show the component (`price-fetch-1`, `price-fetch-2`, etc.)
- Easy to grep all logs for a specific request: `grep "traceId=abc-123" logs/app.log`
- TraceId also returned in `X-Trace-Id` response header

## 6. Configuration (application.yaml)

```yaml
price:
  pool:
    core-size: 3          # Always-alive threads
    max-size: 10          # Max threads under load
    queue-capacity: 100    # Queue size before spawning new threads
    thread-name-prefix: price-fetch-
  fetch:
    timeout-ms: 2000      # Max time for each vendor call
```

## 8. Complete Request Flow (Putting It All Together)

Here's how a single request flows through the system:

```
1. HTTP Request arrives at /api/prices/iphone-15
   ↓
2. TraceIdFilter generates traceId="abc-123", sets MDC, response header
   ↓
3. PriceController.getPrices() is called (thread: http-nio-8080-exec-1)
   ↓
4. PriceService.fetchPrices() creates 3 CompletableFuture tasks
   ↓
5. Tasks submitted to priceTaskExecutor (custom thread pool)
   ↓
6. MdcTaskDecorator captures MDC from parent thread
   ↓
7. Tasks execute in price-fetch-1, price-fetch-2, price-fetch-3
   ↓
8. MdcTaskDecorator restores parent MDC in each child thread
   ↓
9. AmazonClient, FlipkartClient, WalmartClient run with traceId available
   ↓
10. Logs from ALL threads show [traceId=abc-123]
   ↓
11. Results collected, returned to controller
   ↓
12. Response includes X-Trace-Id=abc-123 header
```

## Key Takeaways

✅ **Always use a custom thread pool** for async tasks (not `ForkJoinPool.commonPool()`)

✅ **MDC doesn't propagate to child threads** — it uses `ThreadLocal`

✅ **TaskDecorator is the Spring way** to add cross-cutting concerns to async tasks

✅ **Capture AND restore MDC** to prevent memory leaks

✅ **traceId in logs = easier debugging** in production

✅ **Structured logging** with timestamp + thread + traceId makes logs grep-friendly

## Conclusion

Building async systems with proper tracing isn't just about performance — it's about **observability**. When something goes wrong in production (and it will), you'll thank yourself for having:

1. **Custom thread pools** with meaningful thread names
2. **MDC propagation** across async boundaries
3. **Structured logs** that you can grep by traceId

The pattern shown here (TaskDecorator + MDC) applies to any async processing in Spring — not just price aggregation. Use it for:
- Calling multiple microservices in parallel
- Processing Kafka messages with tracing
- Running scheduled tasks with context propagation

## Further Learning

Want to dive deeper? Check out this YouTube playlist for more on Resilience4j, microservices patterns, and distributed tracing:

📺 **[Resilience4j & Microservices Playlist](https://www.youtube.com/playlist?list=YOUR_PLAYLIST_ID)** *(Replace with actual playlist link)*

**Related topics to explore:**
- Resilience4j Circuit Breaker patterns (Part 4 of this project)
- Bulkhead pattern — isolating thread pools per vendor (Part 6)
- Distributed tracing with Micrometer/Zipkin (Part 11)
- ELK Stack for centralized log analysis (Part 12)

---

**Full code available at:** [github.com/codefarm0/price-aggregator](https://github.com/codefarm0/price-aggregator)

**Key files:**
- `src/main/java/in/codefarm/price/aggregator/config/PriceConfig.java` — Thread pool config
- `src/main/java/in/codefarm/price/aggregator/config/MdcTaskDecorator.java` — MDC propagation
- `src/main/java/in/codefarm/price/aggregator/service/PriceService.java` — Async price fetching
- `src/main/java/in/codefarm/price/aggregator/config/TraceIdFilter.java` — TraceId setup
- `src/main/resources/logback-spring.xml` — Structured logging config

---

*If you found this helpful, follow me for more Spring Boot and microservices content!*
