package in.codefarm.price.aggregator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAsync
public class PriceConfig {

    @Value("${price.pool.core-size:3}")
    private int corePoolSize;

    @Value("${price.pool.max-size:10}")
    private int maxPoolSize;

    @Value("${price.pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${price.pool.thread-name-prefix:price-fetch-}")
    private String threadNamePrefix;

    @Bean(name = "priceTaskExecutor")
    public Executor priceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setThreadFactory(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, threadNamePrefix + counter.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}