package io.github.erjonfida.ledger;

import java.util.List;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@ConditionalOnWebApplication
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
		paramName = "X-API-Key")
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, ClientRepository clients, StringRedisTemplate redis) throws Exception {
		Filter apiKeys = (request, response, chain) -> {
			String apiKey = ((HttpServletRequest) request).getHeader("X-API-Key");
			if (apiKey != null) {
				clients.findByApiKeyHash(Client.hash(apiKey)).ifPresent(client -> {
					SecurityContext context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(
							UsernamePasswordAuthenticationToken.authenticated(client.getId(), null, List.of()));
					SecurityContextHolder.setContext(context);
				});
			}
			chain.doFilter(request, response);
		};
		return http
			.addFilterBefore(apiKeys, AnonymousAuthenticationFilter.class)
			.addFilterAfter(new RateLimitFilter(redis), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.POST, "/api/clients").permitAll()
				.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(AbstractHttpConfigurer::disable)
			.build();
	}

}
