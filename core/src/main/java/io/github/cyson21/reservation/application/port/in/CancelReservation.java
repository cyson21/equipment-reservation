package io.github.cyson21.reservation.application.port.in;

import java.util.UUID;

public interface CancelReservation {

    Result cancel(UUID reservationId);

    enum Result {
        CANCELLED,
        RESERVATION_NOT_FOUND,
        INVALID_RESERVATION_ID
    }
}
