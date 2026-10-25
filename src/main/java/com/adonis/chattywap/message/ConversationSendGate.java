package com.adonis.chattywap.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lets at most {@value #SENDS_PER_CONVERSATION} sends per conversation at a time, on this instance, start their
 * transactions.
 * <p>
 * Sends to one conversation run one after the other anyway: each takes the conversation's row lock in PostgreSQL
 * ({@code ConversationService#recordActivity}). But a send waiting for that lock holds a database connection, so a
 * conversation receiving many messages at once would tie up the whole connection pool, and every other request on the
 * instance would wait for a connection. With 50 concurrent senders to one group, all connections waited for that
 * group's lock while unrelated history reads took a hundred times longer (loadtest/README.md). Here the waiting
 * sends wait without a connection.
 * <p>
 * Two at a time, not one: while one send holds the row lock, the next has its connection and waits for the lock, so it
 * goes on the moment the lock is released, as without the gate. With one at a time, taking a connection, starting the
 * transaction and the work after the commit would no longer overlap with the send in front: in the load test, one
 * conversation took 80 messages per second without the gate, 55 with two at a time and 45 with one.
 * <p>
 * This saves this instance's resources; it isn't what keeps sends apart. The row lock still does that, also across
 * instances, each of which lets its sends per conversation through.
 * <p>
 * A conversation has a gate here only while sends to it are running or waiting, so the memory used follows the sends
 * in progress, not the number of conversations. Fair: sends get in in the order they arrived. A send that would wait
 * longer than the time limit fails with {@link ConversationBusyException} instead of waiting on.
 * <p>
 * Callers must not hold a transaction of their own: the gate is released when the send returns, while inside a
 * caller's transaction the row lock is held until that transaction ends.
 */
@Component
class ConversationSendGate {

	static final int SENDS_PER_CONVERSATION = 2;

	private final ConcurrentMap<UUID, Gate> gates = new ConcurrentHashMap<>();

	private final Duration timeout;

	/**
	 * @param timeout how long a send waits for the sends to the same conversation in front of it
	 */
	ConversationSendGate(@Value("${chattywap.messages.send-wait-timeout:5s}") Duration timeout) {
		this.timeout = timeout;
	}

	/**
	 * Runs {@code send} once fewer than {@value #SENDS_PER_CONVERSATION} sends to the same conversation are in front
	 * of it.
	 *
	 * @throws ConversationBusyException if that takes longer than the time limit
	 */
	<T> T run(UUID conversationId, Supplier<T> send) {
		Gate gate = gates.compute(conversationId, (id, existing) -> {
			Gate entered = (existing != null) ? existing : new Gate();
			entered.users++;
			return entered;
		});
		try {
			if (!gate.permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
				throw new ConversationBusyException();
			}
			try {
				return send.get();
			}
			finally {
				gate.permits.release();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ConversationBusyException();
		}
		finally {
			gates.computeIfPresent(conversationId, (id, left) -> (--left.users == 0) ? null : left);
		}
	}

	/** Conversations that currently have a gate: sends to them are running or waiting. For tests. */
	int size() {
		return gates.size();
	}

	/** A conversation's permits, and the sends using them; changed only inside the map's atomic updates. */
	private static final class Gate {

		private final Semaphore permits = new Semaphore(SENDS_PER_CONVERSATION, true);

		private int users;

	}

}
