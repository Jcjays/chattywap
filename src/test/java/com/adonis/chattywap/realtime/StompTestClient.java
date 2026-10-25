package com.adonis.chattywap.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A STOMP client over a plain WebSocket, for tests. It sends frames exactly as written, including ones a client library
 * wouldn't send (no CONNECT, unknown destinations, forged headers), and keeps every frame the server sends, in order.
 * <p>
 * Waiting is never done by sleeping: {@link #next()} waits for the next frame, and {@link #sync()} waits until the
 * server has processed every frame sent before it.
 */
final class StompTestClient implements AutoCloseable {

	static final String ERRORS = "/user/queue/errors";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String ERRORS_SUBSCRIPTION = "errors";

	private final WebSocketSession session;

	private final BlockingQueue<Frame> frames;

	private final CompletableFuture<CloseStatus> closed;

	private StompTestClient(WebSocketSession session, BlockingQueue<Frame> frames, CompletableFuture<CloseStatus> closed) {
		this.session = session;
		this.frames = frames;
		this.closed = closed;
	}

	/** Opens a WebSocket connection to the STOMP endpoint. No STOMP frame is sent yet. */
	static StompTestClient open(int port) {
		return open(port, new WebSocketHttpHeaders());
	}

	/** Opens a WebSocket connection with these headers on the handshake request. */
	static StompTestClient open(int port, WebSocketHttpHeaders handshakeHeaders) {
		BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
		CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
		StompDecoder decoder = new StompDecoder();
		TextWebSocketHandler handler = new TextWebSocketHandler() {

			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				for (Message<byte[]> frame : decoder.decode(ByteBuffer.wrap(message.asBytes()))) {
					Frame decoded = Frame.of(frame);
					if (decoded.command() != null) {
						frames.add(decoded);
					}
				}
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
				closed.complete(status);
			}

		};
		try {
			WebSocketSession session = new StandardWebSocketClient()
					.execute(handler, handshakeHeaders, URI.create("ws://localhost:" + port + "/ws"))
					.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			return new StompTestClient(session, frames, closed);
		}
		catch (Exception ex) {
			throw new IllegalStateException("WebSocket handshake failed", ex);
		}
	}

	/**
	 * Opens a connection, connects with the access token and subscribes to the connection's error queue, which
	 * {@link #sync()} needs.
	 */
	static StompTestClient connected(int port, String accessToken) {
		StompTestClient client = open(port);
		Frame connected = client.connect(accessToken);
		assertThat(connected.command()).as("reply to CONNECT: %s", connected).isEqualTo(StompCommand.CONNECTED);
		client.subscribeToErrors();
		client.sync();
		return client;
	}

	/** Subscribes to the connection's error queue, which {@link #nextError()} and {@link #sync()} read. */
	void subscribeToErrors() {
		subscribe(ERRORS_SUBSCRIPTION, ERRORS);
	}

	/** Sends CONNECT with {@code Authorization: Bearer <accessToken>} and returns the server's reply. */
	Frame connect(String accessToken) {
		return connectWith(Map.of("Authorization", "Bearer " + accessToken));
	}

	/** Sends CONNECT with these headers, besides the required ones, and returns the server's reply. */
	Frame connectWith(Map<String, String> headers) {
		Map<String, String> all = new LinkedHashMap<>();
		all.put("accept-version", "1.2");
		all.put("host", "localhost");
		all.putAll(headers);
		send("CONNECT", all, "");
		return next();
	}

	void subscribe(String id, String destination) {
		send("SUBSCRIBE", Map.of("id", id, "destination", destination), "");
	}

	/** Sends a JSON payload, the way client libraries send messages. */
	void sendJson(String destination, String json) {
		sendJson(destination, json, Map.of());
	}

	void sendJson(String destination, String json, Map<String, String> extraHeaders) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("destination", destination);
		headers.put("content-type", "application/json");
		headers.putAll(extraHeaders);
		send("SEND", headers, json);
	}

	/**
	 * Sends a frame as written. Header values must not contain {@code :}, line breaks or backslashes, which STOMP
	 * would need escaped.
	 */
	void send(String command, Map<String, String> headers, String body) {
		StringBuilder frame = new StringBuilder(command).append('\n');
		headers.forEach((name, value) -> frame.append(name).append(':').append(value).append('\n'));
		frame.append('\n').append(body).append('\0');
		try {
			session.sendMessage(new TextMessage(frame.toString()));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Sending a frame failed", ex);
		}
	}

	/** The next frame from the server, waiting for it if necessary. */
	Frame next() {
		try {
			Frame frame = frames.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (frame == null) {
				fail("No frame from the server within " + TIMEOUT);
			}
			return frame;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** The next frame, which must be a MESSAGE on the subscription with this ID. */
	Frame nextMessage(String subscriptionId) {
		Frame frame = next();
		assertThat(frame.command()).as("frame: %s", frame).isEqualTo(StompCommand.MESSAGE);
		assertThat(frame.header("subscription")).as("frame: %s", frame).isEqualTo(subscriptionId);
		return frame;
	}

	/** The next frame, which must be a problem detail on the connection's error queue. */
	Frame nextError() {
		return nextMessage(ERRORS_SUBSCRIPTION);
	}

	/**
	 * Waits until the server has processed every frame this client sent before, without anything else arriving
	 * meanwhile. The server processes a connection's frames one after the other, so it answers a deliberately invalid
	 * message only after all earlier frames, subscriptions included, are done.
	 */
	void sync() {
		String destination = "/app/conversations/" + UUID.randomUUID() + "/messages";
		sendJson(destination, "{\"content\": \"\"}");
		Frame error = next();
		assertThat(error.command()).as("only the reply to sync() was expected, got %s", error)
				.isEqualTo(StompCommand.MESSAGE);
		assertThat(error.header("subscription")).as("only the reply to sync() was expected, got %s", error)
				.isEqualTo(ERRORS_SUBSCRIPTION);
		assertThat(error.body()).as("only the reply to sync() was expected").contains(destination);
	}

	/** Waits for the server to close the connection. */
	CloseStatus awaitClosed() {
		try {
			return closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new AssertionError("The server didn't close the connection within " + TIMEOUT, ex);
		}
	}

	/** Whether the connection is still open: the server hasn't closed it. */
	boolean isOpen() {
		return !closed.isDone();
	}

	/** Frames received so far and not yet taken with {@link #next()}. */
	List<Frame> pending() {
		List<Frame> pending = new ArrayList<>();
		frames.drainTo(pending);
		return pending;
	}

	@Override
	public void close() {
		try {
			session.close();
		}
		catch (IOException ex) {
			// Already closed.
		}
	}

	/**
	 * A frame from the server.
	 *
	 * @param headers its STOMP headers, the first value of each
	 */
	record Frame(StompCommand command, Map<String, String> headers, String body) {

		static Frame of(Message<byte[]> message) {
			StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
			Map<String, String> headers = new HashMap<>();
			accessor.toNativeHeaderMap().forEach((name, values) -> headers.put(name, values.getFirst()));
			return new Frame(accessor.getCommand(), headers, new String(message.getPayload(), StandardCharsets.UTF_8));
		}

		String header(String name) {
			return headers.get(name);
		}

	}

}
