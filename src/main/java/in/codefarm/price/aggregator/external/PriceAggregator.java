package in.codefarm.price.aggregator.external;

import in.codefarm.price.aggregator.dto.PriceResult;

public interface PriceAggregator {

    PriceResult getPrice(String productId, boolean refreshCache);

    PriceResult getFallbackPrice(String productId, Throwable t);
}
