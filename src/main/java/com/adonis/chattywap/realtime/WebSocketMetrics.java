package com.adonis.chattywap.realtime;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.util.function.ToIntFunction;

/**
 * WebSocket metrics of this instance, from Spring's session statistics and disconnect events:
 * <ul>
 * <li>{@code chattywap.websocket.sessions}: open connections.</li>
 * <li>{@code chattywap.websocket.sessions.closed}, by {@code reason}: connections the server closed because a client
 * didn't take its messages in time or the send buffer overflowed ({@code send-limit-exceeded}, slow clients), sent no
 * CONNECT frame in time ({@code no-connect}), or failed ({@code transport-error}).</li>
 * <li>{@code chattywap.websocket.disconnects}, by {@code close_status}: every connection that ended, by its
 * WebSocket close status. {@code normal} and {@code going-away} are ordinary; {@code no-close-frame} means the
 * connection broke off, {@code policy-violation} that the server closed it (missed heartbeats, rejected frames).</li>
 * </ul>
 * Tags have a fixed, small set of values; session and user IDs belong in the logs.
 */
@Component
class WebSocketMetrics {

	private final MeterRegistry meterRegistry;

	WebSocketMetrics(WebSocketMessageBrokerStats stats, MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		Gauge.builder("chattywap.websocket.sessions", stats, current -> sessionStats(current,
						SubProtocolWebSocketHandler.Stats::getWebSocketSessions))
				.description("Open WebSocket connections on this instance")
				.register(meterRegistry);
		closed(stats, "send-limit-exceeded", SubProtocolWebSocketHandler.Stats::getLimitExceededSessions);
		closed(stats, "no-connect", SubProtocolWebSocketHandler.Stats::getNoMessagesReceivedSessions);
		closed(stats, "transport-error", SubProtocolWebSocketHandler.Stats::getTransportErrorSessions);
	}

	@EventListener
	void onDisconnect(SessionDisconnectEvent event) {
		meterRegistry.counter("chattywap.websocket.disconnects", "close_status", name(event.getCloseStatus()))
				.increment();
	}

	private void closed(WebSocketMessageBrokerStats stats, String reason,
			ToIntFunction<SubProtocolWebSocketHandler.Stats> count) {
		FunctionCounter.builder("chattywap.websocket.sessions.closed", stats, current -> sessionStats(current, count))
				.description("WebSocket connections the server closed because of the client")
				.tag("reason", reason)
				.register(meterRegistry);
	}

	private static double sessionStats(WebSocketMessageBrokerStats stats,
			ToIntFunction<SubProtocolWebSocketHandler.Stats> value) {
		SubProtocolWebSocketHandler.Stats sessionStats = stats.getWebSocketSessionStats();
		return (sessionStats != null) ? value.applyAsInt(sessionStats) : 0;
	}

	private static String name(CloseStatus status) {
		if (status == null) {
			return "unknown";
		}
		return switch (status.getCode()) {
			case 1000 -> "normal";
			case 1001 -> "going-away";
			case 1002 -> "protocol-error";
			case 1003 -> "not-acceptable";
			case 1006 -> "no-close-frame";
			case 1007 -> "bad-data";
			case 1008 -> "policy-violation";
			case 1009 -> "too-big";
			case 1011 -> "server-error";
			case 1012 -> "service-restarted";
			case 4500 -> "session-not-reliable";
			default -> "other";
		};
	}

}
