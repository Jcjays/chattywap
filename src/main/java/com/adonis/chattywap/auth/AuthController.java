package com.adonis.chattywap.auth;

import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RefreshTokenRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import com.adonis.chattywap.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints (permitted in {@link SecurityConfig}). Transport only: validate the body,
 * call {@link AuthService}, return its result.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AuthService authService;

	AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return authService.refresh(request.refreshToken());
	}

	/**
	 * Always 204 for a well-formed request, whether the token was active, already revoked, expired or
	 * unknown: in every case the token can't be used afterwards, which is what the client asked for.
	 */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.refreshToken());
	}

}
