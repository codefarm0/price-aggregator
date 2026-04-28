# Price Aggregator

A Spring Boot microservice that aggregates product prices from multiple external retailers (Amazon, Walmart, Flipkart) with Resilience4j patterns, MDC tracing, and structured logging.

## Project Plan

| Part | Description | Status |
|------|-------------|--------|
| [Part 1-3](docs/Part1-3.md) | Basic Price Fetching, Spring Boot Aggregator, Redis Caching | ✅ |
| [Part 4](docs/Part4.md) | Resilience4j Circuit Breaker + Rate Limiting | ✅ |
| [Part 5](docs/Part5.md) | End-to-End TraceId Propagation & Structured Logging | ✅ |
| [Part 6](docs/Part6.md) | Completing Resiliency (Bulkhead, TimeLimiter, Retry) | 🚧 |
| [Part 7](docs/Part7.md) | Unit Tests | 🚧 |
| [Part 8](docs/Part8.md) | Integration Tests | 🚧 |
| [Part 9](docs/Part9.md) | Load Testing - Performance Testing | 🚧 |
| [Part 10](docs/Part10.md) | Event-Driven Updates (Kafka) | 🚧 |
| [Part 11](docs/Part11.md) | Micrometer Integration for Observability | 🚧 |
| [Part 12](docs/Part12.md) | ELK Stack for Application Monitoring | 🚧 |

## Quick Start

### Development
```bash
./gradlew build
./gradlew bootRun
```

### Production (Docker)
```bash
docker-compose up --build
```

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/prices/{productId}` | Fetch prices (returns List<PriceResult>) |
| `GET /api/prices/{productId}` + `X-Refresh-Cache: true` | Force fresh API call |
| `GET /mock-api/amazon/{productId}` | Mock Amazon API |
| `GET /mock-api/flipkart/{productId}` | Mock Flipkart API |
| `GET /mock-api/walmart/{productId}` | Mock Walmart API |
| `GET /mock-api/chaos/scenario/fast-failures` | Simulate 100% failures |
| `GET /mock-api/chaos/scenario/slow-responses` | Simulate 500ms delays |
| `GET /mock-api/chaos/scenario/unstable` | Simulate 70% failures + 3s delay |
| `GET /mock-api/chaos/reset` | Reset chaos mode |

### Response Headers
| Header | Description |
|--------|-------------|
| `X-Trace-Id` | Trace ID for end-to-end request tracing |

## Tech Stack

| Component | Technology |
|-----------|-------------|
| Framework | Spring Boot 4.0.6 |
| Web | Spring MVC + WebFlux (WebClient) |
| Cache | Redis (distributed) |
| Resilience | Resilience4j (Circuit Breaker, Bulkhead, Retry, TimeLimiter) |
| Tracing | MDC with traceId propagation |
| Logging | Logback with structured logging |
| Language | Java 25 |
| Build | Gradle + Jib |

## Configuration

| Profile | Description |
|---------|-------------|
| `default` | Local development (with Redis required) |
| `prod` | Production with Redis |

## Project Structure

```
src/main/java/in/codefarm/price/aggregator/
├── PriceAggregatorApplication.java
├── config/
│   ├── PriceConfig.java           # Thread pool + MdcTaskDecorator
│   ├── TraceIdFilter.java        # MDC traceId from header
│   ├── MdcTaskDecorator.java     # MDC propagation for async
│   ├── WebClientConfig.java      # WebClient with traceId propagation
│   ├── RedisConfig.java
│   └── logback-spring.xml       # Structured logging config
├── controller/
│   ├── PriceController.java      # Returns List<PriceResult>
│   └── mock/
│       └── MockVendorController.java  # Mock APIs with traceId support
├── dto/
│   ├── PriceResponse.java       # 3rd party API response
│   ├── PriceResult.java         # API response DTO (with source, traceId)
│   └── PriceSource.java        # Enum: CACHE, API, FALLBACK
├── external/
│   ├── AmazonClient.java       # With CircuitBreaker + MDC logging
│   ├── FlipkartClient.java
│   ├── WalmartClient.java
│   └── PriceAggregator.java    # Interface returning PriceResult
└── service/
    ├── PriceService.java        # Returns List<PriceResult>
    └── PriceCacheService.java  # Stores PriceResult as JSON in Redis
```

## Documentation

All documentation is in the [docs/](docs/) folder:
- [Part 1: Basic Price Fetching](docs/Part1.md)
- [Part 2: Spring Boot Aggregator](docs/Part2.md)
- [Part 3: Redis Caching](docs/Part3.md)
- [Part 4: Circuit Breaker](docs/Part4.md)
- [Part 5: TraceId & Logging](docs/Part5.md)

## Docker Files

- `Dockerfile` - Application container
- `docker-compose.yml` - Redis + App orchestration

## Building

```bash
./gradlew build
./gradlew bootJar
```

## Testing

```bash
./gradlew test
./cb-test.sh help    # Circuit breaker testing script
```
