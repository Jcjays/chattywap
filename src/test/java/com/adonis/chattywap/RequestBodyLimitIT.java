package com.adonis.chattywap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request body limit, over real HTTP, on a public endpoint: bodies with a declared length above the limit and
 * bodies sent in chunks without one are both refused with 413, before or while they are read, never parsed whole.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RequestBodyLimitIT {

	/** Ten times the default limit: a password of 640 KB. */
	private static final String TOO_LARGE = "{\"email\": \"someone@example.com\", \"password\": \""
			+ "a".repeat(640 * 1024) + "\"}";

	@LocalServerPort
	private int port;

	@Test
	void aBodyDeclaredLargerThanTheLimitIsRefused() throws Exception {
		HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString(TOO_LARGE));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(response.body()).contains("\"maxBytes\":65536").contains("/api/v1/auth/login");
	}

	@Test
	void aChunkedBodyIsCutOffOnceItPassesTheLimit() throws Exception {
		// No declared length: the client streams the body in chunks.
		HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofInputStream(
				() -> new ByteArrayInputStream(TOO_LARGE.getBytes(StandardCharsets.UTF_8))));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.body()).contains("\"maxBytes\":65536");
	}

	@Test
	void aBodyWithinTheLimitIsReadAsBefore() throws Exception {
		HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString(
				"{\"email\": \"nobody@example.com\", \"password\": \"wrong\"}"));

		// Read, validated and answered like any login with unknown credentials.
		assertThat(response.statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
					.header("Content-Type", "application/json")
					.POST(body)
					.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

}
