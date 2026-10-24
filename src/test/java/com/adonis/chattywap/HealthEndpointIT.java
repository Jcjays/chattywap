package com.adonis.chattywap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class HealthEndpointIT {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void healthIsPublicAndReportsDatabaseAndBrokerWithoutDetails() {
		MvcTestResult result = mvc.get().uri("/actuator/health").exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
		assertThat(result).bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
		assertThat(result).bodyJson().extractingPath("$.components.rabbit.status").isEqualTo("UP");
		assertThat(result).bodyJson().doesNotHavePath("$.components.db.details");
		assertThat(result).bodyJson().doesNotHavePath("$.components.rabbit.details");
	}

	@Test
	@WithMockUser
	void environmentEndpointIsNotExposed() {
		assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
	}

}
