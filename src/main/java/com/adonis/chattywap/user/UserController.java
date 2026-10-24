package com.adonis.chattywap.user;

import com.adonis.chattywap.user.dto.UserResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

	private final UserService userService;

	UserController(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Spring MVC passes in the {@link Authentication} that Spring Security stored for this request after
	 * validating the access token. Its name is the token's subject, which is the user ID. The ID therefore
	 * comes from a token the server signed, never from a value the client chose.
	 */
	@GetMapping("/me")
	UserResponse me(Authentication authentication) {
		UUID userId = UUID.fromString(authentication.getName());
		return userService.getUser(userId);
	}

}
