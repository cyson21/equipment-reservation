package io.github.cyson21.reservation.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface ReserveEquipment {

    Result reserve(long equipmentId, Instant startsAt, Instant endsAt);

    sealed interface Result permits Confirmed, Rejection {
    }

    record Confirmed(UUID reservationId) implements Result {
    }

    enum Rejection implements Result {
        INVALID_TIME_SLOT,
        EQUIPMENT_NOT_FOUND,
        TIME_CONFLICT
    }
}
