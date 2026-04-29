package in.codefarm.price.aggregator.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class PriceResult {

    private String vendor;
    private Double price;          // null if error
    private Long timestamp;         // 3rd party API timestamp
    private PriceSource source;     // enum: CACHE | API | FALLBACK
    private String error;           // error message if failed
    @JsonIgnore
    private String traceId;         // MDC trace ID - not cached, set per request

    public PriceResult() {}

    public PriceResult(String vendor, Double price, Long timestamp, PriceSource source, String traceId) {
        this.vendor = vendor;
        this.price = price;
        this.timestamp = timestamp;
        this.source = source;
        this.traceId = traceId;
    }

    public PriceResult(String vendor, Double price, Long timestamp, PriceSource source, String traceId, String error) {
        this.vendor = vendor;
        this.price = price;
        this.timestamp = timestamp;
        this.source = source;
        this.traceId = traceId;
        this.error = error;
    }

    // Factory methods
    public static PriceResult fromCache(String vendor, Double price, Long timestamp, String traceId) {
        return new PriceResult(vendor, price, timestamp, PriceSource.CACHE, traceId);
    }

    public static PriceResult fromApi(String vendor, Double price, Long timestamp, String traceId) {
        return new PriceResult(vendor, price, timestamp, PriceSource.API, traceId);
    }

    public static PriceResult fromFallback(String vendor, Double price, Long timestamp, String traceId) {
        return new PriceResult(vendor, price, timestamp, PriceSource.FALLBACK, traceId);
    }

    public static PriceResult error(String vendor, String error, String traceId) {
        PriceResult result = new PriceResult(vendor, null, null, PriceSource.FALLBACK, traceId);
        result.setError(error);
        return result;
    }

    // Getters and Setters
    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public PriceSource getSource() {
        return source;
    }

    public void setSource(PriceSource source) {
        this.source = source;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
