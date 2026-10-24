package com.adonis.chattywap.user;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/users/me and how Spring Security treats the access token on a protected endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserControllerIT {

	private static final String PASSWORD = "correct horse battery staple";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JsonMapper jsonMapper;

	/** The application's own encoder: tokens it signs carry a valid signature. */
	@Autowired
	private JwtEncoder jwtEncoder;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	@Test
	void meReturnsTheProfileOfTheTokensUser() throws Exception {
		String email = uniqueEmail();
		String userId = register(email);
		TokenResponse tokens = login(email);

		MvcTestResult result = getMe("Bearer " + tokens.accessToken());

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(userId);
		assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
		assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Alice");
		assertThat(result).bodyJson().doesNotHavePath("$.passwordHash");
	}

	@Test
	void meIgnoresAUserIdSuppliedByTheClient() throws Exception {
		String email = uniqueEmail();
		String userId = register(email);
		String otherUserId = register(uniqueEmail());
		TokenResponse tokens = login(email);

		MvcTestResult result = mvc.get().uri("/api/v1/users/me?id={id}&userId={id}", otherUserId, otherUserId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
				.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(userId);
	}

	@Test
	void meWithoutTokenIsUnauthorized() {
		MvcTestResult result = mvc.get().uri("/api/v1/users/me").exchange();

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		// Spring Security 7 also adds resource_metadata="<url>" (RFC 9728), so check only the scheme.
		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Missing, invalid or expired access token.");
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/users/me");
	}

	@Test
	void meWithMalformedTokenIsUnauthorized() {
		MvcTestResult result = getMe("Bearer not-a-jwt");

		assertUnauthorizedBecauseOfInvalidToken(result);
	}

	@Test
	void meWithTokenSignedByAnotherKeyIsUnauthorized() {
		byte[] otherKey = new byte[32];
		new SecureRandom().nextBytes(otherKey);
		JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build();

		MvcTestResult result = getMe("Bearer " + token(otherEncoder, UUID.randomUUID().toString(), claims -> {
		}));

		assertUnauthorizedBecauseOfInvalidToken(result);
	}

	@Test
	void meWithExpiredTokenIsUnauthorized() {
		String userId = register(uniqueEmail());
		Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));

		MvcTestResult result = getMe("Bearer " + token(jwtEncoder, userId,
				claims -> claims.issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15)))));

		assertUnauthorizedBecauseOfInvalidToken(result);
	}

	@Test
	void meWithTokenForAnotherAudienceIsUnauthorized() {
		String userId = register(uniqueEmail());

		MvcTestResult result = getMe("Bearer " + token(jwtEncoder, userId,
				claims -> claims.audience(List.of("some-other-api"))));

		assertUnauthorizedBecauseOfInvalidToken(result);
	}

	@Test
	void meWithTokenFromAnotherIssuerIsUnauthorized() {
		String userId = register(uniqueEmail());

		MvcTestResult result = getMe("Bearer " + token(jwtEncoder, userId, claims -> claims.issuer("someone-else")));

		assertUnauthorizedBecauseOfInvalidToken(result);
	}

	@Test
	void meWithValidTokenBuiltByTheTestHelperIsAccepted() {
		// Guards the tests above: their tokens are rejected for the one claim they change.
		String userId = register(uniqueEmail());

		assertThat(getMe("Bearer " + token(jwtEncoder, userId, claims -> {
		}))).hasStatusOk();
	}

	private void assertUnauthorizedBecauseOfInvalidToken(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).headers().containsHeader(HttpHeaders.WWW_AUTHENTICATE);
		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Missing, invalid or expired access token.");
	}

	private String token(JwtEncoder encoder, String subject, Consumer<JwtClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(subject)
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)));
		customizer.accept(claims);
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private MvcTestResult getMe(String authorizationHeader) {
		return mvc.get().uri("/api/v1/users/me")
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader)
				.exchange();
	}

	/** Returns the new user's ID. */
	private String register(String email) {
		MvcTestResult result = post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice"));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return jsonMapper.readTree(body(result)).get("id").asString();
	}

	private TokenResponse login(String email) {
		MvcTestResult result = post("/api/v1/auth/login", new LoginRequest(email, PASSWORD));
		assertThat(result).hasStatusOk();
		return jsonMapper.readValue(body(result), TokenResponse.class);
	}

	private MvcTestResult post(String uri, Object body) {
		return mvc.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(body))
				.exchange();
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

}
