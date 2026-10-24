package com.adonis.chattywap.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, UUID> {

	/**
	 * Finds the family of the token with this hash and locks the family row until the transaction ends.
	 * <p>
	 * {@code PESSIMISTIC_WRITE} adds a row lock ({@code SELECT ... FOR UPDATE} in PostgreSQL). Another
	 * transaction asking for the same lock waits until this one commits or rolls back, and then reads the
	 * committed state. So two refresh requests for the same session run one after the other.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select f from RefreshTokenFamily f
			where f.id = (select t.familyId from RefreshToken t where t.tokenHash = :tokenHash)
			""")
	Optional<RefreshTokenFamily> findByTokenHashForUpdate(String tokenHash);

}
