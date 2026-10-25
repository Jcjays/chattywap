package com.adonis.chattywap.realtime;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.realtime.StompTestClient.Frame;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * STOMP heartbeats, which let the server close connections whose client is gone without closing them, such as a phone
 * that lost its network: only connections that asked for heartbeats get them and must send them. A short interval keeps
 * the test fast, which gives this class its own application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "chattywap.realtime.heartbeat=500ms")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StompHeartbeatIT {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private MeterRegistry meterRegistry;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	@Test
	void aConnectionThatPromisedHeartbeatsAndFellSilentIsClosed() {
		try (StompTestClient client = StompTestClient.open(port)) {
			Frame connected = client.connectWith(Map.of("Authorization", "Bearer " + accessToken(),
					"heart-beat", "500,0"));

			assertThat(connected.command()).isEqualTo(StompCommand.CONNECTED);
			// The server's offer. Agreed: a frame or heartbeat from the client every 500 ms; the server gives up after
			// three intervals without one, with an ERROR frame.
			assertThat(connected.header("heart-beat")).isEqualTo("500,500");
			CloseStatus status = client.awaitClosed();
			assertThat(status.getCode()).isNotEqualTo(CloseStatus.NORMAL.getCode());
			assertThat(client.pending()).extracting(Frame::command).contains(StompCommand.ERROR);
		}
	}

	@Test
	void aConnectionThatAskedForNoHeartbeatsIsNotClosedForBeingSilent() {
		try (StompTestClient client = StompTestClient.open(port)) {
			Frame connected = client.connectWith(Map.of("Authorization", "Bearer " + accessToken(),
					"heart-beat", "0,0"));
			assertThat(connected.command()).isEqualTo(StompCommand.CONNECTED);

			// Four times as long as a heartbeating connection may stay silent.
			await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8)).until(client::isOpen);

			client.subscribeToErrors();
			client.sync();
		}
	}

	@Test
	void connectionsThatEndAreCountedByTheirCloseStatus() {
		double before = disconnects();
		try (StompTestClient client = StompTestClient.connected(port, accessToken())) {
			client.sync();
		}

		await().atMost(Duration.ofSeconds(10)).until(() -> disconnects() > before);
	}

	private double disconnects() {
		return meterRegistry.find("chattywap.websocket.disconnects").counters().stream()
				.mapToDouble(counter -> counter.count()).sum();
	}

	/** Signed by the application's own key, with the claims the application issues. */
	private String accessToken() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(UUID.randomUUID().toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
