# Part 7: Comprehensive Testing Strategy & JaCoCo Quality Gates

## Overview

This document describes the testing strategy for the `price-aggregator` microservice, including unit tests, integration tests (with Testcontainers Redis), and JaCoCo code coverage quality gates enforced in the CI/CD pipeline.

## Test Architecture

### Testing Pyramid

```
                        ┌─────────────┐
                        │  E2E Tests  │  (Manual / Postman)
                        ├─────────────┤
                  ┌─────┤Integration  ├─────┐
                  │     │  Tests (32) │     │
                  │     ├─────────────┤     │
                  │     │ Testcontainers│    │
                  │     │    Redis    │     │
                  │     └─────────────┘     │
             ┌────┴────┐              ┌─────┴────┐
             │ Service │              │ External │
             │  Tests  │              │  Tests   │
             └────┬────┘              └─────┬────┘
                  │     ┌─────────────┐     │
                  │     │  Controller │     │
                  │     │   Tests     │     │
                  │     └─────────────┘     │
                  │     ┌─────────────┐     │
                  │     │    Config   │     │
                  │     │   Tests     │     │
                  └─────┴─────────────┴─────┘
                        │  Unit Tests  │
                        │    (22)      │
                        └──────────────┘
```

### Test Categories

| Category | Count | Description |
|----------|-------|-------------|
| Unit Tests | 22 | Fast, isolated tests with mocked dependencies |
| Integration Tests | 32 | Full Spring context + WireMock HTTP stubs + Testcontainers Redis |
| Context Load Test | 1 | Verifies Spring Boot application context starts |
| **Total** | **55** | |

## Unit Tests

### Structure

Unit tests are located in `src/test/java/in/codefarm/price/aggregator/unit/` and follow the same package structure as the main code.

| Test Class | Tests | Coverage Target |
|------------|-------|-----------------|
| `PriceCacheServiceTest` | 9 | 70% |
| `PriceControllerTest` | 5 | 80% |
| `MdcTaskDecoratorTest` | 4 | N/A (config excluded) |
| `PriceServiceTest` | 3 | 80% |
| `PriceAggregatorApplicationTests` | 1 | N/A (entry point) |

### Key Patterns

**Mockito with Constructor Injection:**
```java
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {
    private final PriceAggregator amazonClient = mock(PriceAggregator.class);
    private final Executor executor = Runnable::run; // Synchronous for tests
    private PriceService priceService;

    @BeforeEach
    void setUp() {
        priceService = new PriceService(
                List.of(amazonClient, flipkartClient, walmartClient),
                executor, 5000L
        );
    }
}
```

**MDC Context Management:**
```java
@Test
void testPropagatesMdcToChildThread() throws InterruptedException {
    MDC.put("traceId", "test-trace-123");
    // ... test logic ...
    MDC.clear(); // Cleanup in @AfterEach or finally
}
```

## Integration Tests

### Structure

Integration tests are located in `src/test/java/in/codefarm/price/aggregator/integration/`.

| Test Class | Tests | Purpose |
|------------|-------|---------|
| `PriceControllerIntegrationTest` | 7 | E2E HTTP flows, trace ID propagation, X-Refresh-Cache behavior |
| `CircuitBreakerIntegrationTest` | 7 | Circuit breaker state transitions, recovery, isolation, failure rate tracking |
| `CircuitBreakerCacheFallbackIntegrationTest` | 5 | Cache-CB fallback pattern, graceful degradation, full lifecycle |
| `RedisCacheIntegrationTest` | 8 | Real Redis operations via Testcontainers (JSON, TTL, eviction, health) |
| `TraceIdFilterIntegrationTest` | 5 | Trace ID generation, propagation, concurrent requests |

### Technology Stack

- **Spring Boot 4.0.6** with `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- **WireMock 3.13.1** for HTTP stubbing of vendor APIs
- **Testcontainers (Redis 7 Alpine)** for real cache integration testing
- **WebClient** (reactive) for making HTTP requests in tests
- **@DynamicPropertySource** for runtime property injection (WireMock port, Redis host/port)
- **JUnit 5** with `@RegisterExtension` for WireMock lifecycle, `@Testcontainers` for Redis lifecycle

### Shared Test Configuration

Common test properties live in `src/test/resources/application.yaml`, eliminating the need for `@TestPropertySource` on most test classes:

```yaml
# src/test/resources/application.yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      repositories:
        enabled: false

vendors:
  amazon:
    base-url: http://localhost:8081
  flipkart:
    base-url: http://localhost:8082
  walmart:
    base-url: http://localhost:8083

price:
  fetch:
    timeout-ms: 5000

resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        waitDurationInOpenState: 5s
        failureRateThreshold: 50
```

Only circuit breaker tests need `@TestPropertySource` to override defaults for faster testing:

```java
// CircuitBreakerIntegrationTest — 12 properties (4 per vendor)
@TestPropertySource(properties = {
    "resilience4j.circuitbreaker.instances.amazon.slidingWindowSize=5",
    "resilience4j.circuitbreaker.instances.amazon.minimumNumberOfCalls=3",
    "resilience4j.circuitbreaker.instances.amazon.waitDurationInOpenState=2s",
    "resilience4j.circuitbreaker.instances.amazon.permittedNumberOfCallsInHalfOpenState=1",
    // ... flipkart and walmart overrides ...
})
```

### WireMock + Testcontainers Integration Pattern

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MyIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.6-trixie")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerWiremockProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + wiremock.getPort();
        registry.add("vendors.amazon.base-url", () -> baseUrl);
        // ... other vendors ...
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
```

### Test Scenarios Covered

#### Happy Path & Error Flows
1. **All vendors respond** -> HTTP 200 with 3 price results
2. **Partial vendor failure** -> HTTP 200 with mixed success/error results
3. **All vendors fail** -> HTTP 207 (Multi-Status) with 3 error results
4. **Cache bypass via `X-Refresh-Cache: true`** -> forces API calls even with cached data
5. **Redis unavailable** -> cache miss falls through to API calls

#### Trace ID Behavior
6. **Client-provided trace ID** -> echoed in response header
7. **Auto-generated UUID trace ID** -> valid UUID when client omits header
8. **Trace ID propagation to vendors** -> outbound requests include `X-Trace-Id`
9. **Concurrent unique trace IDs** -> parallel requests get distinct trace IDs

#### Circuit Breaker State Machine
10. **CLOSED -> OPEN threshold** -> 3 failures (minCalls=3, failureRate=50%) trips the circuit
11. **Short-circuit verification** -> WireMock request count stays constant after OPEN
12. **Circuit isolation** -> Amazon OPEN, Flipkart/Walmart remain CLOSED
13. **OPEN -> HALF_OPEN -> CLOSED recovery** -> wait 2s, probe succeeds, circuit closes
14. **HALF_OPEN -> OPEN on failed probe** -> probe fails, circuit re-opens
15. **All circuits OPEN** -> HTTP 207 with all fallback errors
16. **Mixed success/failure rate tracking** -> 2 success + 3 failures = 60% > 50% threshold

#### Redis Cache Operations (Testcontainers)
17. **JSON serialization** -> PriceResult stored as valid JSON with correct fields
18. **JSON deserialization** -> retrieved PriceResult matches all metadata
19. **TTL verification** -> cached keys have expiry <= 600 seconds
20. **Single key eviction** -> evict() removes only the specified vendor+product key
21. **Bulk eviction** -> evictAll() removes all keys matching `price:*` pattern
22. **Health check** -> isAvailable() returns true when Redis is reachable
23. **Key isolation** -> different vendors with same product ID don't collide
24. **Cache overwrite** -> new price for same vendor+product replaces old value

#### Cache + Circuit Breaker Fallback Pattern
25. **Cache HIT on second request** -> source=CACHE, no API call (verified via WireMock count)
26. **Graceful degradation** -> CB OPEN + cache available -> returns cached price (source=FALLBACK)
27. **Worst-case scenario** -> CB OPEN + empty cache -> returns error "No price available"
28. **Cache bypass with X-Refresh-Cache** -> API called even with cached data
29. **Full lifecycle** -> cache populate -> CB OPEN -> cache fallback -> recovery -> fresh cache

## Testcontainers Redis

### Why Testcontainers?

Mocked `RedisTemplate` cannot verify:
- Actual JSON serialization/deserialization of `PriceResult` objects
- Real TTL behavior and key expiration
- Pattern-based key scanning (`keys("price:*")`)
- Network-level Redis connectivity and error handling

Testcontainers spins up a real Redis 7 Alpine container for each integration test class, providing production-fidelity cache testing.

### Container Lifecycle

```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:8.6-trixie")
        .withExposedPorts(6379);
```

- The `@Container` annotation ensures the container starts once per test class (not per test method)
- `@DynamicPropertySource` injects the dynamically assigned host and port into Spring's environment
- `@BeforeEach` in `RedisCacheIntegrationTest` calls `cacheService.evictAll()` to ensure clean state
- `@AfterEach` also calls `evictAll()` to prevent test pollution

### Key Format

The cache service uses a consistent key pattern: `price:{vendor}:{productId}`

Example: `price:amazon:iphone-15`

This enables pattern-based bulk eviction with `keys("price:*")`.

## Cache + Circuit Breaker Fallback Pattern

This is a critical resilience pattern in the price aggregator. The interaction between the circuit breaker and Redis cache enables graceful degradation when vendor APIs are down.

### Flow Diagram

```
Request arrives
    |
    v
X-Refresh-Cache == true? ----yes---> Skip cache, call API through circuit breaker
    |                                      |
   no                                      v
    |                                Circuit OPEN?
    v                                      |
Cache available? ----yes---> Return cached (source=CACHE)  yes---> Fallback: check cache
    |                                      |                    |
   no                                      v                   yes---> Return cached (source=FALLBACK)
    v                                CallNotPermittedEx        no----> Return error (source=FALLBACK)
Call API through circuit breaker           |
    |                                     no
    v                                      v
Circuit OPEN? ----yes---> Fallback to cache           Call API, cache result
    |
   no
    v
Return API result (source=API), cache it
```

### Source Field Values

| Source | Meaning |
|--------|---------|
| `API` | Fresh price from vendor API call |
| `CACHE` | Price served from Redis cache (cache hit on normal request) |
| `FALLBACK` | Price served from Redis cache during circuit breaker fallback, or error when no cache available |

### Test Strategy

The `CircuitBreakerCacheFallbackIntegrationTest` class uses both Testcontainers Redis AND WireMock to verify:

1. **Cache population** via successful API calls
2. **Circuit breaker tripping** via `X-Refresh-Cache: true` (bypasses cache, forces API failures to trip CB without destroying cached data)
3. **Fallback behavior** via `X-Refresh-Cache: true` on subsequent requests (forces API path -> CB blocks -> fallback reads cache)
4. **Recovery** via wait duration + restored WireMock stubs

### Critical Test Detail

When testing CB fallback with cache available, use `X-Refresh-Cache: true`:
- Without this header, the service checks Redis first. If cached, it returns immediately without hitting the circuit breaker.
- With this header, the service bypasses cache, calls the API through the circuit breaker. If CB is OPEN, `CallNotPermittedException` is caught and the fallback logic reads from Redis cache.

## JaCoCo Quality Gates

### Configuration (`build.gradle`)

```groovy
jacoco {
    toolVersion = "0.8.13"  // Required for Java 25 compatibility
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
    afterEvaluate {
        classDirectories.setFrom(files(classDirectories.files.collect {
            fileTree(dir: it, exclude: [
                'in/codefarm/price/aggregator/PriceAggregatorApplication.class',
                'in/codefarm/price/aggregator/config/*',
                'in/codefarm/price/aggregator/dto/*',
                'in/codefarm/price/aggregator/mock/**'
            ])
        }))
    }
}

jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = 0.75  // 75% overall instruction coverage
            }
        }
        rule {
            element = 'CLASS'
            includes = [
                'in.codefarm.price.aggregator.controller.PriceController',
                'in.codefarm.price.aggregator.service.PriceService',
            ]
            limit {
                minimum = 0.80  // 80% for core business logic
            }
        }
        rule {
            element = 'CLASS'
            includes = [
                'in.codefarm.price.aggregator.service.PriceCacheService',
            ]
            limit {
                minimum = 0.70  // 70% for cache service (Redis error paths)
            }
        }
    }
}
```

### Excluded Classes

| Exclusion | Reason |
|-----------|--------|
| `PriceAggregatorApplication` | Spring Boot entry point, no business logic |
| `config/*` | Configuration classes, tested via integration tests |
| `dto/*` | Simple data transfer objects (getters/setters/factories) |
| `mock/**` | Mock vendor controllers (dev/chaos tooling) |

### Current Coverage

```
Overall Instruction Coverage: 1246/1503 = 82.9%
```

| Class | Coverage | Threshold | Status |
|-------|----------|-----------|--------|
| `PriceController` | >80% | 80% | PASS |
| `PriceService` | >80% | 80% | PASS |
| `PriceCacheService` | >70% | 70% | PASS |
| **Overall** | **82.9%** | **75%** | **PASS** |

## Execution Commands

```bash
# Run all tests
./gradlew test

# Run only unit tests
./gradlew test --tests "in.codefarm.price.aggregator.unit.*"

# Run only integration tests
./gradlew test --tests "in.codefarm.price.aggregator.integration.*"

# Run only Redis/Testcontainers tests
./gradlew test --tests "in.codefarm.price.aggregator.integration.RedisCacheIntegrationTest"

# Run only Circuit Breaker + Cache fallback tests
./gradlew test --tests "in.codefarm.price.aggregator.integration.CircuitBreakerCacheFallbackIntegrationTest"

# Run a specific test class
./gradlew test --tests "PriceControllerIntegrationTest"

# Run a specific test method
./gradlew test --tests "PriceControllerIntegrationTest.shouldReturn200WhenAllVendorsRespond"

# Generate JaCoCo HTML report
./gradlew jacocoTestReport
# Open: build/reports/jacoco/test/html/index.html

# Verify coverage thresholds
./gradlew jacocoTestCoverageVerification

# Full pipeline: clean, test, report, verify
./gradlew clean test jacocoTestReport jacocoTestCoverageVerification
```

## CI/CD Integration

The quality gates are enforced in the Gradle build pipeline. Any PR that drops coverage below the thresholds will fail the build:

```bash
# This command should pass in CI
./gradlew clean test jacocoTestCoverageVerification
```

## Java 25 Compatibility

- JaCoCo `0.8.13` is required for Java 25 bytecode analysis
- Earlier versions (0.8.12 and below) throw `IOException: Error while analyzing ...class`
- WireMock standalone 3.13.1 includes Netty 4.2.x compatible with Java 25
- Testcontainers 2.0.5 supports Java 25 runtime

## Troubleshooting

### Connection Refused on WireMock verify()

**Problem**: `HttpHostConnectException: Connect to http://localhost:8080 failed`

**Cause**: Using static `verify()` from `WireMock.*` instead of `wiremock.verify()`.

**Fix**: Always use the extension instance for verification:
```java
// WRONG - connects to default port 8080
verify(getRequestedFor(urlEqualTo("/api")));

// CORRECT - uses WireMockExtension's port
wiremock.verify(getRequestedFor(urlEqualTo("/api")));
```

### Spring Context Fails to Load

**Problem**: `PlaceholderResolutionException` for `${wiremock.port}`

**Cause**: `@TestPropertySource` properties are evaluated before WireMock starts.

**Fix**: Use `@DynamicPropertySource` instead:
```java
@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("vendors.amazon.base-url",
        () -> "http://localhost:" + wiremock.getPort());
}
```

### Redis Connection Errors in Tests (Without Testcontainers)

**Expected Behavior**: Tests that don't use Testcontainers log `Unable to connect to Redis` warnings but pass because:
- `PriceCacheService` wraps all Redis operations in try/catch
- Cache misses gracefully fall through to API calls
- Tests use WireMock to provide API responses

### Testcontainers Docker Not Available

**Problem**: `Could not find a valid Docker environment`

**Cause**: Docker daemon is not running or Testcontainers can't connect to it.

**Fix**:
```bash
# Ensure Docker Desktop (or equivalent) is running
docker ps

# Test Testcontainers manually
docker run --rm redis:8.6-trixie echo "Redis works"
```

### Async Circuit Breaker State Transitions

**Problem**: Asserting circuit breaker state immediately after a probe call shows the old state.

**Cause**: Resilience4j transitions from HALF_OPEN to CLOSED (or back to OPEN) asynchronously after the probe call completes.

**Fix**: Add a brief sleep after the probe request:
```java
fetchPrices(); // Probe call
Thread.sleep(500); // Allow async state transition
assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
```

## Next Steps

1. ~~Add Testcontainers Redis for full cache integration testing~~ DONE
2. ~~Increase coverage to 75%+ overall~~ DONE (82.9%)
3. Add contract tests for vendor API responses (PACT or Spring Cloud Contract)
4. Add performance/benchmark tests for concurrent price fetching
5. Add mutation testing with PIT for test quality verification
6. Consider raising overall coverage threshold to 85%
