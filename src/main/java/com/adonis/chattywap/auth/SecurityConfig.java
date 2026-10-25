package com.adonis.chattywap.auth;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security configuration.
 * <p>
 * Every HTTP request passes through a chain of servlet filters before it reaches a controller. This
 * {@link SecurityFilterChain} decides which filters run and which requests need an authenticated user.
 * Defining it replaces Spring Boot's default configuration (form login, HTTP Basic, generated password).
 * <p>
 * The chain also protects the management server, which serves the actuator endpoints on a port of its own: there,
 * exposed endpoints need no token, because only operators can reach that port (see ARCHITECTURE.md, decision 22).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	/**
	 * The path of the STOMP endpoint, as {@code realtime} registers it. Repeated here, because {@code auth} doesn't
	 * depend on {@code realtime}.
	 */
	private static final String WEBSOCKET_ENDPOINT = "/ws";

	/**
	 * The health groups that Spring Boot also serves on the application port
	 * ({@code management.endpoint.health.probes.add-additional-paths}).
	 */
	private static final String LIVENESS_PROBE = "/livez";

	private static final String READINESS_PROBE = "/readyz";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper, Environment environment)
			throws Exception {
		AuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(jsonMapper);
		boolean separateManagementServer = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;

		http
				// Deny by default: only the endpoints listed here are public.
				.authorizeHttpRequests(requests -> {
					requests
							.requestMatchers(HttpMethod.POST,
									"/api/v1/auth/register",
									"/api/v1/auth/login",
									"/api/v1/auth/refresh",
									"/api/v1/auth/logout").permitAll()
							// Liveness and readiness probes on the application port. They answer with a status only.
							.requestMatchers(HttpMethod.GET, LIVENESS_PROBE, READINESS_PROBE).permitAll()
							// The WebSocket handshake of the STOMP endpoint (realtime.WebSocketConfig). STOMP clients
							// authenticate in their CONNECT frame instead, with the same access token, and the server
							// accepts no other frame from a connection before that (realtime.StompSecurityInterceptor).
							.requestMatchers(HttpMethod.GET, WEBSOCKET_ENDPOINT).permitAll();
					// Actuator endpoints (health details, metrics) are for operators. They are served only on the
					// management port, which must not be reachable from outside (MANAGEMENT_ADDRESS): access to that
					// network is the authorization. With a separate management server, these matchers match only its
					// requests. Without one, the endpoints would be on the application port, so they are refused.
					if (separateManagementServer) {
						requests.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
					}
					else {
						requests.requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll();
					}
					requests
							// Spring Boot forwards failed requests to /error to render them. Keeping it public
							// stops a server error from being reported as 401.
							.requestMatchers("/error").permitAll()
							.anyRequest().authenticated();
				})
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
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
				// The caller's user ID in the log context, once the token is validated.
				.addFilterAfter(new CallerLogContextFilter(), BearerTokenAuthenticationFilter.class);

		return http.build();
	}

	/**
	 * Reads the access token from the {@code Authorization} header, except on the authentication endpoints and the
	 * WebSocket handshake.
	 * <p>
	 * {@code permitAll()} doesn't stop the bearer-token filter: if a request carries a token, the filter
	 * validates it and rejects the request when it's invalid. Clients often attach their access token to every
	 * request, and a refresh request is exactly when that token has expired. Ignoring the header on these
	 * endpoints lets such a client still refresh, log out or log in. On the handshake, the token in the STOMP
	 * CONNECT frame is the only one that counts, so a token in the HTTP header neither helps nor hurts.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
		RequestMatcher ignored = new OrRequestMatcher(
				PathPatternRequestMatcher.pathPattern("/api/v1/auth/**"),
				PathPatternRequestMatcher.pathPattern(WEBSOCKET_ENDPOINT));
		return request -> ignored.matches(request) ? null : headerResolver.resolve(request);
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
