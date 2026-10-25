package com.adonis.chattywap.common;

import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUnavailableTest {

	@Test
	void failuresToGetOrKeepAConnectionAreUnavailability() {
		// No connection from the pool in time.
		assertThat(DatabaseUnavailable.isCause(new CannotCreateTransactionException("Could not open JPA EntityManager",
				new SQLTransientConnectionException("chattywap - Connection is not available, request timed out"))))
				.isTrue();
		assertThat(DatabaseUnavailable.isCause(new CannotGetJdbcConnectionException("no connection"))).isTrue();
		// A connection that broke or timed out while in use (PostgreSQL JDBC: 08006, 08001, ...).
		assertThat(DatabaseUnavailable.isCause(new RuntimeException(
				new SQLException("An I/O error occurred while sending to the backend.", "08006")))).isTrue();
		// The server shutting down.
		assertThat(DatabaseUnavailable.isCause(new SQLException("terminating connection", "57P01"))).isTrue();
		// The rollback after such a failure, on the connection the pool has closed meanwhile.
		assertThat(DatabaseUnavailable.isCause(new JpaSystemException(new TransactionException(
				"Unable to rollback against JDBC Connection", new SQLException("Connection is closed"))))).isTrue();
	}

	@Test
	void otherDatabaseFailuresAreNot() {
		// What a trigger or a constraint raises: a bug or bad data, the same on every attempt.
		assertThat(DatabaseUnavailable.isCause(new JpaSystemException(new TransactionException(
				"Unable to commit against JDBC Connection", new SQLException("simulated failure", "P0001"))))).isFalse();
		assertThat(DatabaseUnavailable.isCause(new DataIntegrityViolationException("duplicate key",
				new SQLException("duplicate key value", "23505")))).isFalse();
		assertThat(DatabaseUnavailable.isCause(new CannotAcquireLockException("deadlock",
				new SQLException("deadlock detected", "40P01")))).isFalse();
		assertThat(DatabaseUnavailable.isCause(new IllegalStateException("a bug"))).isFalse();
	}

}
