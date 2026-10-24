package com.adonis.chattywap.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Writes the 401 response when a request to a protected endpoint has no valid access token.
 * <p>
 * Spring Security rejects such requests in its filter chain, before Spring MVC and its exception handlers
 * run. An "entry point" is the hook Spring Security calls at that moment. This one keeps the standard
 * bearer-token behavior (status 401 and a {@code WWW-Authenticate: Bearer ...} header, RFC 6750) and adds
 * a problem-detail body, so the client sees the same error format as everywhere else.
 */
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final AuthenticationEntryPoint bearerTokenEntryPoint = new BearerTokenAuthenticationEntryPoint();

	private final JsonMapper jsonMapper;

	ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		bearerTokenEntryPoint.commence(request, response, authException);

		// One message for missing, malformed, expired and wrongly signed tokens alike. The
		// WWW-Authenticate header already tells a client whether the token was rejected.
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
				"Missing, invalid or expired access token.");
		problem.setInstance(URI.create(request.getRequestURI()));

		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
