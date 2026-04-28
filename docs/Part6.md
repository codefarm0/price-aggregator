# Part 6 — Completing Resiliency (Bulkhead, TimeLimiter, Retry)

## Overview

This phase will implement the remaining Resilience4j patterns:
- **Bulkhead**: Isolate thread pools for each vendor
- **TimeLimiter**: Limit maximum wait time for async calls
- **Retry**: Automatically retry failed calls with exponential backoff

## Planned Changes

- [ ] Add Resilience4j Bulkhead to each vendor client
- [ ] Add Resilience4j TimeLimiter for timeout handling
- [ ] Add Resilience4j Retry with configurable retry attempts
- [ ] Use Spring Cloud annotations (`@Bulkhead`, `@TimeLimiter`, `@Retry`) where possible
- [ ] Update configuration in `application.yaml`
- [ ] Test each pattern independently and in combination

## Configuration (Planned)

```yaml
resilience4j:
  bulkhead:
    instances:
      amazon:
        max-concurrent-calls: 10
        max-wait-duration: 1000ms
      flipkart:
        max-concurrent-calls: 10
        max-wait-duration: 1000ms
      walmart:
        max-concurrent-calls: 10
        max-wait-duration: 1000ms

  timelimiter:
    instances:
      amazon:
        timeout-duration: 2s
      flipkart:
        timeout-duration: 2s
      walmart:
        timeout-duration: 2s

  retry:
    instances:
      amazon:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
      flipkart:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
      walmart:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
```

## Status: 🚧 IN PROGRESS
