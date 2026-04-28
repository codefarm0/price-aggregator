# Phase 4 — Spring Boot Aggregator

A proper Spring Boot microservice with REST API, WebClient, and connection pooling.

## Architecture

```mermaid
graph TD
    subgraph Client
        C[Client App]
    end

    subgraph "price-aggregator Service"
        PC["PriceController<br/GET /api/prices/{productId}"]
        PS[PriceService]
        
        subgraph "Connection Pool"
            WC[WebClient]
            CP[ConnectionProvider<brmaxConnections: 50<brmaxIdleTime: 30s]
        end
        
        AC[AmazonClient]
        FC[FlipkartClient]
        WC2[WalmartClient]
        
        subgraph "Cache Layer"
            CC[Caffeine Cache<brmaxSize: 1000<brttl: 10min]
        end
    end

    subgraph "Internal Mock APIs"
        MC["MockVendorController<br/mock-api/{vendor}/{productId}"]
    end

    C -->|GET /api/prices/iphone-15| PC
    PC --> PS
    PS -->|async| AC
    PS -->|async| FC
    PS -->|async| WC2
    
    AC -->|WebClient| WC
    FC -->|WebClient| WC
    WC2 -->|WebClient| WC
    
    WC -->|HTTP| CP
    CP -->|HTTP| MC
    
    AC -.->|cache hit| CC
    FC -.->|cache hit| CC
    WC2 -.->|cache hit| CC
```

## Sequence Diagram

```mermaid
sequenceDiagram
    participant Client
    participant PC as PriceController
    participant PS as PriceService
    participant AC as AmazonClient
    participant FC as FlipkartClient
    participant WC as WalmartClient
    participant Cache as Caffeine Cache
    
    Client->>PC: GET /api/prices/iphone-15
    PC->>PS: fetchPrices("iphone-15")
    
    rect rgb(100, 120, 55)
        Note over PS,AC: Parallel Execution
        PS->>AC: getPrice("iphone-15")
        PS->>FC: getPrice("iphone-15")
        PS->>WC: getPrice("iphone-15")
    end
    
    rect rgb(100, 55, 220)
        Note over AC,Cache: Check Cache First
        AC->>Cache: getIfPresent("iphone-15")
        alt Cache Miss
            AC->>AC: fetchPriceFromApi()
            AC->>AC: WebClient → Mock API
            AC->>Cache: put("iphone-15", 799.99)
        end
    end
    
    Note over FC,Cache: Same pattern
    Note over WC,Cache: Same pattern
    
    AC-->>PS: 799.99
    FC-->>PS: 749.99
    WC-->>PS: 779.99
    
    PS-->>PC: {amazon: 799.99, flipkart: 749.99, walmart: 779.99}
    PC-->>Client: JSON Response
```

## Component Details

### REST Controller
- **Endpoint**: `GET /api/prices/{productId}`
- **Response**: `Map<String, Double>` - vendor name to price

### Service Layer
- Fetches prices from all vendors **in parallel**
- Uses `CompletableFuture` with thread pool
- Has **timeout** (default: 2s)
- **Fallback** to cached price on failure

### WebClient (Connection Pooling)
- **maxConnections**: 50
- **maxIdleTime**: 30s
- **maxLifeTime**: 5min
- **pendingAcquireTimeout**: 10s

### Cache (Caffeine)
- **maxSize**: 1000 entries
- **expireAfterWrite**: 10 minutes
- **recordStats**: enabled (for monitoring)

### Thread Pool
- **core-size**: 3
- **max-size**: 10
- **queue-capacity**: 100
- **thread-name-prefix**: `price-fetch-`

## Configuration

```yaml
spring:
  application:
    name: price-aggregator

server:
  port: 8080

vendors:
  amazon:
    base-url: http://localhost:8080
  flipkart:
    base-url: http://localhost:8080
  walmart:
    base-url: http://localhost:8080
  connection-timeout-ms: 5000
  read-timeout-ms: 5000
  cache:
    max-size: 1000
    expire-after-write-minutes: 10

price:
  fetch:
    timeout-ms: 2000
  pool:
    core-size: 3
    max-size: 10
    queue-capacity: 100
    thread-name-prefix: price-fetch-
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/prices/{productId}` | Fetch prices from all vendors |
| GET | `/mock-api/amazon/{productId}` | Mock Amazon API |
| GET | `/mock-api/flipkart/{productId}` | Mock Flipkart API |
| GET | `/mock-api/walmart/{productId}` | Mock Walmart API |

## Testing with cURL

### 1. Fetch all prices for a product

```bash
curl -s http://localhost:8080/api/prices/iphone-15 | jq
```

**Response:**
```json
{
  "amazon": 799.99,
  "flipkart": 749.99,
  "walmart": 779.99
}
```

### 2. Test mock vendor APIs

```bash
# Amazon
curl -s http://localhost:8080/mock-api/amazon/iphone-15 | jq

# Flipkart
curl -s http://localhost:8080/mock-api/flipkart/iphone-15 | jq

# Walmart
curl -s http://localhost:8080/mock-api/walmart/iphone-15 | jq
```

**Response:**
```json
{
  "productId": "iphone-15",
  "price": 799.99,
  "vendor": "amazon",
  "timestamp": 1713244800000
}
```

### 3. Test with different products

```bash
curl -s http://localhost:8080/api/prices/macbook-pro | jq
curl -s http://localhost:8080/api/prices/airpods-pro | jq
curl -s http://localhost:8080/api/prices/ipad-mini | jq
```

### 4. Test timeout behavior

```bash
time curl -s http://localhost:8080/api/prices/timeout-test
```

### 5. Test cache behavior (second call should be faster)

```bash
# First call (cache miss)
time curl -s http://localhost:8080/api/prices/iphone-15 > /dev/null

# Second call (cache hit)
time curl -s http://localhost:8080/api/prices/iphone-15 > /dev/null
```

## Running

```bash
./gradlew bootRun
```

## Verification

```bash
# Check service is running
curl -s http://localhost:8080/actuator/health

# Fetch prices
curl -s http://localhost:8080/api/prices/test-product
```