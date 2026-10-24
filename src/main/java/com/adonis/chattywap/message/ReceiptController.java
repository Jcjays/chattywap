package com.adonis.chattywap.message;

import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import com.adonis.chattywap.message.dto.ReceiptPositionsResponse;
import com.adonis.chattywap.message.dto.ReceiptRequest;
import com.adonis.chattywap.message.dto.UnreadCountResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Delivery and read receipts and the unread count of a conversation. They require an access token, like every
 * endpoint except authentication and health. Transport only: the member who acknowledges is always the caller from
 * the token, and {@link ReceiptService} decides what they may do.
 * <p>
 * Acknowledging is idempotent: repeating a request, or sending one for a message before the current position, changes
 * nothing and answers {@code 200} with the position as it is.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
class ReceiptController {

	private final ReceiptService receiptService;

	ReceiptController(ReceiptService receiptService) {
		this.receiptService = receiptService;
	}

	@GetMapping("/receipts")
	ReceiptPositionsResponse list(@PathVariable UUID conversationId, Authentication authentication) {
		return receiptService.listReceiptPositions(currentUserId(authentication), conversationId);
	}

	@PostMapping("/receipts/delivered")
	ReceiptPositionResponse delivered(@PathVariable UUID conversationId, @Valid @RequestBody ReceiptRequest request,
			Authentication authentication) {
		return receiptService.acknowledgeDelivery(currentUserId(authentication), conversationId, request.messageId());
	}

	@PostMapping("/receipts/read")
	ReceiptPositionResponse read(@PathVariable UUID conversationId, @Valid @RequestBody ReceiptRequest request,
			Authentication authentication) {
		return receiptService.markRead(currentUserId(authentication), conversationId, request.messageId());
	}

	@GetMapping("/unread-count")
	UnreadCountResponse unreadCount(@PathVariable UUID conversationId, Authentication authentication) {
		return receiptService.getUnreadCount(currentUserId(authentication), conversationId);
	}

	/**
	 * The access token's subject, which is the user ID. Never a value from the request path or body.
	 */
	private static UUID currentUserId(Authentication authentication) {
		return UUID.fromString(authentication.getName());
	}

}
