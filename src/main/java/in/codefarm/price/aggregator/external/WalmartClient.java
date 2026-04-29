package in.codefarm.price.aggregator.external;

import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceResponse;
import in.codefarm.price.aggregator.dto.PriceSource;
import in.codefarm.price.aggregator.service.PriceCacheService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class WalmartClient implements PriceAggregator {

    private static final Logger log = LoggerFactory.getLogger(WalmartClient.class);
    private static final String VENDOR = "walmart";

    private final WebClient webClient;
    private final PriceCacheService cacheService;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public WalmartClient(
            WebClient webClient,
            PriceCacheService cacheService,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${vendors.walmart.base-url:http://localhost:8080}") String baseUrl) {
        this.webClient = webClient;
        this.cacheService = cacheService;
        this.baseUrl = baseUrl;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(VENDOR);

        this.circuitBreaker.getEventPublisher()
            .onStateTransition(event -> log.info("[{}] circuit state transition: {} -> {}",
                VENDOR.toUpperCase(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState()))
            .onCallNotPermitted(event -> log.warn("[{}] circuit: call NOT permitted (circuit OPEN)",
                VENDOR.toUpperCase()))
            .onSuccess(event -> log.debug("[{}] circuit: call succeeded",
                VENDOR.toUpperCase()))
            .onError(event -> log.warn("[{}] circuit: call failed - {}",
                VENDOR.toUpperCase(), event.getThrowable().getMessage()));
    }

    @Override
    public PriceResult getPrice(String productId, boolean refreshCache) {
        String traceId = MDC.get("traceId");

        // Try cache first if not forcing refresh
        if (!refreshCache) {
            Optional<PriceResult> cached = cacheService.getWithMetadata(VENDOR, productId);
            if (cached.isPresent()) {
                PriceResult result = cached.get();
                result.setTraceId(traceId); // Set current traceId
                result.setSource(PriceSource.CACHE); // Ensure source is CACHE
                log.debug("[{}] productId={} source=CACHE price={}",
                          VENDOR.toUpperCase(), productId, result.getPrice());
                return result;
            }
        }

        return fetchPriceFromApi(productId, traceId, refreshCache);
    }

    private PriceResult fetchPriceFromApi(String productId, String traceId, boolean refreshCache) {
        log.info("[{}] productId={} source=API refreshCache={}",
                  VENDOR.toUpperCase(), productId, refreshCache);

        Supplier<PriceResponse> supplier = () -> webClient.get()
                .uri(baseUrl + "/mock-api/walmart/{productId}", productId)
                .retrieve()
                .bodyToMono(PriceResponse.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        try {
            PriceResponse response = circuitBreaker.executeSupplier(supplier);
            if (response != null) {
                cacheService.set(VENDOR, productId, response.getPrice(), response.getTimestamp());
                log.info("[{}] productId={} source=API price={} timestamp={}",
                          VENDOR.toUpperCase(), productId, response.getPrice(), response.getTimestamp());

                return PriceResult.fromApi(VENDOR, response.getPrice(), response.getTimestamp(), traceId);
            }
        } catch (Exception e) {
            log.warn("[{}] productId={} API call failed, circuit state: {}",
                      VENDOR.toUpperCase(), productId, circuitBreaker.getState());
        }

        return getFallbackPrice(productId, null, traceId);
    }

    @Override
    public PriceResult getFallbackPrice(String productId, Throwable t) {
        String traceId = MDC.get("traceId");
        return getFallbackPrice(productId, t, traceId);
    }

    public PriceResult getFallbackPrice(String productId, Throwable t, String traceId) {
        if (t != null) {
            log.warn("[{}] productId={} circuit breaker triggered: {}",
                      VENDOR.toUpperCase(), productId, t.getMessage());
        }

        Optional<PriceResult> cached = cacheService.getWithMetadata(VENDOR, productId);
        if (cached.isPresent()) {
            PriceResult result = cached.get();
            result.setTraceId(traceId);
            result.setSource(PriceSource.FALLBACK); // Ensure source is FALLBACK
            log.info("[{}] productId={} source=FALLBACK (from cache) price={}",
                      VENDOR.toUpperCase(), productId, result.getPrice());
            return result;
        }

        log.error("[{}] productId={} source=FALLBACK price=null (no cache available)",
                  VENDOR.toUpperCase(), productId);
        return PriceResult.error(VENDOR, "No price available", traceId);
    }
}
