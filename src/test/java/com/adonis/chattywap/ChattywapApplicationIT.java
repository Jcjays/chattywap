package com.adonis.chattywap;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChattywapApplicationIT {

	@Test
	void contextLoads() {
	}

	@Test
	void flywayInitializesTheDatabase(@Autowired JdbcTemplate jdbcTemplate) {
		Boolean schemaHistoryExists = jdbcTemplate.queryForObject(
				"select to_regclass('flyway_schema_history') is not null", Boolean.class);

		assertThat(schemaHistoryExists).isTrue();
	}

	@Test
	void hibernateValidatesTheSchemaInsteadOfGeneratingIt(@Autowired EntityManagerFactory entityManagerFactory) {
		assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
	}

}
