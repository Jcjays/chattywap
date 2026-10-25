-- The request that wrote an outbox event, for following it through the logs: the request ID that the request's log
-- lines carry (LogContext.REQUEST_ID in the application). The relay publishes it as the message's AMQP correlation_id,
-- and consumers log it while they process the event. Null for events written outside a request. Diagnostic only:
-- nothing depends on it, and it isn't part of the event's payload.
alter table outbox_events add column correlation_id varchar(64);
