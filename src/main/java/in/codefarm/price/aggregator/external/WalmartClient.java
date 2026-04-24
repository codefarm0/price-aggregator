package in.codefarm.price.aggregator.external;

import java.util.Random;

public class WalmartClient implements PriceAggregator{
    private double lastFetchedPrice = 0.0;
    public double getPrice(String productId){
        int num = new Random().nextInt(100,1000);
        try {
            Thread.sleep(num);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.printf("Took - %d ms to fetch the product %s price from walmart\n ", num, productId);
        lastFetchedPrice = num * 8.5 / 10.0;
        return lastFetchedPrice;
    }

    @Override
    public double lastFetchedPrice() {
        System.out.println("fetching last fetched price for walmart - " + lastFetchedPrice);
        return lastFetchedPrice;
    }
}
