package com.adonis.chattywap.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Gives every HTTP request an ID, and logs each request once it is answered.
 * <p>
 * The ID is in the log context ({@link LogContext#REQUEST_ID}) of everything logged while the request is handled, and
 * in the {@value #REQUEST_ID_HEADER} response header, so a client can report it. A proxy in front of the application
 * may pass its own ID in the same request header; it is used if it looks like an ID ({@link LogContext}), otherwise
 * replaced.
 * <p>
 * The request log line names the method, the path (never the query string), the status and the duration, and the
 * caller's user ID once the security filter chain has authenticated it ({@link #USER_ID_ATTRIBUTE}). Server errors
 * and requests slower than {@code chattywap.logging.slow-request-threshold} are logged at {@code WARN}; every other
 * request at {@code DEBUG}, so that the log grows with the problems, not with the traffic. Setting this class's logger
 * to {@code DEBUG} gives an access log of every request.
 * <p>
 * Runs first, before Spring Security, so that rejected requests have an ID too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	/**
	 * The request attribute in which the security filter chain leaves the authenticated caller's user ID. The log
	 * context's own entry ends with the security filter chain, before the request is logged.
	 */
	public static final String USER_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".userId";

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	private final Duration slowRequestThreshold;

	RequestLoggingFilter(@Value("${chattywap.logging.slow-request-threshold:1s}") Duration slowRequestThreshold) {
		this.slowRequestThreshold = slowRequestThreshold;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String proposed = request.getHeader(REQUEST_ID_HEADER);
		String requestId = LogContext.isValidRequestId(proposed) ? proposed : LogContext.newRequestId();
		MDC.put(LogContext.REQUEST_ID, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		long start = System.nanoTime();
		Throwable failure = null;
		try {
			chain.doFilter(request, response);
		}
		catch (IOException | ServletException | RuntimeException ex) {
			failure = ex;
			throw ex;
		}
		finally {
			logRequest(request, response, Duration.ofNanos(System.nanoTime() - start), failure);
			MDC.remove(LogContext.REQUEST_ID);
		}
	}

	private void logRequest(HttpServletRequest request, HttpServletResponse response, Duration duration,
			Throwable failure) {
		// An exception that leaves the filter chain becomes a 500 afterwards, in the container's error handling.
		int status = (failure != null) ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
		boolean slow = duration.compareTo(slowRequestThreshold) >= 0;
		Level level = (status >= 500 || slow) ? Level.WARN : Level.DEBUG;
		if (!log.isEnabledForLevel(level)) {
			return;
		}
		Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
		if (userId != null) {
			MDC.put(LogContext.USER_ID, userId.toString());
		}
		try {
			log.atLevel(level)
					.addKeyValue("method", request.getMethod())
					.addKeyValue("path", request.getRequestURI())
					.addKeyValue("status", status)
					.addKeyValue("durationMs", duration.toMillis())
					.log("{} {} answered {} in {} ms{}", request.getMethod(), request.getRequestURI(), status,
							duration.toMillis(), slow ? " (slow)" : "");
		}
		finally {
			MDC.remove(LogContext.USER_ID);
		}
	}

}
