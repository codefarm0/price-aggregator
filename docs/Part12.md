# Part 12 — ELK Stack for Application Monitoring

## Overview

Deploy ELK (Elasticsearch, Logstash, Kibana) stack for centralized logging and monitoring.

## Planned Changes

- [ ] Add Logstash dependency or Filebeat for log shipping
- [ ] Configure log format for ELK (JSON format)
- [ ] Create `logback-spring.xml` with JSON encoder (logstash-logback-encoder)
- [ ] Deploy ELK stack via docker-compose
- [ ] Create Kibana dashboards for:
  - Request logs with traceId
  - Error logs
  - Circuit breaker events
  - Performance metrics

## Log Format (Planned)

```json
{
  "timestamp": "2026-04-28T10:00:00.123Z",
  "thread": "http-nio-8080-exec-1",
  "app-name": "price-aggregator",
  "traceId": "abc-123",
  "level": "INFO",
  "logger": "c.c.p.a.controller.PriceController",
  "message": "GET /api/prices/iphone-15 refreshCache=false"
}
```

## Docker Compose Services (Planned)

| Service | Description |
|---------|-------------|
| elasticsearch | Search and analytics engine |
| logstash | Log aggregator and processor |
| kibana | Visualization and dashboards |
| filebeat | Log shipper (alternative to Logstash) |

## Kibana Dashboards (Planned)

1. **Request Tracking**: Filter logs by traceId
2. **Error Analysis**: Group errors by type and vendor
3. **Performance**: Response time trends
4. **Circuit Breaker**: State transition timeline

## Status: 🚧 IN PROGRESS
