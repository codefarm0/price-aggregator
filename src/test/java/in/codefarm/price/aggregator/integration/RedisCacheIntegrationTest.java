package in.codefarm.price.aggregator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceSource;
import in.codefarm.price.aggregator.service.PriceCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PriceCacheService using a real Redis instance via Testcontainers.
 *
 * These tests verify actual Redis operations (set/get/evict/evictAll/isAvailable)
 * that cannot be fully tested with mocked RedisTemplate.
 *
 * Key behaviors tested:
 *   - JSON serialization/deserialization of PriceResult objects
 *   - TTL expiration on cached entries
 *   - Key format consistency (price:vendor:productId)
 *   - Graceful eviction of single and all keys
 *   - Redis health check via isAvailable()
 */
@SpringBootTest
@Testcontainers
@DisplayName("RedisCache Integration Tests")
class RedisCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.6-trixie")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.repositories.enabled", () -> false);
    }

    @Autowired
    private PriceCacheService cacheService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Clean all price keys before each test
        cacheService.evictAll();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        cacheService.evictAll();
    }

    /**
     * Verifies that set() stores a PriceResult as JSON with correct structure.
     *
     * Expected Redis value format:
     *   {"vendor":"amazon","price":999.99,"timestamp":1000,"source":"API","error":null}
     *
     * Assertions:
     *   - Value exists at key "price:amazon:iphone-15"
     *   - Value is valid JSON with correct vendor, price, timestamp, source fields
     *   - Key has TTL set (TTL > 0)
     */
    @Test
    @DisplayName("Should store PriceResult as JSON with TTL in Redis")
    void shouldStorePriceResultAsJsonWithTtl() throws Exception {
        cacheService.set("amazon", "iphone-15", 999.99, 1700000000L);

        String key = "price:amazon:iphone-15";
        String value = redisTemplate.opsForValue().get(key);

        assertNotNull(value, "Redis should contain value at price key");

        // Parse and verify JSON structure
        PriceResult result = objectMapper.readValue(value, PriceResult.class);
        assertEquals("amazon", result.getVendor());
        assertEquals(999.99, result.getPrice());
        assertEquals(1700000000L, result.getTimestamp());
        assertEquals(PriceSource.API, result.getSource());

        // Verify TTL is set (should be 600 seconds = 10 minutes from config)
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "Key should have TTL set");
        assertTrue(ttl <= 600, "TTL should not exceed configured 10 minutes");
    }

    /**
     * Verifies that getWithMetadata() deserializes the JSON back to PriceResult.
     *
     * Flow:
     *   1. Store price via cacheService.set()
     *   2. Retrieve via cacheService.getWithMetadata()
     *   3. Verify all fields match
     */
    @Test
    @DisplayName("Should retrieve PriceResult from Redis with all metadata intact")
    void shouldRetrievePriceResultWithMetadata() {
        cacheService.set("flipkart", "pixel-8", 749.99, 1700000000L);

        Optional<PriceResult> result = cacheService.getWithMetadata("flipkart", "pixel-8");

        assertTrue(result.isPresent(), "Should find cached value");
        assertEquals("flipkart", result.get().getVendor());
        assertEquals(749.99, result.get().getPrice());
        assertEquals(1700000000L, result.get().getTimestamp());
        assertEquals(PriceSource.API, result.get().getSource());
    }

    /**
     * Verifies that getWithMetadata() returns empty when key doesn't exist.
     */
    @Test
    @DisplayName("Should return empty Optional when cache key does not exist")
    void shouldReturnEmptyWhenKeyDoesNotExist() {
        Optional<PriceResult> result = cacheService.getWithMetadata("walmart", "nonexistent");

        assertFalse(result.isPresent(), "Should return empty for missing key");
    }

    /**
     * Verifies that evict() removes a single key from Redis.
     *
     * Flow:
     *   1. Store two keys
     *   2. Evict one
     *   3. Verify evicted key is gone, other key remains
     */
    @Test
    @DisplayName("Should remove only the specified vendor-product key on eviction")
    void shouldEvictSingleKey() {
        cacheService.set("amazon", "iphone-15", 999.99, 1000L);
        cacheService.set("flipkart", "iphone-15", 899.99, 1000L);

        cacheService.evict("amazon", "iphone-15");

        assertFalse(cacheService.getWithMetadata("amazon", "iphone-15").isPresent(),
                "Evicted key should be gone");
        assertTrue(cacheService.getWithMetadata("flipkart", "iphone-15").isPresent(),
                "Non-evicted key should remain");
    }

    /**
     * Verifies that evictAll() removes ALL keys matching the "price:*" pattern.
     *
     * Flow:
     *   1. Store 3 keys for different vendors
     *   2. Call evictAll()
     *   3. Verify all keys are gone
     */
    @Test
    @DisplayName("Should remove all price keys when evictAll is called")
    void shouldEvictAllPriceKeys() {
        cacheService.set("amazon", "item-1", 100.0, 1000L);
        cacheService.set("flipkart", "item-2", 200.0, 1000L);
        cacheService.set("walmart", "item-3", 300.0, 1000L);

        // Verify keys exist
        Set<String> keys = redisTemplate.keys("price:*");
        assertNotNull(keys);
        assertEquals(3, keys.size(), "Should have 3 cached keys");

        cacheService.evictAll();

        keys = redisTemplate.keys("price:*");
        assertTrue(keys == null || keys.isEmpty(), "All price keys should be evicted");
    }

    /**
     * Verifies that isAvailable() returns true when Redis is running.
     */
    @Test
    @DisplayName("Should return true when Redis is reachable (health check)")
    void shouldReturnTrueWhenRedisIsAvailable() {
        boolean available = cacheService.isAvailable();

        assertTrue(available, "Redis should be available via Testcontainers");

        // Verify the health check key was cleaned up
        String healthKey = redisTemplate.opsForValue().get("health-check");
        assertNull(healthKey, "Health check key should be cleaned up after isAvailable()");
    }

    /**
     * Verifies that multiple stores and retrieves work correctly for the same product
     * across different vendors (no key collision).
     */
    @Test
    @DisplayName("Should store and retrieve independently for different vendors with same product")
    void shouldStoreIndependentlyForDifferentVendors() {
        cacheService.set("amazon", "same-product", 100.0, 1000L);
        cacheService.set("flipkart", "same-product", 200.0, 2000L);
        cacheService.set("walmart", "same-product", 300.0, 3000L);

        assertEquals(100.0, cacheService.getWithMetadata("amazon", "same-product").get().getPrice());
        assertEquals(200.0, cacheService.getWithMetadata("flipkart", "same-product").get().getPrice());
        assertEquals(300.0, cacheService.getWithMetadata("walmart", "same-product").get().getPrice());
    }

    /**
     * Verifies that storing a new price for the same vendor+product overwrites the old value.
     */
    @Test
    @DisplayName("Should overwrite existing cache entry when storing same vendor+product")
    void shouldOverwriteExistingEntry() {
        cacheService.set("amazon", "iphone-15", 999.99, 1000L);
        cacheService.set("amazon", "iphone-15", 899.99, 2000L);

        Optional<PriceResult> result = cacheService.getWithMetadata("amazon", "iphone-15");
        assertTrue(result.isPresent());
        assertEquals(899.99, result.get().getPrice(), "Should return the latest price");
        assertEquals(2000L, result.get().getTimestamp(), "Should return the latest timestamp");
    }
}
