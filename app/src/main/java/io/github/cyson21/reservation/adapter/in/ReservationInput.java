package io.github.cyson21.reservation.adapter.in;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class ReservationInput {
    private ReservationInput() {
    }

    public static Instant instantOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    public static UUID reservationIdOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
