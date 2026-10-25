package com.adonis.chattywap.common;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Failures that mean PostgreSQL can't be used at the moment, rather than a bug: no connection could be obtained in
 * time (the pool's {@code connection-timeout}, a database that is down or unreachable), or a connection broke or
 * timed out while it was in use. The request's transaction didn't commit; its changes rolled back or never started.
 * <p>
 * Clients get {@code 503 Service Unavailable}, which tells them that the same request may succeed later, instead of
 * the {@code 500} of an unexpected error. Both REST ({@code GlobalExceptionHandler}) and STOMP answer this way.
 */
public final class DatabaseUnavailable {

	/** SQLSTATE class 08: connection exceptions. */
	private static final String CONNECTION_EXCEPTION = "08";

	/** SQLSTATE 57P01 to 57P03: the server is shutting down, crashed, or doesn't accept connections yet. */
	private static final String OPERATOR_INTERVENTION = "57P0";

	/**
	 * What HikariCP's connection throws, without an SQLSTATE, once the pool has closed it. The pool closes a connection
	 * as soon as a statement on it fails with a connection exception, so the rollback that follows fails with this,
	 * and that failure, not the original one, is what reaches the caller.
	 */
	private static final String CLOSED_BY_POOL = "Connection is closed";

	private DatabaseUnavailable() {
	}

	/**
	 * Whether {@code failure}, or any of its causes, is such a failure.
	 */
	public static boolean isCause(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = (cause.getCause() == cause) ? null : cause.getCause()) {
			if (cause instanceof CannotCreateTransactionException || cause instanceof DataAccessResourceFailureException
					|| cause instanceof SQLTransientConnectionException) {
				return true;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().startsWith(CONNECTION_EXCEPTION)
							|| sql.getSQLState().startsWith(OPERATOR_INTERVENTION))) {
				return true;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() == null
					&& CLOSED_BY_POOL.equals(sql.getMessage())) {
				return true;
			}
		}
		return false;
	}

	/** The innermost cause, for the log: its type and message say why, without a stack trace per failed request. */
	public static String describe(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getName() + ": " + cause.getMessage();
	}

	public static ProblemDetail problem() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The service is temporarily unavailable. Try again later.");
	}

}
