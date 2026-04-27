package in.codefarm.price.aggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.data.redis.repositories.enabled=false"
})
class PriceAggregatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
