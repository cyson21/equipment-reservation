package io.github.cyson21.reservation.application;

import io.github.cyson21.reservation.application.port.in.CancelReservation;
import io.github.cyson21.reservation.application.port.in.ListEquipment;
import io.github.cyson21.reservation.application.port.in.ReserveEquipment;
import io.github.cyson21.reservation.application.port.out.EquipmentCatalog;
import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Equipment;
import io.github.cyson21.reservation.domain.Reservation;
import io.github.cyson21.reservation.domain.TimeSlot;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ReservationService implements ListEquipment, ReserveEquipment, CancelReservation {

    private final EquipmentCatalog equipmentCatalog;
    private final ReservationStore reservations;

    public ReservationService(EquipmentCatalog equipmentCatalog, ReservationStore reservations) {
        this.equipmentCatalog = Objects.requireNonNull(equipmentCatalog);
        this.reservations = Objects.requireNonNull(reservations);
    }

    @Override
    public List<Equipment> listEquipment() {
        return equipmentCatalog.findAll().stream()
                .sorted(Comparator.comparingLong(Equipment::id))
                .toList();
    }

    @Override
    public ReserveEquipment.Result reserve(long equipmentId, Instant startsAt, Instant endsAt) {
        TimeSlot slot;
        try {
            slot = new TimeSlot(startsAt, endsAt);
        } catch (IllegalArgumentException invalidSlot) {
            return Rejection.INVALID_TIME_SLOT;
        }

        if (!equipmentCatalog.existsById(equipmentId)) {
            return Rejection.EQUIPMENT_NOT_FOUND;
        }

        Reservation reservation = new Reservation(UUID.randomUUID(), equipmentId, slot);
        return switch (reservations.tryReserve(reservation)) {
            case CONFIRMED -> new Confirmed(reservation.id());
            case TIME_CONFLICT -> Rejection.TIME_CONFLICT;
        };
    }

    @Override
    public CancelReservation.Result cancel(UUID reservationId) {
        if (reservationId == null) {
            return CancelReservation.Result.INVALID_RESERVATION_ID;
        }

        return switch (reservations.cancel(reservationId)) {
            case CANCELLED -> CancelReservation.Result.CANCELLED;
            case NOT_FOUND -> CancelReservation.Result.RESERVATION_NOT_FOUND;
        };
    }
}
