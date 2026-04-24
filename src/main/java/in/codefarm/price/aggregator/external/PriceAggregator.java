package in.codefarm.price.aggregator.external;

public interface PriceAggregator {

    double getPrice(String productId);

    double lastFetchedPrice();

}
