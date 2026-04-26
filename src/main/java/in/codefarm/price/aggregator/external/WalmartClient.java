package in.codefarm.price.aggregator.external;

import in.codefarm.price.aggregator.dto.PriceResponse;
import in.codefarm.price.aggregator.service.PriceCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

@Component
public class WalmartClient implements PriceAggregator {

    private static final Logger log = LoggerFactory.getLogger(WalmartClient.class);
    private static final String VENDOR = "walmart";

    private final WebClient webClient;
    private final PriceCacheService cacheService;
    private final String baseUrl;

    public WalmartClient(
            WebClient webClient,
            PriceCacheService cacheService,
            @Value("${vendors.walmart.base-url:http://localhost:8080}") String baseUrl) {
        this.webClient = webClient;
        this.cacheService = cacheService;
        this.baseUrl = baseUrl;
    }

    @Override
    public double getPrice(String productId) {
        Optional<Double> cached = cacheService.get(VENDOR, productId);
        if (cached.isPresent()) {
            log.info("Cache hit for product {} on Walmart: {}", productId, cached.get());
            return cached.get();
        }
        return fetchPriceFromApi(productId);
    }

    private double fetchPriceFromApi(String productId) {
        log.info("Cache miss - fetching price for product {} from Walmart", productId);

        try {
            PriceResponse response = webClient.get()
                    .uri(baseUrl + "/mock-api/walmart/{productId}", productId)
                    .retrieve()
                    .bodyToMono(PriceResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            if (response != null) {
                cacheService.set(VENDOR, productId, response.getPrice());
                log.info("Walmart price for {}: {}", productId, response.getPrice());
                return response.getPrice();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch from Walmart API: {}", e.getMessage());
        }

        return getFallbackPrice(productId);
    }

    @Override
    public double getFallbackPrice(String productId) {
        return cacheService.get(VENDOR, productId).orElse(0.0);
    }
}