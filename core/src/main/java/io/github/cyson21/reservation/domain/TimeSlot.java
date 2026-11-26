package io.github.cyson21.reservation.domain;

import java.time.Instant;

public record TimeSlot(Instant startsAt, Instant endsAt) {

    private static final Instant MIN_INCLUSIVE = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    public TimeSlot {
        if (!isSupported(startsAt) || !isSupported(endsAt) || !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException(
                    "A time slot requires increasing UTC instants in years 0001–9999 at microsecond precision.");
        }
    }

    public boolean overlaps(TimeSlot other) {
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }

    private static boolean isSupported(Instant instant) {
        return instant != null
                && !instant.isBefore(MIN_INCLUSIVE)
                && instant.isBefore(MAX_EXCLUSIVE)
                && instant.getNano() % 1_000 == 0;
    }
}
