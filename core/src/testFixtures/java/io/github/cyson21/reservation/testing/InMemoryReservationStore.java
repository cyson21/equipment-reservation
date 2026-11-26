package io.github.cyson21.reservation.testing;

import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Reservation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryReservationStore implements ReservationStore {

    private final Map<UUID, Entry> reservations = new HashMap<>();

    @Override
    public synchronized ReserveResult tryReserve(Reservation reservation) {
        if (reservations.containsKey(reservation.id())) {
            throw new IllegalStateException("Reservation ID already exists.");
        }

        boolean overlaps = reservations.values().stream()
                .filter(entry -> !entry.cancelled())
                .map(Entry::reservation)
                .anyMatch(existing -> existing.equipmentId() == reservation.equipmentId()
                        && existing.timeSlot().overlaps(reservation.timeSlot()));

        if (overlaps) {
            return ReserveResult.TIME_CONFLICT;
        }

        reservations.put(reservation.id(), new Entry(reservation, false));
        return ReserveResult.CONFIRMED;
    }

    @Override
    public synchronized CancelResult cancel(UUID reservationId) {
        Entry existing = reservations.get(reservationId);
        if (existing == null) {
            return CancelResult.NOT_FOUND;
        }

        reservations.put(reservationId, new Entry(existing.reservation(), true));
        return CancelResult.CANCELLED;
    }

    private record Entry(Reservation reservation, boolean cancelled) {
    }
}
