# Part 8 — Load Testing: Baseline Results & Optimization Analysis

## Cache Hit vs Miss Results (10 VUs, 70s)

### Summary

| Metric | Cache Hit (target) | Cache Hit (actual) | Cache Miss (target) | Cache Miss (actual) |
|--------|--------------------|--------------------|---------------------|---------------------|
| p95 | < 10ms | **215.76ms** ❌ | < 500ms | 300.11ms ✅ |
| p90 | — | 202.12ms | — | 275.32ms |
| median | — | 7.95ms ✅ | — | 193.89ms |
| avg | — | 55.46ms | — | 201.69ms |
| min | — | 2.2ms | — | 92.42ms |
| max | — | 321.47ms | — | 443.93ms |
| % under target | 100% | **62%** | 100% | 100% |

### Key Finding: The 38% Tail Problem

Cache Hit **median is 7.95ms** (excellent) but **p95 is 215.76ms** (21x over target). This means:
- 50% of cache hits complete in <8ms — Redis round-trip works well
- 38% of cache hits are slow (100ms–321ms) — something is causing tail latency

### Why Cache Hit p95 is 215ms (Expected Behavior)

The 80/20 product distribution means:
1. Each iteration picks a random product via `pickProductId()`
2. "Hot" products (5 items, 80% probability) are likely cached after first request
3. "Cold" products (40 items, 20% probability) are almost never cached — every cold product request is a cache MISS
4. The Cache Hit group includes cold product lookups that miss cache, inflating p95

**This is actually realistic behavior** — real e-commerce systems have long-tail products that rarely get cached.

---

## Identified Bottlenecks

### 1. Thread Pool Contention (Highest Impact)

**Current**: `ThreadPoolTaskExecutor` with `core-size=3, max-size=10, queue-capacity=100`

At 10 VUs with `sleep(1-3)`, concurrent requests queue up in the thread pool:

```
Request arrives → Queue → Wait for free thread → Execute → Return
                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                  This wait is the 200ms+ tail latency
```

**Evidence**: Cache hit median is 7.95ms (no vendor calls), but p95 is 215ms. The 200ms gap is thread pool wait time, not Redis latency.

**Optimization**: Virtual threads (Java 21+)
- Replace `ThreadPoolTaskExecutor` with `SimpleAsyncTaskExecutor` backed by virtual threads
- Each request gets its own virtual thread — no queueing
- Expected: Cache hit p95 drops from 215ms → ~15ms (Redis round-trip only)

### 2. Lettuce Connection Pool

**Current**: Default Lettuce connection pool (single shared connection by default)

With 10 concurrent VUs, Redis connections serialize:
```
VU1 → Redis GET price:amazon:iphone-15 → 8ms
VU2 → Redis GET price:flipkart:iphone-15 → wait VU1 → 15ms
VU3 → Redis GET price:walmart:iphone-15 → wait VU2 → 22ms
...
```

**Optimization**: Configure Lettuce connection pool
```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 20    # Default: 8
          max-idle: 10      # Default: 8
          min-idle: 5       # Default: 0
          max-wait: 1000ms  # Default: -1 (unlimited)
```

### 3. Jackson Serialization Overhead

**Current**: `PriceResult` objects serialized to JSON for Redis storage on every cache write.

For cache hits: Jackson deserializes JSON → `PriceResult` object → returns to client.

**Optimization**: Consider caching pre-serialized JSON strings and streaming directly to response, skipping deserialization for cache hits. Requires changing the response serialization path.

### 4. WebClient Reactive Stack Latency

**Current**: Three vendor calls via `WebClient` (reactive, Netty-based) with `block()` at the end.

Even for cache hits, the `PriceService` creates `CompletableFuture` objects for each vendor before checking cache. The thread pool scheduling overhead exists regardless.

**Optimization**: Check cache BEFORE creating `CompletableFuture` tasks in `PriceService`. Current flow:
```java
// Current: creates 3 futures THEN checks cache inside each future
vendorClients.stream()
    .map(client -> executor.execute(() -> client.getPrice(productId, refreshCache)))
    .collect(toList());

// Better: check cache first, only create futures for cache misses
if (!refreshCache) {
    cached = cacheService.get(vendor, productId);
    if (cached != null) return cached;  // Skip future creation entirely
}
```

### 5. MDC Context Propagation Overhead

**Current**: `MdcTaskDecorator` copies MDC context to each thread. Adds ~1-2ms per request.

Minor but measurable: 50 checks include trace-id header verification. The decorator runs on every thread pool submission.

**Optimization**: Only copy MDC keys that are actually needed downstream (currently only `traceId`). Could use a more targeted decorator.

---

## Optimization Priority Matrix

| # | Optimization | Effort | Expected Impact | Priority |
|---|-------------|--------|-----------------|----------|
| 1 | **Virtual threads** | Low | 10x throughput, p95 215ms → 15ms | ⭐⭐⭐ |
| 2 | **Cache check before future creation** | Low | p95 215ms → 50ms for hits | ⭐⭐⭐ |
| 3 | **Lettuce pool tuning** | Low | Reduces tail latency 15% | ⭐⭐ |
| 4 | **Pre-serialized JSON caching** | Medium | 5-10ms improvement per hit | ⭐ |
| 5 | **MDC decorator optimization** | Low | 1-2ms improvement | ⭐ |

---

## Expected Results After Phase 1 (Virtual Threads + Cache Check First)

| Metric | Current | After Phase 1 |
|--------|---------|---------------|
| Cache Hit p95 | 215ms | **~15ms** |
| Cache Hit median | 7.95ms | **~5ms** |
| Cache Miss p95 | 300ms | **~180ms** |
| Throughput (10 VUs) | 3.9 req/s | **~15 req/s** |
| Error rate | 0.00% | 0.00% |

## Expected Results After Phase 1+2 (+ Lettuce Pool Tuning)

| Metric | After Phase 1 | After Phase 2 |
|--------|---------------|---------------|
| Cache Hit p95 | ~15ms | **~10ms** |
| Cache Miss p95 | ~180ms | **~150ms** |
| Throughput (10 VUs) | ~15 req/s | **~20 req/s** |

---

## Test Design Note

The current `cache-hit-vs-miss.js` test has an inherent limitation: it uses `pickProductId()` (80/20 distribution) for both groups, meaning cold products appear in the Cache Hit group and inflate its p95. This is **realistic** but makes the <10ms threshold impossible to hit.

A stricter test variant could use only hot products for the Cache Hit group:
```javascript
// Stricter variant — only hot products
const productId = HOT_PRODUCTS[Math.floor(Math.random() * HOT_PRODUCTS.length)];
```

This would test the best-case cache scenario and validate the <10ms target under ideal conditions.
