package io.github.cyson21.reservation.application.port.out;

import io.github.cyson21.reservation.domain.Reservation;

import java.util.UUID;

public interface ReservationStore {

    /**
     * Atomically confirms an active reservation or reports an overlapping active reservation for the same equipment.
     * Confirmation means the write is visible to later operations. Storage failures propagate to the caller.
     */
    ReserveResult tryReserve(Reservation reservation);

    /**
     * Cancels the reservation if it exists. Repeated cancellation returns the same success result.
     */
    CancelResult cancel(UUID reservationId);

    enum ReserveResult {
        CONFIRMED,
        TIME_CONFLICT
    }

    enum CancelResult {
        CANCELLED,
        NOT_FOUND
    }
}
