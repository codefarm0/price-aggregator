package in.codefarm.price.aggregator.driver;

import in.codefarm.price.aggregator.external.AmazonClient;
import in.codefarm.price.aggregator.external.FlipkartClient;
import in.codefarm.price.aggregator.external.PriceAggregator;
import in.codefarm.price.aggregator.external.WalmartClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceFetcherPhase2 {
    static void main() {
        PriceAggregator amazon = new AmazonClient();
        PriceAggregator flipkart = new FlipkartClient();
        PriceAggregator walmart = new WalmartClient();

        String productId = "iphone-15";

        ExecutorService executor = Executors.newFixedThreadPool(10);

        long start = Instant.now().toEpochMilli();
        //amazon
        CompletableFuture<Double> amazonPrice =
                CompletableFuture.supplyAsync(() -> amazon.getPrice(productId), executor);

        //flipkar
        CompletableFuture<Double> flipkartPrice =
                CompletableFuture.supplyAsync(() -> flipkart.getPrice(productId), executor);


        //walmart
        CompletableFuture<Double> walmartPrice =
                CompletableFuture.supplyAsync(() -> walmart.getPrice(productId), executor);

        List<Double> prices = CompletableFuture
                .allOf(amazonPrice, flipkartPrice, walmartPrice)
                .thenApply(v -> List.of(
                        amazonPrice.join(),
                        flipkartPrice.join(),
                        walmartPrice.join()
                ))
                .join();

        System.out.printf("total time to fetch prices - %d ms", (Instant.now().toEpochMilli() - start) );

        executor.shutdown();

    }
}

