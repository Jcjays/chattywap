package com.adonis.chattywap.user;

import com.adonis.chattywap.user.dto.UserSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data generates the implementation at startup. Query methods are derived from their names:
 * {@code findByEmail} becomes {@code select ... from users where email = ?}.
 */
interface UserRepository extends JpaRepository<User, UUID> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	/**
	 * Reads only the columns of a {@link UserSummary}, not the email address or password hash.
	 */
	@Query("select new com.adonis.chattywap.user.dto.UserSummary(u.id, u.displayName) from User u where u.id in :ids")
	List<UserSummary> findSummariesByIdIn(Collection<UUID> ids);

}
