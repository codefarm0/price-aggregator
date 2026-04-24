package in.codefarm.price.aggregator.mock;

import in.codefarm.price.aggregator.dto.PriceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;

@RestController
@RequestMapping("/mock-api")
public class MockVendorController {

    private final Random random = new Random();

    @GetMapping("/amazon/{productId}")
    public PriceResponse getAmazonPrice(@PathVariable String productId) {
        simulateLatency();
        double price = generatePrice();
        return new PriceResponse(productId, price, "amazon");
    }

    @GetMapping("/flipkart/{productId}")
    public PriceResponse getFlipkartPrice(@PathVariable String productId) {
        simulateLatency();
        double price = generatePrice();
        return new PriceResponse(productId, price, "flipkart");
    }

    @GetMapping("/walmart/{productId}")
    public PriceResponse getWalmartPrice(@PathVariable String productId) {
        simulateLatency();
        double price = generatePrice();
        return new PriceResponse(productId, price, "walmart");
    }

    private void simulateLatency() {
        try {
            Thread.sleep(random.nextInt(100, 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double generatePrice() {
        return Math.round(random.nextDouble(100, 1000) * 100.0) / 100.0;
    }
}