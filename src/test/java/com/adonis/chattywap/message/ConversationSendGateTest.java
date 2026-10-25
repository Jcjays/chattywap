package com.adonis.chattywap.message;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationSendGateTest {

	private final ConversationSendGate gate = new ConversationSendGate(Duration.ofMillis(300));

	@Test
	void atMostTwoSendsToOneConversationRunAtOnce() throws Exception {
		UUID conversation = UUID.randomUUID();
		AtomicInteger running = new AtomicInteger();
		AtomicInteger mostAtOnce = new AtomicInteger();

		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (int i = 0; i < 40; i++) {
				executor.submit(() -> gate.run(conversation, () -> {
					mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
					sleep(2);
					running.decrementAndGet();
					return null;
				}));
			}
		}

		assertThat(mostAtOnce.get()).isBetween(1, ConversationSendGate.SENDS_PER_CONVERSATION);
		assertThat(gate.size()).isZero();
	}

	@Test
	void sendsToOtherConversationsDoNotWait() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch leave = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> holding = executor.submit(() -> gate.run(UUID.randomUUID(), () -> {
				inside.countDown();
				await(leave);
				return null;
			}));
			await(inside);

			assertThat(gate.run(UUID.randomUUID(), () -> "sent")).isEqualTo("sent");

			leave.countDown();
			holding.get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void aSendThatWouldWaitLongerThanTheTimeLimitFailsWithoutRunning() throws Exception {
		UUID conversation = UUID.randomUUID();
		CountDownLatch inside = new CountDownLatch(ConversationSendGate.SENDS_PER_CONVERSATION);
		CountDownLatch leave = new CountDownLatch(1);
		AtomicInteger ran = new AtomicInteger();
		try (ExecutorService executor = Executors.newFixedThreadPool(ConversationSendGate.SENDS_PER_CONVERSATION)) {
			// As many sends in the gate as it lets in, all of them taking long.
			List<Future<?>> holding = new ArrayList<>();
			for (int i = 0; i < ConversationSendGate.SENDS_PER_CONVERSATION; i++) {
				holding.add(executor.submit(() -> gate.run(conversation, () -> {
					inside.countDown();
					await(leave);
					return null;
				})));
			}
			await(inside);

			assertThatThrownBy(() -> gate.run(conversation, ran::incrementAndGet))
					.isInstanceOf(ConversationBusyException.class);
			assertThat(ran).hasValue(0);

			leave.countDown();
			for (Future<?> send : holding) {
				send.get(5, TimeUnit.SECONDS);
			}
		}
		// The gate is gone with the last send, also after a timeout.
		assertThat(gate.size()).isZero();
	}

	@Test
	void aFailedSendReleasesTheGate() {
		UUID conversation = UUID.randomUUID();

		assertThatThrownBy(() -> gate.run(conversation, () -> {
			throw new IllegalStateException("rolled back");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(gate.run(conversation, () -> "sent")).isEqualTo("sent");
		assertThat(gate.size()).isZero();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
