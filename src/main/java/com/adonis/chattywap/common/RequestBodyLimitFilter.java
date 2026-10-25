package com.adonis.chattywap.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Refuses request bodies larger than {@code chattywap.http.max-request-body-size} with {@code 413 Content Too Large}.
 * <p>
 * Nothing else limits a JSON body: Tomcat's limits apply to form posts only, and the request DTOs' size constraints
 * are checked after the whole body has been read. Measured before: eight concurrent 50 MB login requests, which need
 * no access token, ran the server out of heap. The largest body any endpoint accepts is a message of 4,000 characters,
 * about 24 KB even with every character escaped in JSON; the default limit leaves room for that.
 * <p>
 * A declared {@code Content-Length} above the limit is refused before anything is read. A body without one (chunked)
 * is cut off once it passes the limit: reading it fails with {@link RequestBodyTooLargeException}, which
 * {@code GlobalExceptionHandler} answers with the same 413. Runs before Spring Security, so it protects the public
 * endpoints too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestBodyLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;

	private final JsonMapper jsonMapper;

	RequestBodyLimitFilter(@Value("${chattywap.http.max-request-body-size:64KB}") DataSize maxSize,
			JsonMapper jsonMapper) {
		this.maxBytes = maxSize.toBytes();
		this.jsonMapper = jsonMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			ProblemDetail problem = RequestBodyTooLargeException.problem(maxBytes);
			problem.setInstance(URI.create(request.getRequestURI()));
			jsonMapper.writeValue(response.getOutputStream(), problem);
			return;
		}
		chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
	}

	/** The request, with a body that can't be read past the limit. */
	private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

		private final long maxBytes;

		private ServletInputStream body;

		LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (body == null) {
				body = new LimitedInputStream(super.getInputStream(), maxBytes);
			}
			return body;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream delegate;

		private final long maxBytes;

		private long read;

		LimitedInputStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int value = delegate.read();
			if (value >= 0) {
				count(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int count = delegate.read(buffer, offset, length);
			if (count > 0) {
				count(count);
			}
			return count;
		}

		private void count(int bytes) throws RequestBodyTooLargeException {
			read += bytes;
			if (read > maxBytes) {
				throw new RequestBodyTooLargeException(maxBytes);
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			delegate.setReadListener(listener);
		}

	}

}
