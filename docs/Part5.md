# Part 5 — End-to-End TraceId Propagation & Structured Logging

## Overview

This phase implements end-to-end traceId propagation to 3rd party APIs and configures structured logging with timestamp and traceId in a consistent format.

## What Was Done

- [x] **traceId propagation to 3rd party APIs** via WebClient `ExchangeFilterFunction`
- [x] **Structured logging** with format: `timestamp [thread][app-name][traceId] level logger - message`
- [x] **Timestamp added** to logs (`yyyy-MM-dd HH:mm:ss.SSS`)
- [x] **traceId duplication removed** from log messages (now only in log pattern via MDC)
- [x] **MockVendorController updated** to read `X-Trace-Id` header and log it
- [x] **logback-spring.xml created** for consistent logging across all components

## End-to-End TraceId Flow

```
Client Request
    ↓
TraceIdFilter (generates/reads X-Trace-Id, sets MDC, sets response header)
    ↓
PriceController (reads traceId from MDC)
    ↓
PriceService (MDC propagated via MdcTaskDecorator)
    ↓
AmazonClient/FlipkartClient/WalmartClient
    ↓
WebClient ExchangeFilterFunction (adds X-Trace-Id header to outgoing request)
    ↓
MockVendorController (reads X-Trace-Id header, sets MDC, logs it)
```

## Structured Logging Configuration

### logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread][%property{app-name}][traceId=%X{traceId:-N/A}] %-5level %logger{36} - %msg%n"/>

    <springProperty scope="context" name="app-name" source="spring.application.name" defaultValue="price-aggregator"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### Log Format

```
timestamp [thread-name][app-name][traceId=value] LEVEL logger - message
```

Example output:
```
2026-04-28 10:00:00.123 [http-nio-8080-exec-1][price-aggregator][traceId=abc-123] INFO  c.c.p.a.controller.PriceController - GET /api/prices/iphone-15 refreshCache=false
2026-04-28 10:00:00.456 [price-fetch-1][price-aggregator][traceId=abc-123] INFO  c.c.p.a.external.AmazonClient - [AMAZON] productId=iphone-15 source=API price=999.99
2026-04-28 10:00:00.789 [price-fetch-2][price-aggregator][traceId=abc-123] WARN  c.c.p.a.external.FlipkartClient - [FLIPKART] circuit breaker triggered
2026-04-28 10:00:01.012 [mock-api-1][price-aggregator][traceId=abc-123] INFO  c.c.p.a.mock.MockVendorController - [MOCK-AMAZON] productId=iphone-15
```

## Key Implementation Details

### 1. WebClient traceId Propagation

**File:** `config/WebClientConfig.java`

```java
private ExchangeFilterFunction traceIdPropagationFilter() {
    return ExchangeFilterFunction.ofRequestProcessor(request -> {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isEmpty()) {
            ClientRequest newRequest = ClientRequest.from(request)
                    .header(TRACE_ID_HEADER, traceId)
                    .build();
            return Mono.just(newRequest);
        }
        return Mono.just(request);
    });
}
```

### 2. Mock Vendor Controller Reading traceId

**File:** `mock/MockVendorController.java`

```java
@GetMapping("/amazon/{productId}")
public PriceResponse getAmazonPrice(@PathVariable String productId, HttpServletRequest request) {
    getTraceId(request);
    log.info("[MOCK-AMAZON] productId={}", productId);
    // ...
}

private String getTraceId(HttpServletRequest request) {
    String traceId = request.getHeader("X-Trace-Id");
    if (traceId != null && !traceId.isEmpty()) {
        MDC.put("traceId", traceId);
    }
    return traceId != null ? traceId : "N/A";
}
```

### 3. Clean Log Messages (No traceId Duplication)

Before (BAD):
```java
log.info("[{}] traceId={} productId={} source=API", VENDOR, traceId, productId);
```

After (GOOD):
```java
log.info("[{}] productId={} source=API", VENDOR, productId);
// traceId is automatically added by logback pattern from MDC
```

## Files Modified

- `src/main/resources/logback-spring.xml` - **CREATED** - Structured logging configuration
- `src/main/java/in/codefarm/price/aggregator/config/WebClientConfig.java` - Added `ExchangeFilterFunction` for traceId propagation
- `src/main/java/in/codefarm/price/aggregator/mock/MockVendorController.java` - Read and log traceId from header
- `src/main/java/in/codefarm/price/aggregator/external/AmazonClient.java` - Removed traceId from messages
- `src/main/java/in/codefarm/price/aggregator/external/FlipkartClient.java` - Removed traceId from messages
- `src/main/java/in/codefarm/price/aggregator/external/WalmartClient.java` - Removed traceId from messages
- `src/main/java/in/codefarm/price/aggregator/controller/PriceController.java` - Removed traceId from messages
- `src/main/java/in/codefarm/price/aggregator/service/PriceService.java` - Removed traceId from messages

## Testing

1. Start the application
2. Make a request with or without `X-Trace-Id` header:
   ```bash
   curl -v http://localhost:8080/api/prices/iphone-15
   curl -H "X-Trace-Id: my-custom-id-123" http://localhost:8080/api/prices/iphone-15
   ```
3. Verify in logs:
   - Timestamp is present at the start of each log line
   - `traceId=` appears only once (in the pattern, not in the message)
   - Same traceId appears in the response header `X-Trace-Id`
   - Mock vendor logs show the same traceId
