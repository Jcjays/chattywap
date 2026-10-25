package com.adonis.chattywap.auth;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.common.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the caller's user ID, the access token's subject, into the log context ({@link LogContext#USER_ID}) for the
 * rest of the request, and leaves it for the request log ({@link RequestLoggingFilter#USER_ID_ATTRIBUTE}). Runs in
 * the security filter chain right after the access token was validated ({@link SecurityConfig}). Never the token
 * itself.
 */
class CallerLogContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken)) {
			chain.doFilter(request, response);
			return;
		}
		String userId = authentication.getName();
		request.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, userId);
		MDC.put(LogContext.USER_ID, userId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(LogContext.USER_ID);
		}
	}

}
