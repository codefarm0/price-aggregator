package in.codefarm.price.aggregator.mock;

import in.codefarm.price.aggregator.dto.PriceResponse;
import in.codefarm.price.aggregator.service.PriceCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/mock-api")
public class MockVendorController {

    private static final Logger log = LoggerFactory.getLogger(MockVendorController.class);
    private final Random random = new Random();
    private final PriceCacheService cacheService;
    
    // Chaos mode settings
    private final AtomicBoolean chaosEnabled = new AtomicBoolean(false);
    private final AtomicInteger failureRatePercent = new AtomicInteger(0); // 0-100
    private final AtomicInteger delayMs = new AtomicInteger(100); // Normal delay ms

    public MockVendorController(PriceCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/amazon/{productId}")
    public PriceResponse getAmazonPrice(@PathVariable String productId) {
        log.info("fetching price from amazon for product {}", productId);
        simulateChaos("amazon");
        double price = generatePrice();
        return new PriceResponse(productId, price, "amazon");
    }

    @GetMapping("/flipkart/{productId}")
    public PriceResponse getFlipkartPrice(@PathVariable String productId) {
        log.info("fetching price from flipkart for product {}", productId);
        simulateChaos("flipkart");
        double price = generatePrice();
        return new PriceResponse(productId, price, "flipkart");
    }

    @GetMapping("/walmart/{productId}")
    public PriceResponse getWalmartPrice(@PathVariable String productId) {
        log.info("fetching price from walmart for product {}", productId);
        simulateChaos("walmart");
        double price = generatePrice();
        return new PriceResponse(productId, price, "walmart");
    }


    // Chaos endpoints for circuit breaker testing
    @GetMapping("/chaos")
    public ChaosStatus getChaosStatus() {
        return new ChaosStatus(
            chaosEnabled.get(),
            failureRatePercent.get(),
            delayMs.get()
        );
    }

    @GetMapping("/chaos/enable")
    public String enableChaos(
            @RequestParam(defaultValue = "50") int failureRate,
            @RequestParam(defaultValue = "3000") int delay) {
        chaosEnabled.set(true);
        failureRatePercent.set(Math.min(100, Math.max(0, failureRate)));
        delayMs.set(delay);
        return String.format("Chaos enabled: %d%% failures, %dms delay", failureRatePercent.get(), delayMs.get());
    }

    @GetMapping("/chaos/disable")
    public String disableChaos() {
        chaosEnabled.set(false);
        delayMs.set(100);
        failureRatePercent.set(0);
        return "Chaos disabled - normal operation";
    }

    @GetMapping("/chaos/reset")
    public String resetChaos() {
        chaosEnabled.set(false);
        failureRatePercent.set(0);
        delayMs.set(100);
        cacheService.evictAll();
        return "Chaos state reset, cache cleared";
    }

    // Pre-configured test scenarios
    @GetMapping("/chaos/scenario/fast-failures")
    public String scenarioFastFailures() {
        chaosEnabled.set(true);
        failureRatePercent.set(100); // All calls fail immediately
        delayMs.set(50);
        cacheService.evictAll();
        return "Scenario: Fast Failures - All calls will fail instantly (for testing circuit OPEN)";
    }

    @GetMapping("/chaos/scenario/slow-responses")
    public String scenarioSlowResponses() {
        chaosEnabled.set(true);
        failureRatePercent.set(0);
        delayMs.set(500); // 500ms delay (exceeds 100ms slowCallDurationThreshold)
        cacheService.evictAll();
        return "Scenario: Slow Responses - All calls will take 500ms (> 100ms threshold)";
    }

    @GetMapping("/chaos/scenario/unstable")
    public String scenarioUnstable() {
        chaosEnabled.set(true);
        failureRatePercent.set(70); // 70% failures
        delayMs.set(3000);
        cacheService.evictAll();
        return "Scenario: Unstable - 70% failures, 3s delay (will trip circuit breaker)";
    }

    private void simulateChaos(String vendor) {
        // Check if chaos is enabled
        if (!chaosEnabled.get()) {
            simulateNormalLatency();
            return;
        }
        
        // Simulate delay
        int actualDelay = delayMs.get();
        if (actualDelay > 0) {
            try {
                Thread.sleep(actualDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Simulate failure based on failure rate
        if (random.nextInt(100) < failureRatePercent.get()) {
            throw new RuntimeException("Chaos: Simulated failure from " + vendor);
        }
    }

    private void simulateNormalLatency() {
        try {
            Thread.sleep(random.nextInt(50, 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double generatePrice() {
        return Math.round(random.nextDouble(100, 1000) * 100.0) / 100.0;
    }

    public record ChaosStatus(boolean enabled, int failureRate, int delayMs) {}
}