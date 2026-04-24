package in.codefarm.price.aggregator.service;

import in.codefarm.price.aggregator.external.PriceAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final List<PriceAggregator> priceAggregators;
    private final Executor priceTaskExecutor;
    private final long timeoutMs;

    public PriceService(
            List<PriceAggregator> priceAggregators,
            @Qualifier("priceTaskExecutor") Executor priceTaskExecutor,
            @Value("${price.fetch.timeout-ms:1000}") long timeoutMs) {
        this.priceAggregators = priceAggregators;
        this.priceTaskExecutor = priceTaskExecutor;
        this.timeoutMs = timeoutMs;
    }

    public Map<String, Double> fetchPrices(String productId) {
        long start = Instant.now().toEpochMilli();

        List<CompletableFuture<Double>> futures = priceAggregators.stream()
                .map(client -> fetchWithFallback(client, productId))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, Double> prices = priceAggregators.stream()
                .collect(Collectors.toMap(
                        c -> c.getClass().getSimpleName().replace("Client", "").toLowerCase(),
                        c -> futures.get(priceAggregators.indexOf(c)).join()));

        log.info("Fetched prices for product {} in {} ms", productId,
                Instant.now().toEpochMilli() - start);

        return prices;
    }

    private CompletableFuture<Double> fetchWithFallback(PriceAggregator client, String productId) {
        return CompletableFuture.supplyAsync(() -> client.getPrice(productId), priceTaskExecutor)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch price from {}, using fallback",
                            client.getClass().getSimpleName());
                    return client.getFallbackPrice(productId);
                });
    }
}