package in.codefarm.price.aggregator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.codefarm.price.aggregator.dto.PriceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class PriceCacheService {

    private static final Logger log = LoggerFactory.getLogger(PriceCacheService.class);
    private static final String KEY_PREFIX = "price:";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;
    private final Duration ttl;

    public PriceCacheService(
            RedisTemplate<String, String> redisTemplate,
            @Value("${price.cache.ttl-minutes:10}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public Optional<PriceResult> getWithMetadata(String vendor, String productId) {
        String key = buildKey(vendor, productId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.info("[{}] Cache HIT for key: {}", vendor.toUpperCase(), key);
                PriceResult result = objectMapper.readValue(value, PriceResult.class);
                return Optional.of(result);
            }
            log.info("[{}] Cache MISS for key: {}", vendor.toUpperCase(), key);
        } catch (Exception e) {
            log.warn("[{}] Failed to get from Redis for key: {}, error: {}", vendor.toUpperCase(), key, e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Double> get(String vendor, String productId) {
        String key = buildKey(vendor, productId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.info("[{}] Cache HIT for key: {}", vendor.toUpperCase(), key);
                // Try to parse as PriceResult JSON first, fallback to simple double
                try {
                    PriceResult result = objectMapper.readValue(value, PriceResult.class);
                    return Optional.of(result.getPrice());
                } catch (JsonProcessingException e) {
                    // Legacy format - just a double
                    return Optional.of(Double.parseDouble(value));
                }
            }
            log.info("[{}] Cache MISS for key: {}", vendor.toUpperCase(), key);
        } catch (Exception e) {
            log.warn("[{}] Failed to get from Redis for key: {}, error: {}", vendor.toUpperCase(), key, e.getMessage());
        }
        return Optional.empty();
    }

    public void set(String vendor, String productId, double price, long timestamp) {
        String key = buildKey(vendor, productId);
        try {
            // Store with source=API (fresh data), traceId is @JsonIgnore so not serialized
            PriceResult result = PriceResult.fromApi(vendor, price, timestamp, "ignored");
            String jsonValue = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key, jsonValue, ttl);
            log.info("[{}] Cached price {} for key: {} with TTL: {}", vendor.toUpperCase(), price, key, ttl);
        } catch (Exception e) {
            log.warn("[{}] Failed to set Redis for key: {}, error: {}", vendor.toUpperCase(), key, e.getMessage());
        }
    }

    public void evict(String vendor, String productId) {
        String key = buildKey(vendor, productId);
        try {
            redisTemplate.delete(key);
            log.info("[{}] Evicted cache for key: {}", vendor.toUpperCase(), key);
        } catch (Exception e) {
            log.warn("[{}] Failed to evict Redis for key: {}, error: {}", vendor.toUpperCase(), key, e.getMessage());
        }
    }

    public void evictAll() {
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Evicted all {} cache entries", keys.size());
            }
        } catch (Exception e) {
            log.warn("Failed to evict all Redis cache: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            String testKey = "health-check";
            redisTemplate.opsForValue().set(testKey, "ok", Duration.ofSeconds(5));
            redisTemplate.delete(testKey);
            return true;
        } catch (Exception e) {
            log.warn("Redis is not available: {}", e.getMessage());
            return false;
        }
    }

    private String buildKey(String vendor, String productId) {
        return KEY_PREFIX + vendor + ":" + productId;
    }
}
