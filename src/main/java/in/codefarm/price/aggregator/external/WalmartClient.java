package in.codefarm.price.aggregator.external;

import in.codefarm.price.aggregator.dto.PriceResponse;
import in.codefarm.price.aggregator.service.PriceCacheService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            .onStateTransition(event -> log.info("Walmart circuit state transition: {} -> {}", 
                event.getStateTransition().getFromState(), 
                event.getStateTransition().getToState()))
            .onCallNotPermitted(event -> log.warn("Walmart circuit: call NOT permitted (circuit OPEN)"))
            .onSuccess(event -> log.debug("Walmart circuit: call succeeded"))
            .onError(event -> log.warn("Walmart circuit: call failed - {}", event.getThrowable().getMessage()));
    }

    @Override
    public double getPrice(String productId) {
        Optional<Double> cached = cacheService.get(VENDOR, productId);
        if (cached.isPresent()) {
            log.debug("Cache hit for product {} on Walmart: {}", productId, cached.get());
            return cached.get();
        }
        return fetchPriceFromApi(productId);
    }

    private double fetchPriceFromApi(String productId) {
        log.info("Cache miss - fetching price for product {} from Walmart", productId);

        Supplier<PriceResponse> supplier = () -> webClient.get()
                .uri(baseUrl + "/mock-api/walmart/{productId}", productId)
                .retrieve()
                .bodyToMono(PriceResponse.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        try {
            PriceResponse response = circuitBreaker.executeSupplier(supplier);
            if (response != null) {
                cacheService.set(VENDOR, productId, response.getPrice());
                log.info("Walmart price for {}: {}", productId, response.getPrice());
                return response.getPrice();
            }
        } catch (Exception e) {
            log.warn("Walmart API call failed, circuit state: {}", circuitBreaker.getState(), e);
        }

        return getFallbackPrice(productId);
    }

    @Override
    public double getFallbackPrice(String productId) {
        return getFallbackPrice(productId, null);
    }

    public double getFallbackPrice(String productId, Throwable t) {
        if (t != null) {
            log.warn("Walmart circuit breaker triggered for product {}: {}", productId, t.getMessage());
        }
        return cacheService.get(VENDOR, productId).orElse(0.0);
    }
}