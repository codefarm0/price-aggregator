# Part 1 — Basic Price Fetching (Phases 1-3)

Implementation of price fetching using different phases of optimization.

## Phase 1 — Sequential Fetching

Fetch prices from each vendor one by one:

```java
double amazonPrice = amazon.getPrice(productId);     
double flipkartPrice = flipkart.getPrice(productId);   
double walmartPrice = walmart.getPrice(productId);     
// Total: ~300-3000ms
```

```mermaid
sequenceDiagram
    participant Client
    participant Amazon
    participant Flipkart
    participant Walmart
    
    Client->>Amazon: getPrice(iphone-15)
    Amazon-->>Client: 799.99
    Client->>Flipkart: getPrice(iphone-15)
    Flipkart-->>Client: 749.99
    Client->>Walmart: getPrice(iphone-15)
    Walmart-->>Client: 779.99
```

## Phase 2 — Parallel Fetching

Fetch from all vendors simultaneously using threads:

```java
ExecutorService executor = Executors.newFixedThreadPool(3);

CompletableFuture<Double> amazonFuture = CompletableFuture
        .supplyAsync(() -> amazon.getPrice(productId), executor);
CompletableFuture<Double> flipkartFuture = CompletableFuture
        .supplyAsync(() -> flipkart.getPrice(productId), executor);
CompletableFuture<Double> walmartFuture = CompletableFuture
        .supplyAsync(() -> walmart.getPrice(productId), executor);

CompletableFuture.allOf(amazonFuture, flipkartFuture, walmartFuture).join();
// Total: ~100-1000ms (max of all)
```

```mermaid
sequenceDiagram
    participant Client
    participant Executor
    participant Amazon
    participant Flipkart
    participant Walmart
    
    Client->>Executor: submit all 3 requests
    Executor->>Amazon: getPrice(iphone-15)
    Executor->>Flipkart: getPrice(iphone-15)
    Executor->>Walmart: getPrice(iphone-15)
    Amazon-->>Client: 799.99
    Flipkart-->>Client: 749.99
    Walmart-->>Client: 779.99
```

## Phase 3 — Handling Vendor Failures

Add timeout and fallback on failure:

```java
CompletableFuture<Double> amazonPrice = CompletableFuture
        .supplyAsync(() -> amazon.getPrice(productId), executor)
        .orTimeout(1000, TimeUnit.MILLISECONDS)
        .exceptionally(ex -> amazon.getFallbackPrice(productId));
```

```mermaid
sequenceDiagram
    participant Client
    participant Executor
    participant Amazon
    participant Redis
    
    Client->>Executor: getPrice(iphone-15)
    Executor->>Amazon: getPrice()
    
    rect rgb(255, 200, 200)
        Note over Amazon: Timeout (1s)
        Amazon-->>Executor: TimeoutException
    end
    
    Executor->>Redis: getFallbackPrice()
    Redis-->>Executor: 799.99
    Executor-->>Client: 799.99
```

## Comparison

| Phase | Description | Time | Failure Handling |
|-------|------------|------|--------------|
| 1 | Sequential | ~300-3000ms | None |
| 2 | Parallel | ~100-1000ms | Manual |
| 3 | Parallel + Timeout | ~100-1000ms | Automatic Fallback |

## Next Steps

- [Part2](Part2.md) — Spring Boot Aggregator with REST API