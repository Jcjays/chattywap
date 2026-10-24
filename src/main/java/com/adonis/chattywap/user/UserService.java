package com.adonis.chattywap.user;

import com.adonis.chattywap.user.dto.UserCredentials;
import com.adonis.chattywap.user.dto.UserResponse;
import com.adonis.chattywap.user.dto.UserSummary;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point of the {@code user} feature. Other features call this service, never the repository.
 * <p>
 * Emails are normalized here, in one place, so that registration, login and the database all agree on them.
 */
@Service
public class UserService {

	private static final String UNIQUE_EMAIL_CONSTRAINT = "users_email_key";

	private final UserRepository userRepository;

	// Constructor injection: Spring passes the dependencies in when it creates the bean.
	// With a single constructor, no @Autowired is needed.
	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * Creates a user. The caller hashes the password; this service never sees the plaintext.
	 *
	 * @throws EmailAlreadyRegisteredException if an account with the same normalized email exists
	 */
	@Transactional
	public UserResponse createUser(String email, String passwordHash, String displayName) {
		String normalizedEmail = normalizeEmail(email);

		// Checking first gives the common case a clean answer without a failed INSERT.
		if (userRepository.existsByEmail(normalizedEmail)) {
			throw new EmailAlreadyRegisteredException();
		}

		// strip(), not trim(): @NotBlank (String.isBlank) and strip() agree on what whitespace is, so a name
		// that passed validation can't become empty here and then violate the database's not-blank check.
		User user = new User(normalizedEmail, passwordHash, displayName.strip());
		try {
			// Flush now so that a constraint violation surfaces here, inside the try block, instead of at commit.
			userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// Two registrations for the same email can both pass the check above. The unique constraint
			// in the database is what guarantees uniqueness; translate its violation into the same error.
			if (ex.getCause() instanceof ConstraintViolationException violation
					&& UNIQUE_EMAIL_CONSTRAINT.equals(violation.getConstraintName())) {
				throw new EmailAlreadyRegisteredException();
			}
			throw ex;
		}
		return toResponse(user);
	}

	@Transactional(readOnly = true)
	public Optional<UserCredentials> findCredentialsByEmail(String email) {
		return userRepository.findByEmail(normalizeEmail(email))
				.map(user -> new UserCredentials(user.getId(), user.getPasswordHash()));
	}

	/**
	 * @throws UserNotFoundException if no user has this ID
	 */
	@Transactional(readOnly = true)
	public UserResponse getUser(UUID userId) {
		return userRepository.findById(userId)
				.map(UserService::toResponse)
				.orElseThrow(UserNotFoundException::new);
	}

	/**
	 * Returns the summaries of those users among {@code userIds} that exist, keyed by user ID. Unknown IDs are
	 * left out, so the caller can tell which users don't exist. One query for all IDs.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, UserSummary> findUserSummaries(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllById(userIds).stream()
				.collect(Collectors.toMap(User::getId, user -> new UserSummary(user.getId(), user.getDisplayName())));
	}

	/**
	 * Trims the address and lower-cases all of it. Strictly, the part before the @ may be case-sensitive,
	 * but in practice mail providers ignore case, and treating "Alice@x.com" and "alice@x.com" as two
	 * accounts causes more problems than it solves.
	 */
	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static UserResponse toResponse(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
	}

}
