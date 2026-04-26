package in.codefarm.price.aggregator.external;

import in.codefarm.price.aggregator.dto.PriceResponse;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class AmazonClient implements PriceAggregator {

    private static final Logger log = LoggerFactory.getLogger(AmazonClient.class);

    private final WebClient webClient;
    private final String baseUrl;
    private final Cache<String, Double> priceCache;

    public AmazonClient(
            WebClient webClient,
            @Qualifier("priceCache") Cache<String, Double> priceCache,
            @Value("${vendors.amazon.base-url:http://localhost:8080}") String baseUrl) {
        this.webClient = webClient;
        this.priceCache = priceCache;
        this.baseUrl = baseUrl;
    }

    @Override
    public double getPrice(String productId) {
        Double cachedPrice = priceCache.getIfPresent(productId+"~amazon");
        if (cachedPrice != null) {
            log.info("Cache hit for product {} on Amazon: {}", productId, cachedPrice);
            return cachedPrice;
        }
        return fetchPriceFromApi(productId);
    }

    private double fetchPriceFromApi(String productId) {
        log.info("Cache miss - fetching price for product {} from Amazon", productId);

        try {
            PriceResponse response = webClient.get()
                    .uri(baseUrl + "/mock-api/amazon/{productId}", productId)
                    .retrieve()
                    .bodyToMono(PriceResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            if (response != null) {
                priceCache.put(productId+"~amazon", response.getPrice());
                log.info("Amazon price for {}: {}", productId, response.getPrice());
                return response.getPrice();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch from Amazon API: {}", e.getMessage());
        }

        return getFallbackPrice(productId);
    }

    @Override
    public double getFallbackPrice(String productId) {
        Double cached = priceCache.getIfPresent(productId);
        return cached != null ? cached : 0.0;
    }
}