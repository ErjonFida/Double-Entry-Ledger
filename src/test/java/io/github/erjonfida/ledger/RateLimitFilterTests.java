package io.github.erjonfida.ledger;

import org.junit.jupiter.api.Test;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitFilterTests {

	@Test
	void requestsGoThroughWhenRedisIsDown() throws Exception {
		StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
			throw new RedisConnectionFailureException("Redis is down");
		});
		MockFilterChain chain = new MockFilterChain();

		new RateLimitFilter(redis).doFilter(new MockHttpServletRequest("POST", "/api/clients"),
				new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

}
