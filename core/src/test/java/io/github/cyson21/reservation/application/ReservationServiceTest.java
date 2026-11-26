package io.github.cyson21.reservation.application;

import io.github.cyson21.reservation.application.port.in.CancelReservation;
import io.github.cyson21.reservation.application.port.in.ReserveEquipment;
import io.github.cyson21.reservation.application.port.out.EquipmentCatalog;
import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Equipment;
import io.github.cyson21.reservation.domain.Reservation;
import io.github.cyson21.reservation.testing.InMemoryReservationStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationServiceTest {

    private static final Equipment CAMERA = new Equipment(1L, "Camera");
    private static final Equipment TRIPOD = new Equipment(2L, "Tripod");
    private static final Instant START = Instant.parse("2030-01-01T14:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private final EquipmentCatalog catalog = new EquipmentCatalog() {
        @Override
        public List<Equipment> findAll() {
            return List.of(TRIPOD, CAMERA);
        }

        @Override
        public boolean existsById(long equipmentId) {
            return findAll().stream().anyMatch(equipment -> equipment.id() == equipmentId);
        }
    };

    private final ReservationService service = new ReservationService(catalog, new InMemoryReservationStore());

    @Test
    void listsEquipmentInIdOrder() {
        assertEquals(List.of(CAMERA, TRIPOD), service.listEquipment());
    }

    @Test
    void reservesCancelsAndRebooksTheSameSlot() {
        var first = assertInstanceOf(ReserveEquipment.Confirmed.class, service.reserve(CAMERA.id(), START, END));
        assertEquals(ReserveEquipment.Rejection.TIME_CONFLICT, service.reserve(CAMERA.id(), START, END));

        assertEquals(CancelReservation.Result.CANCELLED, service.cancel(first.reservationId()));
        assertEquals(CancelReservation.Result.CANCELLED, service.cancel(first.reservationId()));

        var second = assertInstanceOf(ReserveEquipment.Confirmed.class, service.reserve(CAMERA.id(), START, END));
        assertNotEquals(first.reservationId(), second.reservationId());
    }

    @Test
    void rejectsInvalidTimeAndUnknownEquipment() {
        assertEquals(ReserveEquipment.Rejection.INVALID_TIME_SLOT, service.reserve(CAMERA.id(), END, START));
        assertEquals(ReserveEquipment.Rejection.EQUIPMENT_NOT_FOUND, service.reserve(999L, START, END));
    }

    @Test
    void distinguishesAnInvalidCancellationIdFromAnUnknownReservation() {
        assertEquals(CancelReservation.Result.INVALID_RESERVATION_ID, service.cancel(null));
        assertEquals(CancelReservation.Result.RESERVATION_NOT_FOUND, service.cancel(UUID.randomUUID()));
    }

    @Test
    void propagatesStorageFailure() {
        var unavailable = new IllegalStateException("Storage unavailable");
        ReservationStore failingStore = new ReservationStore() {
            @Override
            public ReserveResult tryReserve(Reservation reservation) {
                throw unavailable;
            }

            @Override
            public CancelResult cancel(UUID reservationId) {
                throw unavailable;
            }
        };

        var serviceWithStorageFailure = new ReservationService(catalog, failingStore);

        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> serviceWithStorageFailure.reserve(CAMERA.id(), START, END)));
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> serviceWithStorageFailure.cancel(UUID.randomUUID())));
    }
}
