package com.adonis.chattywap.conversation;

import java.util.UUID;

/**
 * The two participants of a direct conversation, in the order the database stores them
 * ({@code direct_user1_id < direct_user2_id}). Fixing the order makes "Alice and Bob" and "Bob and Alice"
 * the same pair, so the unique constraint on the two columns allows one direct conversation per pair.
 * <p>
 * PostgreSQL compares UUIDs byte by byte, as unsigned numbers. {@link UUID#compareTo} compares the two halves
 * as signed longs and disagrees for UUIDs whose first bit is set, so it can't be used here.
 */
record DirectPair(UUID user1Id, UUID user2Id) {

	DirectPair {
		if (compareUnsigned(user1Id, user2Id) >= 0) {
			throw new IllegalArgumentException("user1Id must be less than user2Id");
		}
	}

	static DirectPair of(UUID userA, UUID userB) {
		return compareUnsigned(userA, userB) < 0 ? new DirectPair(userA, userB) : new DirectPair(userB, userA);
	}

	private static int compareUnsigned(UUID a, UUID b) {
		int result = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return result != 0 ? result : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	}

}
