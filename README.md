# Price Aggregator

A Spring Boot microservice that aggregates product prices from multiple external retailers (Amazon, Walmart, Flipkart) with Resilience4j patterns, MDC tracing, and structured logging.

## Project Plan

| Part | Description | Status |
|------|-------------|--------|
| [Part1](Part1.md) | Basic Price Fetching (Phases 1-3) | ✅ |
| [Part2](Part2.md) | Spring Boot Aggregator (REST API + WebClient) | ✅ |
| [Part3](Part3.md) | Redis Caching Layer | ✅ |
| [Part4](Part4.md) | Circuit Breaker (Resilience4j) | ✅ |
| [Part5](Part4.md) | TraceId Propagation & Structured Logging | ✅ |
| **[Part6](Part4.md)** | **Mock Services Refactoring (Distributed Architecture)** | ✅ |
| [Part7](Part4.md) | Unit Tests | 🚧 |
| [Part8](Part4.md) | Integration Tests | 🚧 |
| [Part9](Part4.md) | Load Testing - Performance Testing | 🚧 |
| [Part10](Part4.md) | Event-Driven Updates (Kafka) | 🚧 |
| [Part11](Part4.md) | Micrometer Integration for Observability | 🚧 |
| [Part12](Part4.md) | ELK Stack for Application Monitoring | 🚧 |

## Quick Start

### Development
```bash
# Start mock services (in separate terminals)
cd amazon-mock && ./gradlew bootRun  # Port 8081
cd flipkart-mock && ./gradlew bootRun  # Port 8082
cd walmart-mock && ./gradlew bootRun  # Port 8083

# Start price-aggregator
./gradlew bootRun  # Port 8080
```

### Production (Docker)
```bash
docker-compose up --build
```

## API Endpoints

### Price Aggregator (Port 8080)
| Endpoint | Description |
|----------|-------------|
| `GET /api/prices/{productId}` | Fetch prices from all vendors |
| `GET /api/prices/{productId}` + `X-Refresh-Cache: true` | Force fresh API call |

### Mock Services (Standalone)
| Service | Port | Endpoint |
|---------|------|----------|
| Amazon Mock | 8081 | `GET http://localhost:8081/mock-api/amazon/{productId}` |
| Flipkart Mock | 8082 | `GET http://localhost:8082/mock-api/flipkart/{productId}` |
| Walmart Mock | 8083 | `GET http://localhost:8083/mock-api/walmart/{productId}` |

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

## Project Structure

```
price-aggregator/                    # Main aggregator service
├── src/main/java/in/codefarm/price/aggregator/
│   ├── controller/PriceController.java
│   ├── config/ (TraceIdFilter, MdcTaskDecorator, PriceConfig)
│   ├── external/ (AmazonClient, FlipkartClient, WalmartClient)
│   ├── service/ (PriceService, PriceCacheService)
│   └── dto/ (PriceResult, PriceSource)
├── medium-stories/ (Medium articles)
├── Part1.md, Part2.md, Part3.md, Part4.md
└── README.md

amazon-mock/                         # Standalone Amazon mock service
├── src/main/java/in/codefarm/amazon/mock/
│   ├── AmazonMockApplication.java
│   ├── controller/AmazonMockController.java
│   ├── config/TraceIdFilter.java
│   └── dto/PriceResponse.java
└── GitHub: github.com/code-farm0/amazon-mock

flipkart-mock/                      # Standalone Flipkart mock service
├── src/main/java/in/codefarm/flipkart/mock/
│   └── (similar structure)
└── GitHub: github.com/code-farm0/flipkart-mock

walmart-mock/                       # Standalone Walmart mock service
├── src/main/java/in/codefarm/walmart/mock/
│   └── (similar structure)
└── GitHub: github.com/code-farm0/walmart-mock
```

## Mock Services (Distributed Architecture)

The mock vendor APIs have been **extracted into separate Spring Boot services** to simulate a true distributed architecture:

| Service | GitHub URL |
|---------|------------|
| amazon-mock | [github.com/code-farm0/amazon-mock](https://github.com/code-farm0/amazon-mock) |
| flipkart-mock | [github.com/code-farm0/flipkart-mock](https://github.com/code-farm0/flipkart-mock) |
| walmart-mock | [github.com/code-farm0/walmart-mock](https://github.com/code-farm0/walmart-mock) |

**Main Aggregator:**
| Service | GitHub URL |
|---------|------------|
| price-aggregator | [github.com/code-farm0/price-aggregator](https://github.com/code-farm0/price-aggregator) |

### Minimal Features in Each Mock Service:
- REST endpoint: `GET /mock-api/{vendor}/{productId}`
- Random price generation with timestamp
- TraceId propagation (reads `X-Trace-Id` header, sets MDC)
- Structured logging (same logback pattern)
- Health endpoint (`/actuator/health`)
- Chaos mode (in-memory state for testing)
- Chaos endpoints (`/chaos/enable`, `/disable`, `/reset`, `/status`)
- Predefined scenarios (`/chaos/scenario/fast-failures`, etc.)

## Configuration

| Profile | Description |
|---------|-------------|
| `default` | Local development (with Redis) |
| `prod` | Production with Redis |

## Documentation

All documentation is in the project root:
- `Part1.md` - Basic Price Fetching
- `Part2.md` - Spring Boot Aggregator
- `Part3.md` - Redis Caching
- `Part4.md` - Circuit Breaker + TraceId + Logging
- `Part6.md` - Mock Services Refactoring (Distributed Architecture)

## Medium Stories

Technical articles in `medium-stories/`:
- `01-thread-pool-mdc-propagation.md` - Building MDC-Aware Thread Pool
- `02-resilience-circuit-breaker.md` - Resilience4j Patterns

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
