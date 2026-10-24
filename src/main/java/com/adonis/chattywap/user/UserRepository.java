package com.adonis.chattywap.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data generates the implementation at startup. Query methods are derived from their names:
 * {@code findByEmail} becomes {@code select ... from users where email = ?}.
 */
interface UserRepository extends JpaRepository<User, UUID> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

}
