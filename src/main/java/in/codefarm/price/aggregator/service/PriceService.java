package in.codefarm.price.aggregator.service;

import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.external.PriceAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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

    public List<PriceResult> fetchPrices(String productId, boolean refreshCache) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = java.util.UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
        }

        long start = Instant.now().toEpochMilli();
        log.info("Fetching prices for product={} refreshCache={}", productId, refreshCache);

        List<CompletableFuture<PriceResult>> futures = priceAggregators.stream()
                .map(client -> fetchWithFallback(client, productId, refreshCache))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<PriceResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long duration = Instant.now().toEpochMilli() - start;
        log.info("Fetched {} results for product={} in {}ms",
                results.size(), productId, duration);

        return results;
    }

    private CompletableFuture<PriceResult> fetchWithFallback(PriceAggregator client, String productId, boolean refreshCache) {
        return CompletableFuture.supplyAsync(() -> {
            // MDC is automatically propagated via MdcTaskDecorator
            return client.getPrice(productId, refreshCache);
        }, priceTaskExecutor)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("Failed to fetch price from {}, using fallback",
                            client.getClass().getSimpleName());
                    return client.getFallbackPrice(productId, ex);
                });
    }
}
