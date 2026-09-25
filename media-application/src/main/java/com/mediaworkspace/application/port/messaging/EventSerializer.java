package com.mediaworkspace.application.port.messaging;

import com.mediaworkspace.contracts.event.EventEnvelope;

/**
 * Serialization of an event envelope to its wire form.
 *
 * <p>A port so the application layer can hand an envelope to the outbox without depending on a
 * specific JSON configuration. The adapter that implements it owns the exact mapper settings,
 * including which deviations from the schema are tolerated on the way in.
 */
public interface EventSerializer {

    /** Serializes an envelope for storage in the outbox and delivery to the broker. */
    String toJson(EventEnvelope envelope);

    /**
     * Parses a wire envelope.
     *
     * @throws MalformedEventException when the record cannot be parsed or its required members are
     *                                 missing; the consumer routes such a record to the dead-letter
     *                                 path instead of retrying it forever
     */
    EventEnvelope fromJson(String json);

    /** A record that cannot be interpreted as an envelope. */
    class MalformedEventException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MalformedEventException(String message) {
            super(message);
        }

        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
