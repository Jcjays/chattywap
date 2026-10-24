package com.adonis.chattywap.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Two threads sending to one connection, with the first one held in the middle of writing, the way a thread can be
 * when the server is busy.
 */
class FlushBeforeCloseSessionDecoratorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final Connection connection = new Connection();

	private final FlushBeforeCloseSessionDecorator session =
			new FlushBeforeCloseSessionDecorator(connection.session(), (int) TIMEOUT.toMillis(), 512 * 1024);

	@AfterEach
	void finishWriting() {
		connection.finishWriting();
	}

	@Test
	void anErrorFrameSentWhileAnotherMessageIsBeingWrittenReachesTheClientBeforeTheConnectionCloses()
			throws InterruptedException {
		Thread writing = start(() -> session.sendMessage(new TextMessage("MESSAGE\n\n\0")));
		connection.awaitWriting();

		// Spring's STOMP handler sends the ERROR frame and closes the connection right after.
		StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
		error.setMessage("Unknown destination.");
		error.setLeaveMutable(true);
		Thread rejecting = start(() -> new StompSubProtocolHandler().handleMessageToClient(session,
				MessageBuilder.createMessage("{}".getBytes(StandardCharsets.UTF_8), error.getMessageHeaders())));
		awaitWaitingOrDone(rejecting);
		connection.finishWriting();
		writing.join(TIMEOUT.toMillis());
		rejecting.join(TIMEOUT.toMillis());

		assertThat(connection.events()).containsExactly("MESSAGE", "ERROR", "closed " + CloseStatus.PROTOCOL_ERROR);
	}

	@Test
	void aConnectionThatIsNotReliableIsClosedWithoutWaitingForWrites() throws Exception {
		start(() -> session.sendMessage(new TextMessage("MESSAGE\n\n\0")));
		connection.awaitWriting();

		// Waiting would last until the send time limit.
		assertTimeout(TIMEOUT.dividedBy(2), () -> session.close(CloseStatus.SESSION_NOT_RELIABLE));

		assertThat(connection.events()).containsExactly("closed " + CloseStatus.SESSION_NOT_RELIABLE);
	}

	private interface Action {

		void run() throws Exception;

	}

	private static Thread start(Action action) {
		Thread thread = new Thread(() -> {
			try {
				action.run();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		thread.start();
		return thread;
	}

	/** Until the thread has finished, or is waiting with a timeout, as when it waits to close the connection. */
	private static void awaitWaitingOrDone(Thread thread) {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (thread.getState() != Thread.State.TERMINATED && thread.getState() != Thread.State.TIMED_WAITING) {
			if (Instant.now().isAfter(deadline)) {
				fail("%s is still %s", thread, thread.getState());
			}
			Thread.onSpinWait();
		}
	}

	/**
	 * The client's end of a WebSocket connection. Records the command of every frame written to it and its closing,
	 * in order. Writing doesn't finish until {@link #finishWriting()}.
	 */
	private static final class Connection {

		private final List<String> events = new CopyOnWriteArrayList<>();

		private final CountDownLatch writing = new CountDownLatch(1);

		private final CountDownLatch finishWriting = new CountDownLatch(1);

		WebSocketSession session() {
			return (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
						case "sendMessage" -> {
							String frame = ((WebSocketMessage<?>) args[0]).getPayload().toString();
							writing.countDown();
							if (!finishWriting.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
								throw new IllegalStateException("The test never finished writing");
							}
							events.add(frame.substring(0, frame.indexOf('\n')));
							yield null;
						}
						case "close" -> {
							events.add("closed " + args[0]);
							yield null;
						}
						case "getId" -> "session-1";
						case "isOpen" -> true;
						case "toString" -> "connection";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> throw new UnsupportedOperationException(method.getName());
					});
		}

		void awaitWriting() throws InterruptedException {
			assertThat(writing.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("writing started").isTrue();
		}

		void finishWriting() {
			finishWriting.countDown();
		}

		List<String> events() {
			return events;
		}

	}

}
