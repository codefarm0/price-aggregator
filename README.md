# Price Aggregator

A Spring Boot microservice that aggregates product prices from multiple external retailers (Amazon, Walmart, Flipkart).

## Phases

- [x] **Phase 1-3** — Basic Price Fetching (sequential → parallel → with timeout/fallback)
- [x] **Phase 4** — Spring Boot Aggregator (REST API + WebClient + Connection Pooling) [PHASE4.md](PHASE4.md)
- [ ] **Phase 5** — Redis Caching Layer
- [ ] **Phase 6** — Rate Limiting + Bulkheads (Resilience4j)
- [ ] **Phase 7** — Microservice Architecture (Vendor Adapter Services)
- [ ] **Phase 8** — Event-Driven Updates (Kafka)
- [ ] **Phase 9** — Highly Scalable Architecture

## Quick Start

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Test
curl http://localhost:8080/api/prices/iphone-15
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
| Cache | Caffeine (in-memory) |
| Language | Java 25 |
| Build | Gradle |

## Configuration

See [PHASE4.md](PHASE4.md) for detailed configuration and [PHASE4.md#testing-with-curl](curl commands).

## Project Structure

```
src/main/java/in/codefarm/price/aggregator/
├── PriceAggregatorApplication.java
├── config/
│   ├── PriceConfig.java            # Thread pool + Caffeine cache
│   └── WebClientConfig.java         # Connection pooling
├── controller/
│   ├── PriceController.java        # REST endpoints
│   └── MockVendorController.java   # Mock APIs
├── dto/
│   └── PriceResponse.java
├── external/
│   ├── AmazonClient.java
│   ├── FlipkartClient.java
│   ├── WalmartClient.java
│   └── PriceAggregator.java        # Interface
└── service/
    └── PriceService.java
```