package com.adonis.chattywap.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security configuration.
 * <p>
 * Every HTTP request passes through a chain of servlet filters before it reaches a controller. This
 * {@link SecurityFilterChain} decides which filters run and which requests need an authenticated user.
 * Defining it replaces Spring Boot's default configuration (form login, HTTP Basic, generated password).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
		AuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(jsonMapper);

		http
				// Deny by default: only the endpoints listed here are public.
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(HttpMethod.POST,
								"/api/v1/auth/register",
								"/api/v1/auth/login",
								"/api/v1/auth/refresh",
								"/api/v1/auth/logout").permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
						// Spring Boot forwards failed requests to /error to render them. Keeping it public
						// stops a server error from being reported as 401.
						.requestMatchers("/error").permitAll()
						.anyRequest().authenticated())
				// Stateless: no HTTP session. Each request must carry its own access token.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// CSRF attacks rely on the browser attaching credentials (cookies) automatically. Bearer tokens
				// are added by the client explicitly, so CSRF protection isn't needed here.
				.csrf(csrf -> csrf.disable())
				// Spring Security's own POST /logout ends HTTP sessions; there are none. Logout is
				// POST /api/v1/auth/logout, which revokes a refresh token.
				.logout(logout -> logout.disable())
				// Adds BearerTokenAuthenticationFilter: it reads "Authorization: Bearer <token>", validates the
				// JWT with the JwtDecoder bean (JwtConfig) and stores the resulting Authentication in the
				// SecurityContext for the rest of the request.
				.oauth2ResourceServer(oauth2 -> oauth2
						.bearerTokenResolver(bearerTokenResolver())
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(entryPoint))
				// Requests without any token are rejected by the authorization rules above; answer those
				// with the same 401 response as requests with a bad token.
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));

		return http.build();
	}

	/**
	 * Reads the access token from the {@code Authorization} header, except on the authentication endpoints.
	 * <p>
	 * {@code permitAll()} doesn't stop the bearer-token filter: if a request carries a token, the filter
	 * validates it and rejects the request when it's invalid. Clients often attach their access token to every
	 * request, and a refresh request is exactly when that token has expired. Ignoring the header on these
	 * endpoints lets such a client still refresh, log out or log in.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
		RequestMatcher authEndpoints = PathPatternRequestMatcher.pathPattern("/api/v1/auth/**");
		return request -> authEndpoints.matches(request) ? null : headerResolver.resolve(request);
	}

	/**
	 * Hashes new passwords with BCrypt and stores them as {@code {bcrypt}$2a$10$...}. The prefix names the
	 * algorithm, so a later switch (for example to Argon2) can still verify existing hashes.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
