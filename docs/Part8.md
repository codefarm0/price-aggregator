# Part 8 — Integration Tests

## Overview

Implement integration tests that test the full request flow including Redis and mock vendor APIs.

## Planned Tests

- [ ] **Full Flow Test**: Request → Controller → Service → Client → Mock API → Response
- [ ] **Cache Integration**: Verify Redis caching works correctly
- [ ] **Circuit Breaker Integration**: Test circuit open/half-open/closed transitions
- [ ] **Chaos Scenarios**: Test with chaos endpoints enabled
- [ ] **TraceId Propagation**: Verify traceId flows end-to-end
- [ ] **Error Scenarios**: Test fallback behavior

## Tools

- Spring Boot Test
- TestContainers for Redis
- MockWebServer or WireMock for vendor APIs

## Status: 🚧 IN PROGRESS
