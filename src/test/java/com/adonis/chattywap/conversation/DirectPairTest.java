package com.adonis.chattywap.conversation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectPairTest {

	private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000001");

	/** Its first bit is set: a negative number for UUID.compareTo, the largest value for PostgreSQL. */
	private static final UUID HIGH = UUID.fromString("f0000000-0000-4000-8000-000000000001");

	@Test
	void ordersUsersAsUnsignedNumbersLikePostgres() {
		assertThat(HIGH.compareTo(LOW)).as("UUID.compareTo gets this order wrong").isNegative();

		DirectPair pair = DirectPair.of(HIGH, LOW);

		assertThat(pair.user1Id()).isEqualTo(LOW);
		assertThat(pair.user2Id()).isEqualTo(HIGH);
	}

	@Test
	void comparesTheSecondHalfWhenTheFirstHalvesAreEqual() {
		UUID first = UUID.fromString("00000000-0000-4000-0000-000000000001");
		UUID second = UUID.fromString("00000000-0000-4000-f000-000000000001");

		assertThat(DirectPair.of(second, first)).isEqualTo(new DirectPair(first, second));
	}

	@Test
	void argumentOrderDoesNotMatter() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();

		assertThat(DirectPair.of(a, b)).isEqualTo(DirectPair.of(b, a));
	}

	@Test
	void rejectsUnorderedAndIdenticalUsers() {
		assertThatThrownBy(() -> new DirectPair(HIGH, LOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DirectPair.of(LOW, LOW)).isInstanceOf(IllegalArgumentException.class);
	}

}
