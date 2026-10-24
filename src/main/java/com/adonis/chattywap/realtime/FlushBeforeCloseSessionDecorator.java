package com.adonis.chattywap.realtime;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spring's {@link ConcurrentWebSocketSessionDecorator}, except that closing the connection first lets the frames
 * already sent to it go out. Every connection's session is one ({@link WebSocketConfig}).
 * <p>
 * One thread at a time writes to a connection. A frame sent while another thread is writing is queued, and that thread
 * writes it next, but Spring's decorator discards the queue once the connection is closing. Spring's STOMP handler
 * closes the connection right after sending an ERROR frame, so when a client's frame was rejected while another frame
 * to it was being written, such as the one sent just before, the client would see the connection close without the
 * ERROR frame. Here, closing first waits until the threads sending to the connection are done, at most for the send
 * time limit. A thread sending keeps writing until nothing is queued, so then the ERROR frame has been written.
 * <p>
 * A connection that is closed because writing to it took too long ({@link CloseStatus#SESSION_NOT_RELIABLE}) is closed
 * without waiting.
 */
class FlushBeforeCloseSessionDecorator extends ConcurrentWebSocketSessionDecorator {

	/** Held, shared, by every thread sending; {@link #close} takes it exclusively to wait for them. */
	private final ReentrantReadWriteLock sending = new ReentrantReadWriteLock();

	FlushBeforeCloseSessionDecorator(WebSocketSession delegate, int sendTimeLimit, int bufferSizeLimit) {
		super(delegate, sendTimeLimit, bufferSizeLimit);
	}

	@Override
	public void sendMessage(WebSocketMessage<?> message) throws IOException {
		sending.readLock().lock();
		try {
			super.sendMessage(message);
		}
		finally {
			sending.readLock().unlock();
		}
	}

	@Override
	public void close(CloseStatus status) throws IOException {
		if (!CloseStatus.SESSION_NOT_RELIABLE.equals(status)) {
			awaitSending();
		}
		super.close(status);
	}

	private void awaitSending() {
		try {
			if (sending.writeLock().tryLock(getSendTimeLimit(), TimeUnit.MILLISECONDS)) {
				sending.writeLock().unlock();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
