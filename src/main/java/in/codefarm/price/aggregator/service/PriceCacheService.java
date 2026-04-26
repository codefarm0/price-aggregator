package in.codefarm.price.aggregator.service;

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

    private final RedisTemplate<String, String> redisTemplate;
    private final Duration ttl;

    public PriceCacheService(
            RedisTemplate<String, String> redisTemplate,
            @Value("${price.cache.ttl-minutes:10}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public Optional<Double> get(String vendor, String productId) {
        String key = buildKey(vendor, productId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.info("Redis cache hit for key: {}", key);
                return Optional.of(Double.parseDouble(value));
            }
            log.info("Redis cache miss for key: {}", key);
        } catch (Exception e) {
            log.warn("Failed to get from Redis for key: {}, error: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public void set(String vendor, String productId, double price) {
        String key = buildKey(vendor, productId);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(price), ttl);
            log.info("Cached price {} for key: {} with TTL: {}", price, key, ttl);
        } catch (Exception e) {
            log.warn("Failed to set Redis for key: {}, error: {}", key, e.getMessage());
        }
    }

    public void evict(String vendor, String productId) {
        String key = buildKey(vendor, productId);
        try {
            redisTemplate.delete(key);
            log.info("Evicted cache for key: {}", key);
        } catch (Exception e) {
            log.warn("Failed to evict Redis for key: {}, error: {}", key, e.getMessage());
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