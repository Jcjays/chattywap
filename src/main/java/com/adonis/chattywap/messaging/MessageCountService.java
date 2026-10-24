package com.adonis.chattywap.messaging;

import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The example consumer's effect: counts the {@code MessageCreated} events processed per conversation, exactly once
 * per event however often it is delivered. It exists to show that asynchronous processing is reliable and
 * idempotent; nothing in the API reads the counts.
 */
@Service
class MessageCountService {

	/** This consumer's name in {@code processed_events}. */
	static final String CONSUMER_NAME = "message-counts";

	private final ProcessedEventRepository processedEventRepository;

	private final ConversationMessageCountRepository messageCountRepository;

	MessageCountService(ProcessedEventRepository processedEventRepository,
			ConversationMessageCountRepository messageCountRepository) {
		this.processedEventRepository = processedEventRepository;
		this.messageCountRepository = messageCountRepository;
	}

	/**
	 * Counts the event's message for its conversation, unless this event was counted before. The record that the
	 * event was processed and the count commit in one transaction: either both or neither, so a failure or crash in
	 * between leaves nothing that would make a redelivery skip the event or count it twice.
	 *
	 * @return {@code false} if the event had already been processed, and nothing changed
	 */
	@Transactional
	public boolean recordMessageCreated(MessageCreatedEvent event) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (processedEventRepository.insertIfAbsent(CONSUMER_NAME, event.eventId(), now) == 0) {
			return false;
		}
		messageCountRepository.increment(event.conversationId(), now);
		return true;
	}

}
