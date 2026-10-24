package com.adonis.chattywap.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * WebSocket settings, bound from the {@code chattywap.realtime.*} properties in application.yaml.
 *
 * @param allowedOrigins origins of web pages that may open WebSocket connections, besides pages served by this
 *                       application. Patterns such as {@code https://*.example.com} are allowed. Empty by default.
 *                       Clients that send no {@code Origin} header, such as mobile apps, aren't affected.
 */
@ConfigurationProperties("chattywap.realtime")
record RealtimeProperties(List<String> allowedOrigins) {

	RealtimeProperties {
		allowedOrigins = (allowedOrigins != null) ? List.copyOf(allowedOrigins) : List.of();
	}

}
