# Part 11 — Micrometer Integration for Observability

## Overview

Integrate Micrometer for metrics collection and export to Prometheus/Grafana for observability.

## Planned Changes

- [ ] Add Micrometer dependencies
- [ ] Configure Prometheus metrics endpoint
- [ ] Add custom metrics:
  - `price.fetch.duration` - Time to fetch prices
  - `price.fetch.errors` - Error count by vendor
  - `circuit.breaker.state` - Circuit breaker state
  - `cache.hit.ratio` - Cache hit/miss ratio
- [ ] Create Grafana dashboard
- [ ] Add distributed tracing with Micrometer Tracing (optional)

## Metrics Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/prometheus` | Prometheus metrics |
| `/actuator/metrics` | All metrics |
| `/actuator/metrics/{metric-name}` | Specific metric |

## Dashboard Panels (Planned)

1. Request Rate (requests/sec)
2. Response Time (p50, p95, p99)
3. Error Rate (%)
4. Circuit Breaker States
5. Cache Hit Ratio
6. Thread Pool Usage

## Status: 🚧 IN PROGRESS
