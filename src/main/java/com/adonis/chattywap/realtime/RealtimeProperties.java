package com.adonis.chattywap.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * WebSocket settings, bound from the {@code chattywap.realtime.*} properties in application.yaml.
 *
 * @param allowedOrigins origins of web pages that may open WebSocket connections, besides pages served by this
 *                       application. Patterns such as {@code https://*.example.com} are allowed. Empty by default.
 *                       Clients that send no {@code Origin} header, such as mobile apps, aren't affected.
 * @param heartbeat      the STOMP heartbeat interval the server offers in both directions; 10 seconds if not set,
 *                       zero for none. Only connections whose CONNECT frame asks for heartbeats get them, and only
 *                       those are expected to send them: the server closes such a connection once it has received
 *                       nothing for three intervals of the agreed rate, so that abandoned connections don't stay open.
 */
@ConfigurationProperties("chattywap.realtime")
record RealtimeProperties(List<String> allowedOrigins, Duration heartbeat) {

	RealtimeProperties {
		allowedOrigins = (allowedOrigins != null) ? List.copyOf(allowedOrigins) : List.of();
		heartbeat = (heartbeat != null) ? heartbeat : Duration.ofSeconds(10);
	}

}
