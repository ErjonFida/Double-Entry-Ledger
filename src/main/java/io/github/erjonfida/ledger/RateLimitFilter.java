package io.github.erjonfida.ledger;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class RateLimitFilter extends OncePerRequestFilter {

	static final int REQUESTS_PER_MINUTE = 60;

	static final int REGISTRATIONS_PER_HOUR = 5;

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private static final RedisScript<Long> SECONDS_TO_WAIT = RedisScript.of("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			    redis.call('EXPIRE', KEYS[1], ARGV[1])
			end
			if count > tonumber(ARGV[2]) then
			    return math.max(redis.call('TTL', KEYS[1]), 1)
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	RateLimitFilter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long wait = secondsToWait(request);
		if (wait > 0) {
			response.setStatus(429);
			response.setHeader("Retry-After", Long.toString(wait));
			response.setContentType("application/problem+json");
			response.getWriter().write("""
					{"title":"Too Many Requests","status":429,"detail":"Rate limit reached, retry in %d seconds"}"""
				.formatted(wait));
			return;
		}
		chain.doFilter(request, response);
	}

	private long secondsToWait(HttpServletRequest request) {
		if (request.getMethod().equals("POST") && request.getRequestURI().equals("/api/clients")) {
			return secondsToWait("register:" + request.getRemoteAddr(), REGISTRATIONS_PER_HOUR, Duration.ofHours(1));
		}
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof UUID client) {
			return secondsToWait("client:" + client, REQUESTS_PER_MINUTE, Duration.ofMinutes(1));
		}
		return 0;
	}

	// ponytail: fixed window, so up to twice the limit fits across a window boundary; a sliding window if that matters
	private long secondsToWait(String name, int limit, Duration window) {
		try {
			return redis.execute(SECONDS_TO_WAIT, List.of("rate:" + name), Long.toString(window.toSeconds()),
					Integer.toString(limit));
		}
		catch (DataAccessException e) {
			log.warn("Rate limiter unavailable, letting the request through: {}", e.getMessage());
			return 0;
		}
	}

}
