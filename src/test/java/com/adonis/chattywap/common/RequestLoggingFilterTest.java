package com.adonis.chattywap.common;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

	private final RequestLoggingFilter filter = new RequestLoggingFilter(Duration.ofSeconds(1));

	@Test
	void theRequestIdIsInTheLogContextWhileTheRequestIsHandledAndInTheResponse() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> duringRequest = new AtomicReference<>();

		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"), response,
				(request, ignored) -> duringRequest.set(MDC.get(LogContext.REQUEST_ID)));

		assertThat(duringRequest.get()).isNotBlank();
		assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo(duringRequest.get());
		assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
	}

	@Test
	void aProxysRequestIdIsKeptOnlyIfItLooksLikeAnId() throws Exception {
		assertThat(requestIdFor("lb-1234_abc.5:6")).isEqualTo("lb-1234_abc.5:6");
		for (String forged : new String[] {"two words", "line\nbreak", "{json}", "x".repeat(65), ""}) {
			assertThat(requestIdFor(forged)).as(forged).isNotEqualTo(forged).matches("[0-9a-f-]{36}");
		}
	}

	@Test
	void successfulRequestsAreLoggedAtDebugAndServerErrorsAtWarnWithTheCaller(CapturedOutput output)
			throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/conversations/c/messages");
		request.setQueryString("cursor=secret-looking-value");
		request.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, "user-1");

		filter.doFilter(request, new MockHttpServletResponse(), (ignored, response) ->
				((MockHttpServletResponse) response).setStatus(503));

		assertThat(output).contains("WARN").contains("POST /api/v1/conversations/c/messages answered 503 in")
				.doesNotContain("secret-looking-value");
		assertThat(MDC.get(LogContext.USER_ID)).isNull();
	}

	@Test
	void slowRequestsAreLoggedAtWarn(CapturedOutput output) throws Exception {
		RequestLoggingFilter impatient = new RequestLoggingFilter(Duration.ZERO);

		impatient.doFilter(new MockHttpServletRequest("GET", "/api/v1/conversations"), new MockHttpServletResponse(),
				(request, response) -> {
				});

		assertThat(output).contains("WARN").contains("GET /api/v1/conversations answered 200 in").contains("(slow)");
	}

	@Test
	void aRequestThatFailsWithAnExceptionIsLoggedAsAServerErrorAndTheExceptionPassesOn(CapturedOutput output) {
		assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/boom"),
				new MockHttpServletResponse(), (request, response) -> {
					throw new ServletException("boom");
				})).isInstanceOf(ServletException.class);

		assertThat(output).contains("GET /boom answered 500 in");
		assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
	}

	private String requestIdFor(String proposed) throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/livez");
		request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, proposed);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (ignored, alsoIgnored) -> {
		});
		return response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
	}

}
