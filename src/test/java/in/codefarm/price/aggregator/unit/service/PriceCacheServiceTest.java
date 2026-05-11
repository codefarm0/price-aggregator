package in.codefarm.price.aggregator.unit.service;

import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceSource;
import in.codefarm.price.aggregator.service.PriceCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceCacheService Unit Tests")
class PriceCacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PriceCacheService priceCacheService;

    @BeforeEach
    void setUp() {
        priceCacheService = new PriceCacheService(redisTemplate, 10);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Should store price as JSON with vendor and timestamp metadata")
    void shouldStorePriceAsJsonWithMetadata() {
        priceCacheService.set("amazon", "iphone-15", 999.99, 1000L);

        verify(valueOperations).set(
                eq("price:amazon:iphone-15"),
                contains("\"price\":999.99"),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("Should return cached PriceResult with metadata on cache hit")
    void shouldReturnCachedResultWithMetadataOnHit() {
        String jsonResult = "{\"vendor\":\"amazon\",\"price\":999.99,\"timestamp\":1000,\"source\":\"API\"}";
        when(valueOperations.get("price:amazon:iphone-15")).thenReturn(jsonResult);

        Optional<PriceResult> result = priceCacheService.getWithMetadata("amazon", "iphone-15");

        assertTrue(result.isPresent());
        assertEquals(999.99, result.get().getPrice());
        assertEquals("amazon", result.get().getVendor());
        assertEquals(PriceSource.API, result.get().getSource());
    }

    @Test
    @DisplayName("Should return empty optional on cache miss")
    void shouldReturnEmptyOnCacheMiss() {
        when(valueOperations.get("price:amazon:iphone-15")).thenReturn(null);

        Optional<PriceResult> result = priceCacheService.getWithMetadata("amazon", "iphone-15");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should return empty optional when Redis throws exception")
    void shouldReturnEmptyOnRedisException() {
        when(valueOperations.get("price:amazon:iphone-15")).thenThrow(new RuntimeException("Redis down"));

        Optional<PriceResult> result = priceCacheService.getWithMetadata("amazon", "iphone-15");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should delete specific vendor-product key on eviction")
    void shouldDeleteKeyOnEviction() {
        priceCacheService.evict("amazon", "iphone-15");

        verify(redisTemplate).delete("price:amazon:iphone-15");
    }

    @Test
    @DisplayName("Should delete all price keys when evictAll is called")
    void shouldDeleteAllKeysOnEvictAll() {
        Set<String> keys = Set.of("price:amazon:iphone", "price:flipkart:iphone");
        when(redisTemplate.keys("price:*")).thenReturn(keys);

        priceCacheService.evictAll();

        verify(redisTemplate).delete(keys);
    }

    @Test
    @DisplayName("Should return true when Redis connection is healthy")
    void shouldReturnTrueWhenRedisIsHealthy() {
        boolean available = priceCacheService.isAvailable();

        assertTrue(available);
        verify(valueOperations).set(eq("health-check"), eq("ok"), any(Duration.class));
        verify(redisTemplate).delete("health-check");
    }

    @Test
    @DisplayName("Should return false when Redis connection fails")
    void shouldReturnFalseWhenRedisFails() {
        doThrow(new RuntimeException("Redis down")).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        boolean available = priceCacheService.isAvailable();

        assertFalse(available);
    }

    @Test
    @DisplayName("Should build cache key in format price:vendor:productId")
    void shouldBuildCacheKeyInCorrectFormat() {
        String key = "price:amazon:iphone-15";
        when(valueOperations.get(key)).thenReturn("{\"vendor\":\"amazon\",\"price\":999.99,\"timestamp\":1000,\"source\":\"API\"}");

        Optional<PriceResult> result = priceCacheService.getWithMetadata("amazon", "iphone-15");

        assertTrue(result.isPresent());
        assertEquals("amazon", result.get().getVendor());
    }
}
