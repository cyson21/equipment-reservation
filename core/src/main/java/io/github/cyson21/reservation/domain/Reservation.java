package io.github.cyson21.reservation.domain;

import java.util.Objects;
import java.util.UUID;

public record Reservation(UUID id, long equipmentId, TimeSlot timeSlot) {

    public Reservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timeSlot, "timeSlot");
    }
}
