# Part 8 — Load Testing / Performance Testing

## Overview

Load testing using **k6** with realistic traffic patterns:

- **45 diverse product IDs** with 80/20 hot/cold distribution
- **5 "hot" products** get 80% of traffic → cache hits → p95 ~10ms
- **40 "cold" products** get 20% of traffic → cache misses → p95 ~200ms+
- **Aggressive SLA thresholds** — expected to fail initially, driving optimization

## Simulated Traffic Pattern

```
HOT (80%)   iphone-15, samsung-galaxy-s24, airpods-pro, macbook-air-m3, pixel-8
COLD (20%)  kindle-paperwhite, echo-dot, fire-tv-stick, ring-doorbell, ... (40 more)
```

This mirrors real e-commerce: trending products dominate, long-tail is sporadic.

## Planned Tests

- [x] **Baseline**: 1 VU, 30s, diverse products → p95=206ms (target: <100ms)
- [x] **Concurrent Load**: Ramping 10→50→100 VUs → thread pool stress
- [x] **Sustained Load**: 50 VUs, 10min → Redis pool exhaustion detection
- [x] **Cache Hit vs Miss**: 80/20 distribution → p95 hit <10ms, miss <500ms
- [x] **Circuit Breaker**: Chaos mid-test → graceful degradation under load

## Tools

- k6 (JavaScript, CLI-first, Git-friendly)
- Gradle task: `./gradlew k6 -Pscript=<name>`

## Current Baseline Results

| Metric | Actual | Target | Status |
|--------|--------|--------|--------|
| p95 latency | 206ms | <100ms | ❌ |
| p50 latency | 7.6ms | <200ms | ✅ |
| Error rate | 0.00% | <1% | ✅ |
| Throughput | 1.8 req/s | >5 req/s | ❌ |

## Optimization Roadmap

### Phase 1: Virtual Threads (Java 21+)
Replace platform thread pools with virtual threads for massive concurrency.

### Phase 2: Redis Connection Pool Tuning
Optimize Lettuce pool settings for sustained load.

### Phase 3: Parallel Vendor Fetch
Optimize CompletableFuture composition with virtual threads.

### Phase 4: Circuit Breaker Fallback Speed
Async fallback + connection pooling for faster graceful degradation.

## Status: 🚧 IN PROGRESS
