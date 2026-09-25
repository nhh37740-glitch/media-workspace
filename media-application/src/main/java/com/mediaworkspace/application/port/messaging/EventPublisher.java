package com.mediaworkspace.application.port.messaging;

/**
 * Delivery of an already-persisted outbox record to the broker.
 *
 * <p>An implementation returns normally only after the broker acknowledged the record. A
 * successful return means "the broker has it", which is a different statement from "the business
 * operation succeeded": the business state was already committed before the record was sent.
 * Failures propagate so the publisher can reschedule the row instead of dropping it.
 */
public interface EventPublisher {

    /**
     * Sends one record.
     *
     * @param topic   destination topic, already resolved to the environment's prefixed name
     * @param key     partition key, the media id
     * @param payload JSON body
     * @throws PublishFailedException when the record was not acknowledged
     */
    void publish(String topic, String key, String payload) throws PublishFailedException;

    /** The broker did not acknowledge the record within the allowed time. */
    class PublishFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        public PublishFailedException(String message) {
            super(message);
        }
    }
}
