package in.codefarm.price.aggregator.driver;

import in.codefarm.price.aggregator.external.AmazonClient;
import in.codefarm.price.aggregator.external.FlipkartClient;
import in.codefarm.price.aggregator.external.PriceAggregator;
import in.codefarm.price.aggregator.external.WalmartClient;

import java.time.Instant;
import java.util.List;

public class PriceFetcherPhase1 {
    public static void main(String[] args) {

        PriceAggregator amazon = new AmazonClient();
        PriceAggregator flipkart = new FlipkartClient();
        PriceAggregator walmart = new WalmartClient();

        List<PriceAggregator> priceAggregators = List.of(amazon, flipkart, walmart);
        String productId = "iphone-15";
        long start = Instant.now().toEpochMilli();
        for (PriceAggregator aggregator : priceAggregators) {
            double price = aggregator.getPrice(productId);
        }

        System.out.printf("total time to fetch prices - %d ms", (Instant.now().toEpochMilli() - start) );
    }
}

