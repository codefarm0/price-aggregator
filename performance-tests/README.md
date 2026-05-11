# Part 8 — Load Testing / Performance Testing with k6

## Overview

Performance testing using **k6** — a modern, developer-friendly load testing tool that uses JavaScript for test scripts. k6 was chosen over JMeter for:

- **Code-based tests**: Scripts are plain JavaScript, version-controlled alongside the application
- **CI/CD ready**: CLI-native, integrates seamlessly with Gradle and GitHub Actions
- **Low learning curve**: Familiar JS syntax, no GUI overhead
- **Docker support**: `docker run grafana/k6` if k6 isn't installed locally

## Prerequisites

### Option 1: Install k6 (Recommended)

```bash
# macOS
brew install k6

# Ubuntu/Debian
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6

# Verify
k6 version
```

### Option 2: Use Docker (No Install)

```bash
docker run --rm -i grafana/k6:latest version
```

The Gradle task automatically falls back to Docker if k6 isn't installed locally.

### Application Setup

1. Start Redis: `docker run -d -p 6379:6379 redis:7-alpine`
2. Start vendor mock services (amazon-mock, flipkart-mock, walmart-mock)
3. Start the price-aggregator: `./gradlew bootRun`
4. Verify: `curl http://localhost:8080/actuator/health`

## Project Structure

```
performance-tests/
├── README.md               # This file
├── common/
│   └── config.js           # Shared config (base URL, headers, endpoints)
├── baseline.js             # Single VU smoke test
├── concurrent-load.js      # Ramping 10→50→100 VUs
├── sustained-load.js       # 50 VUs, 10 minutes
├── cache-hit-vs-miss.js    # Compare cached vs uncached performance
└── circuit-breaker.js      # Chaos mid-test, observe degradation
```

## Running Tests

### Via Gradle

```bash
# Run baseline test
./gradlew k6 -Pscript=baseline

# Run concurrent load test
./gradlew k6 -Pscript=concurrent-load

# Run with custom args
./gradlew k6 -Pscript=concurrent-load -Pargs="--vus 200 --duration 2m"

# Run circuit breaker test
./gradlew k6 -Pscript=circuit-breaker

# Custom base URL
K6_BASE_URL=http://staging-server:8080 ./gradlew k6 -Pscript=baseline
```

### Via k6 CLI

```bash
k6 run performance-tests/baseline.js
k6 run performance-tests/concurrent-load.js
k6 run performance-tests/cache-hit-vs-miss.js --vus 50
```

### Via Docker

```bash
docker run --rm -v ./performance-tests:/scripts -i grafana/k6:latest run /scripts/baseline.js
```

## Test Scenarios

### 1. Baseline (`baseline.js`)

**Purpose**: Smoke test and establish performance baseline with a single virtual user.

```javascript
export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<100'],  // 95% of requests under 100ms
    http_req_failed: ['rate<0.01'],    // Less than 1% errors
  },
};
```

**What to look for**:
- p95 latency should be well under 100ms for a healthy system
- 0% error rate
- Trace ID present in every response

**Expected bottleneck**: Thread pool scheduling + Redis round-trip overhead may cause occasional spikes over 100ms.

---

### 2. Concurrent Load (`concurrent-load.js`)

**Purpose**: Stress test the thread pool configuration (`core-size=3, max-size=10, queue-capacity=100`).

```javascript
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // Warm-up
    { duration: '1m', target: 50 },    // Medium load
    { duration: '30s', target: 100 },  // Heavy load
    { duration: '30s', target: 0 },    // Cool-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],  // 95% under 500ms
    http_req_failed: ['rate<0.01'],    // Less than 1% errors
  },
};
```

**What to look for**:
- At 10 VUs: Should pass easily
- At 50 VUs: Thread pool (max=10) becomes the bottleneck, queue fills up
- At 100 VUs: Queue overflow likely, errors expected

**Expected bottleneck**: Fixed thread pool with `max-size=10` cannot handle 50+ concurrent requests. Requests queue up, latency increases exponentially.

---

### 3. Sustained Load (`sustained-load.js`)

**Purpose**: Long-running test to detect memory leaks, Redis connection pool exhaustion, and thread starvation.

```javascript
export const options = {
  vus: 50,
  duration: '10m',
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.005'],   // Less than 0.5% errors over 10 minutes
  },
};
```

**What to look for**:
- Error rate should remain stable (no creeping up)
- p95 latency should not increase over time
- Redis connection count should remain bounded
- JVM heap should stay within bounds

**Expected bottleneck**: Redis connection pool limits, potential Lettuce connection leaks under sustained load.

---

### 4. Cache Hit vs Miss (`cache-hit-vs-miss.js`)

**Purpose**: Quantify the performance benefit of caching by comparing cached responses vs fresh API calls.

```javascript
export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '1m', target: 10 },
  ],
  thresholds: {
    'http_req_duration{group:::Cache Hit}': ['p(95)<10'],    // Cached: <10ms
    'http_req_duration{group:::Cache Miss}': ['p(95)<500'],  // Fresh: <500ms
  },
};
```

**What to look for**:
- Cache Hit group should be dramatically faster than Cache Miss
- Cache Hit p95 should be in the single-digit milliseconds
- Cache Miss p95 depends on vendor API response times

**Expected bottleneck**: Even with caching, Redis round-trip + deserialization adds overhead. Target: <10ms p95 for cached responses.

---

### 5. Circuit Breaker Under Load (`circuit-breaker.js`)

**Purpose**: Validate graceful degradation under vendor failures. The test toggles chaos mid-test:

| Phase | Time | Action | Expected Behavior |
|-------|------|--------|-------------------|
| 1 | 0-30s | Normal requests | Baseline metrics, all succeed |
| 2 | 30-35s | Enable chaos (fast-failures) | Circuit starts accumulating failures |
| 3 | 35-90s | Requests continue | Circuit opens, graceful degradation |
| 4 | 90-95s | Reset chaos | Circuit transitions to HALF_OPEN |
| 5 | 95-120s | Recovery | Circuit closes, normal operation resumes |

```javascript
export const options = {
  vus: 20,
  duration: '2m',
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95% under 2s (includes fallback)
    http_req_failed: ['rate<0.1'],      // Up to 10% errors acceptable during circuit open
  },
};
```

**What to look for**:
- Phase 3: Error rate increases but system remains responsive
- Phase 5: Metrics recover to baseline levels
- No cascading failures or thread pool exhaustion

**Expected bottleneck**: Circuit breaker fallback logic adds latency during degradation. Redis fallback may be slow under concurrent access.

---

## Interpreting Results

### k6 Output Summary

```
     █ setup

     ✓ status is 200 or 207
     ✓ body contains data
     ✓ response time < 500ms

     checks.........................: 100.00% ✓ 4521 ✗ 0
     data_received..................: 1.2 MB  20 kB/s
     data_sent......................: 380 kB  6.3 kB/s
     http_req_duration..............: avg=45ms   min=8ms   med=32ms   p(90)=98ms   p(95)=120ms  p(99)=250ms
     http_req_failed................: 0.00%   ✓ 0    ✗ 4521
     http_reqs......................: 4521    75.35/s
     iterations.....................: 4521    75.35/s
     vus............................: 50
```

### Key Metrics

| Metric | What It Tells You |
|--------|-------------------|
| `p(95)` | 95% of requests were faster than this value — the main SLA indicator |
| `p(99)` | Tail latency — what the slowest 1% of users experience |
| `http_reqs/s` | Throughput — how many requests per second the system handles |
| `http_req_failed` | Error rate — percentage of failed requests |
| `checks` | Custom assertions (e.g., status code, response time, headers) |

### Threshold Results

```
     ✗ http_req_duration..............: p(95)=125ms  threshold: p(95)<100ms  ❌ FAILED
     ✓ http_req_failed................: rate=0.00%   threshold: rate<0.01    ✅ PASSED
     ✓ checks.........................: rate=100%    threshold: rate>0.95    ✅ PASSED
```

Failed thresholds are shown with `✗` and highlighted in red.

## Optimization Roadmap

Based on expected test failures, here are the optimizations we'll pursue:

### Phase 1: Thread Pool Optimization
- **Current**: Fixed thread pool (`core=3, max=10`)
- **Target**: Virtual threads (Java 21+) for massive concurrency
- **Expected impact**: 10x throughput improvement at 100+ VUs

### Phase 2: Redis Connection Pool Tuning
- **Current**: Default Lettuce pool settings
- **Target**: Optimize `max-active`, `max-idle`, `min-idle` for sustained load
- **Expected impact**: Reduced Redis-related errors during long tests

### Phase 3: Parallel Vendor Fetch Optimization
- **Current**: CompletableFuture with fixed thread pool
- **Target**: Virtual threads + optimized CompletableFuture composition
- **Expected impact**: Lower p95 for cache-miss scenarios

### Phase 4: Circuit Breaker Fallback Speed
- **Current**: Redis fallback with full lookup
- **Target**: Async fallback + connection pooling
- **Expected impact**: Faster graceful degradation during circuit open

## Troubleshooting

### "k6 not found"

```bash
# Install k6
brew install k6

# Or use Docker fallback
./gradlew k6 -Pscript=baseline
# Falls back to docker run grafana/k6:latest
```

### "Connection refused" on target

```bash
# Verify app is running
curl http://localhost:8080/actuator/health

# Verify Redis is running
docker ps | grep redis
```

### Mock services not responding

```bash
# Check mock service status
curl http://localhost:8081/mock-api/chaos/status
curl http://localhost:8082/mock-api/chaos/status
curl http://localhost:8083/mock-api/chaos/status
```

### Thresholds failing unexpectedly

```bash
# Run with longer duration to stabilize metrics
./gradlew k6 -Pscript=baseline -Pargs="--duration 60s"

# Run with fewer VUs to isolate the issue
./gradlew k6 -Pscript=concurrent-load -Pargs="--vus 5 --duration 30s"
```

## Next Steps

1. Run all 5 test scenarios and document baseline results
2. Analyze bottleneck patterns (thread pool, Redis, network)
3. Implement Phase 1 optimization (virtual threads)
4. Re-run tests to measure improvement
5. Iterate through Phases 2-4
