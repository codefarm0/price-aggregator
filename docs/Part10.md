# Part 10 — Event-Driven Updates (Kafka)

## Overview

Add Kafka integration for event-driven price updates. When prices change, publish events to Kafka for other services to consume.

## Planned Changes

- [ ] Add Kafka dependencies to `build.gradle`
- [ ] Configure Kafka producer in price-aggregator
- [ ] Publish `PriceUpdatedEvent` when prices are fetched from API
- [ ] Create Kafka consumer service (separate microservice)
- [ ] Implement event schema (Avro or JSON)
- [ ] Add Kafka Connect for Redis sink (optional)

## Event Schema (Planned)

```json
{
  "eventId": "uuid",
  "eventType": "PRICE_UPDATED",
  "vendor": "amazon",
  "productId": "iphone-15",
  "price": 999.99,
  "timestamp": 1777198215258,
  "traceId": "abc-123"
}
```

## Topics (Planned)

| Topic | Description |
|-------|-------------|
| `price-updates` | Price change events |
| `price-alerts` | Price drop alerts |

## Status: 🚧 IN PROGRESS
