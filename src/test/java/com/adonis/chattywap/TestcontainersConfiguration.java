package com.adonis.chattywap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL and RabbitMQ containers for integration tests. Image versions match compose.yaml.
 * Public so that integration tests in the feature packages can import it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return postgres();
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return rabbitMq();
	}

	/**
	 * A PostgreSQL container, not started yet: for tests that start applications themselves, such as several
	 * instances sharing one database.
	 */
	public static PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
	}

	/** A RabbitMQ container, not started yet. */
	public static RabbitMQContainer rabbitMq() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.7-management"));
	}

}
