package com.adonis.chattywap.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The fan-out of live deliveries between application instances (ARCHITECTURE.md, decision 26). Every instance
 * delivers the messages and receipt changes stored through it to its own WebSocket clients, and publishes them here
 * ({@link RealtimeFanoutPublisher}); every other instance delivers them to its clients ({@link RealtimeFanoutConsumer}).
 * <ul>
 * <li>{@value #EXCHANGE}: durable fanout exchange, which copies every live delivery to every instance's queue.</li>
 * <li>{@value #QUEUE_PREFIX}{@code <instance ID>}: this instance's queue, bound to the exchange. Classic,
 * non-durable, exclusive and auto-delete: it exists while this instance is connected, and RabbitMQ deletes it, with
 * what it holds, when the connection or the consumer goes away. Spring declares it again on every new connection, like
 * the other queues. It holds at most {@value #QUEUE_LENGTH} messages; beyond that, RabbitMQ drops the oldest, so an
 * instance that stopped consuming can't fill the broker's memory.</li>
 * </ul>
 * The instance ID is random, new on every start, so that no two instances ever share a queue.
 */
@Configuration(proxyBeanMethods = false)
class RealtimeFanoutConfig {

	static final String EXCHANGE = "chattywap.realtime";

	static final String QUEUE_PREFIX = "chattywap.realtime.";

	/** Live deliveries an instance's queue holds at most. */
	static final int QUEUE_LENGTH = 10_000;

	static final String CONTAINER_FACTORY = "realtimeFanoutContainerFactory";

	/** Messages the broker sends the consumer ahead of its acknowledgements. */
	private static final int PREFETCH = 250;

	@Bean
	Instance realtimeFanoutInstance() {
		return new Instance(UUID.randomUUID().toString());
	}

	@Bean
	FanoutExchange realtimeFanoutExchange() {
		return ExchangeBuilder.fanoutExchange(EXCHANGE).durable(true).build();
	}

	@Bean
	Queue realtimeFanoutQueue(Instance realtimeFanoutInstance) {
		return QueueBuilder.nonDurable(QUEUE_PREFIX + realtimeFanoutInstance.id())
				.exclusive()
				.autoDelete()
				.maxLength(QUEUE_LENGTH)
				.build();
	}

	@Bean
	Binding realtimeFanoutBinding(Queue realtimeFanoutQueue, FanoutExchange realtimeFanoutExchange) {
		return BindingBuilder.bind(realtimeFanoutQueue).to(realtimeFanoutExchange);
	}

	/**
	 * The consumer's container, not the event consumers' ({@code spring.rabbitmq.listener.simple.*}): one consumer, so
	 * that live deliveries are handled in the order they arrived, and no retries or requeueing, so that none is
	 * delivered twice. Messages are acknowledged after the listener returned, so the broker sends at most
	 * {@value #PREFETCH} ahead and keeps the rest; the listener never fails. Missing queues aren't fatal: after a broker
	 * restart, the container declares its queue again and keeps trying until it can consume, instead of stopping.
	 */
	@Bean(CONTAINER_FACTORY)
	SimpleRabbitListenerContainerFactory realtimeFanoutContainerFactory(ConnectionFactory connectionFactory) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setConcurrentConsumers(1);
		factory.setMaxConcurrentConsumers(1);
		factory.setPrefetchCount(PREFETCH);
		factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
		factory.setDefaultRequeueRejected(false);
		factory.setMissingQueuesFatal(false);
		return factory;
	}

	/**
	 * This application instance, among those sharing RabbitMQ.
	 *
	 * @param id random, for this start of the application
	 */
	record Instance(String id) {
	}

}
