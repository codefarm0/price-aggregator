# Part 7: Comprehensive Testing Strategy & JaCoCo Quality Gates

## Overview

This document describes the testing strategy for the `price-aggregator` microservice, including unit tests, integration tests, and JaCoCo code coverage quality gates enforced in the CI/CD pipeline.

## Test Architecture

### Testing Pyramid

```
                    ┌─────────────┐
                    │  E2E Tests  │  (Manual / Postman)
                    ├─────────────┤
              ┌─────┤Integration  ├─────┐
              │     │  Tests (17) │     │
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
              │     │     DTO     │     │
              │     │   Tests     │     │
              │     └─────────────┘     │
              │     ┌─────────────┐     │
              │     │    Config   │     │
              │     │   Tests     │     │
              └─────┴─────────────┴─────┘
                    │  Unit Tests  │
                    │    (23)      │
                    └──────────────┘
```

### Test Categories

| Category | Count | Description |
|----------|-------|-------------|
| Unit Tests | 23 | Fast, isolated tests with mocked dependencies |
| Integration Tests | 17 | Full Spring context + WireMock HTTP stubs |
| Context Load Test | 1 | Verifies Spring Boot application context starts |
| **Total** | **41** | |

## Unit Tests

### Structure

Unit tests are located in `src/test/java/in/codefarm/price/aggregator/unit/` and follow the same package structure as the main code.

| Test Class | Lines | Tests | Coverage Target |
|------------|-------|-------|-----------------|
| `PriceControllerTest` | 101 | 5 | 80% |
| `PriceServiceTest` | 96 | 3 | 80% |
| `PriceCacheServiceTest` | 126 | 9 | 70% |
| `PriceResultTest` | 76 | 6 | N/A (DTO excluded) |
| `MdcTaskDecoratorTest` | 131 | 4 | N/A (config excluded) |
| `PriceAggregatorInterfaceTest` | 34 | 1 | N/A (interface) |

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
| `PriceControllerIntegrationTest` | 7 | E2E HTTP flows, caching, trace ID propagation |
| `CircuitBreakerIntegrationTest` | 5 | Circuit breaker state transitions, recovery |
| `TraceIdFilterIntegrationTest` | 5 | Trace ID generation, propagation, concurrent requests |

### Technology Stack

- **Spring Boot 4.0.6** with `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- **WireMock 3.13.1** for HTTP stubbing of vendor APIs
- **WebClient** (reactive) for making HTTP requests in tests
- **@DynamicPropertySource** for runtime property injection (WireMock port)
- **JUnit 5** with `@RegisterExtension` for WireMock lifecycle

### WireMock Integration Pattern

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.data.redis.repositories.enabled=false"
})
class PriceControllerIntegrationTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + wiremock.getPort();
        registry.add("vendors.amazon.base-url", () -> baseUrl);
        registry.add("vendors.flipkart.base-url", () -> baseUrl);
        registry.add("vendors.walmart.base-url", () -> baseUrl);
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll(); // Clean state per test
        webClient = WebClient.create("http://localhost:" + port);
    }
}
```

### Test Scenarios Covered

1. **Happy Path**: All vendors respond with prices -> HTTP 200
2. **Partial Failure**: Some vendors fail -> HTTP 200 with mixed results
3. **Total Failure**: All vendors fail -> HTTP 207 (Multi-Status)
4. **Cache Hit**: Second request returns cached data (when Redis available)
5. **Cache Refresh**: `X-Refresh-Cache: true` forces API calls
6. **Trace ID Propagation**: Client-provided trace ID returned in response header
7. **Auto-generated Trace ID**: UUID generated when client doesn't provide one
8. **Trace ID to Vendors**: Trace ID propagated in outbound HTTP requests to vendors
9. **Circuit Breaker Opens**: Multiple failures trip the circuit breaker
10. **Circuit Breaker Recovery**: After wait duration, circuit transitions to half-open
11. **Timeout Handling**: Slow vendor responses handled gracefully
12. **Concurrent Requests**: Multiple threads get unique trace IDs

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
                minimum = 0.60  // 60% overall instruction coverage
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
Overall Instruction Coverage: 5550/7455 = 74.4%
```

## Execution Commands

```bash
# Run all tests
./gradlew test

# Run only unit tests
./gradlew test --tests "in.codefarm.price.aggregator.unit.*"

# Run only integration tests
./gradlew test --tests "in.codefarm.price.aggregator.integration.*"

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

### Redis Connection Errors in Tests

**Expected Behavior**: Tests log `Unable to connect to Redis` warnings but pass because:
- `PriceCacheService` wraps all Redis operations in try/catch
- Cache misses gracefully fall through to API calls
- Tests use WireMock to provide API responses

## Next Steps

1. Add Testcontainers Redis for full cache integration testing
2. Increase coverage to 80%+ overall
3. Add contract tests for vendor API responses
4. Add performance/benchmark tests for concurrent price fetching
5. Add mutation testing with PIT for test quality verification
