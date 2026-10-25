package com.adonis.chattywap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health, readiness and metrics, over real HTTP on both ports: the probes on the application port, everything else on
 * the management port only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Tests replace metrics export with an in-memory registry unless asked not to; this one checks the export.
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class HealthEndpointIT {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private HealthEndpointGroups healthGroups;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	@Test
	void theProbesOnTheApplicationPortArePublicAndAnswerWithAStatusOnly() {
		for (String probe : List.of("/livez", "/readyz")) {
			ResponseEntity<String> response = get(port, probe, null);

			assertThat(response.getStatusCode()).as(probe).isEqualTo(HttpStatus.OK);
			assertThat(json(response)).as(probe).isEqualTo(jsonMapper.valueToTree(Map.of("status", "UP")));
		}
	}

	@Test
	void actuatorEndpointsAreNotServedOnTheApplicationPort() {
		String token = accessToken(UUID.randomUUID());
		for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/actuator/prometheus",
				"/actuator/metrics", "/actuator/env")) {
			// Not public, like every other protected path; and not there for users either.
			assertThat(get(port, path, null).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(get(port, path, token).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	@Test
	void theManagementPortReportsEveryDependencyWithItsDetailsButNoCredentials() {
		ResponseEntity<String> response = get(managementPort, "/actuator/health", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode health = json(response);
		assertThat(health.at("/status").asString()).isEqualTo("UP");
		assertThat(health.at("/components/db/status").asString()).isEqualTo("UP");
		assertThat(health.at("/components/db/details/database").asString()).isEqualTo("PostgreSQL");
		assertThat(health.at("/components/rabbit/status").asString()).isEqualTo("UP");
		assertThat(health.at("/components/rabbit/details/version").isMissingNode()).isFalse();
		assertThat(response.getBody()).doesNotContainIgnoringCase("password").doesNotContain("jdbc:");
	}

	@Test
	void livenessAndReadinessContainOnlyTheApplicationsOwnState() {
		// Their responses don't list their members, so ask the groups themselves.
		HealthEndpointGroup liveness = healthGroups.get("liveness");
		HealthEndpointGroup readiness = healthGroups.get("readiness");

		assertThat(liveness.isMember("livenessState")).isTrue();
		assertThat(readiness.isMember("readinessState")).isTrue();
		for (String dependency : List.of("db", "rabbit", "diskSpace")) {
			assertThat(liveness.isMember(dependency)).as(dependency).isFalse();
			assertThat(readiness.isMember(dependency)).as(dependency).isFalse();
		}
		for (String group : List.of("liveness", "readiness")) {
			assertThat(get(managementPort, "/actuator/health/" + group, null).getStatusCode()).as(group)
					.isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void metricsAreServedForPrometheusOnTheManagementPortWithoutIdentifiersInTheirLabels() {
		// Requests whose paths contain IDs, so that a label taken from a path would show one.
		String token = accessToken(UUID.randomUUID());
		get(port, "/api/v1/conversations/" + UUID.randomUUID() + "/messages", token);
		get(port, "/api/v1/conversations/" + UUID.randomUUID(), token);

		ResponseEntity<String> response = get(managementPort, "/actuator/prometheus", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody())
				.contains("http_server_requests_seconds_bucket")
				.contains("uri=\"/api/v1/conversations/{conversationId}/messages\"")
				.contains("hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_timeout")
				.contains("jvm_memory_used_bytes", "jvm_gc_max_data_size_bytes", "tomcat_threads_busy_threads")
				.contains("executor_active_threads")
				.contains("chattywap_outbox_pending", "chattywap_outbox_oldest_pending_age_seconds")
				.contains("chattywap_websocket_sessions")
				.contains("application=\"chattywap\"")
				.doesNotContainPattern(UUID_PATTERN);
	}

	@Test
	void otherActuatorEndpointsAreNotExposedEvenOnTheManagementPort() {
		// Not served, so refused like any unknown path: 401 without a token.
		for (String path : List.of("/actuator/env", "/actuator/loggers", "/actuator/heapdump", "/actuator/beans")) {
			assertThat(get(managementPort, path, null).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Test
	void everyResponseCarriesARequestIdAndAProxysIdIsKeptIfItLooksLikeOne() {
		String generated = get(port, "/livez", null).getHeaders().getFirst("X-Request-Id");
		String fromProxy = RestClient.create().get().uri("http://localhost:" + port + "/livez")
				.header("X-Request-Id", "lb-7f3a.42")
				.exchange((request, response) -> response.getHeaders().getFirst("X-Request-Id"));
		String notAnId = RestClient.create().get().uri("http://localhost:" + port + "/livez")
				.header("X-Request-Id", "not an id; " + "x".repeat(100))
				.exchange((request, response) -> response.getHeaders().getFirst("X-Request-Id"));

		assertThat(generated).matches(UUID_PATTERN);
		assertThat(fromProxy).isEqualTo("lb-7f3a.42");
		assertThat(notAnId).matches(UUID_PATTERN);
	}

	private static ResponseEntity<String> get(int port, String path, String accessToken) {
		return RestClient.create().get().uri("http://localhost:" + port + path)
				.headers(headers -> {
					if (accessToken != null) {
						headers.setBearerAuth(accessToken);
					}
				})
				.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
						.headers(response.getHeaders())
						.body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private JsonNode json(ResponseEntity<String> response) {
		return jsonMapper.readTree(response.getBody());
	}

	/** Signed by the application's own key, with the claims the application issues. */
	private String accessToken(UUID userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
