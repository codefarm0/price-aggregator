# Price Aggregator

A Spring Boot microservice that aggregates product prices from multiple external retailers (Amazon, Walmart, Flipkart).

## Parts

| Part | Description | Status |
|------|-------------|--------|
| [Part1.md](Part1.md) | Basic Price Fetching (Phases 1-3) | ✅ |
| [Part2.md](Part2.md) | Spring Boot Aggregator (REST API + WebClient) | ✅ |
| [Part3.md](Part3.md) | Redis Caching Layer | ✅ |
| Part4 | Rate Limiting + Bulkheads (Resilience4j) | 🚧 |
| Part5 | Microservice Architecture | 🚧 |
| Part6 | Event-Driven Updates (Kafka) | 🚧 |
| Part7 | Highly Scalable Architecture | 🚧 |

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
| `GET /api/prices/{productId}` | Fetch prices from all vendors |
| `GET /mock-api/amazon/{productId}` | Mock Amazon API |
| `GET /mock-api/flipkart/{productId}` | Mock Flipkart API |
| `GET /mock-api/walmart/{productId}` | Mock Walmart API |

## Tech Stack

| Component | Technology |
|-----------|-------------|
| Framework | Spring Boot 4.0.5 |
| Web | Spring MVC + WebFlux (WebClient) |
| Cache | Caffeine (local) + Redis (distributed) |
| Language | Java 25 |
| Build | Gradle + Jib |

## Configuration

| Profile | Description |
|---------|-------------|
| `default` | Local development (no Redis) |
| `prod` | Production with Redis |

See [Part2.md](Part2.md) and [Part3.md](Part3.md) for details.

## Project Structure

```
src/main/java/in/codefarm/price/aggregator/
├── PriceAggregatorApplication.java
├── config/
│   ├── PriceConfig.java
│   ├── RedisConfig.java
│   └── WebClientConfig.java
├── controller/
│   ├── PriceController.java
│   └── MockVendorController.java
├── dto/
│   └── PriceResponse.java
├── external/
│   ├── AmazonClient.java
│   ├── FlipkartClient.java
│   ├── WalmartClient.java
│   └── PriceAggregator.java
└── service/
    ├── PriceService.java
    └── PriceCacheService.java
```

## Docker Files

- `Dockerfile` - Application container
- `docker-compose.yml` - Redis + App orchestration