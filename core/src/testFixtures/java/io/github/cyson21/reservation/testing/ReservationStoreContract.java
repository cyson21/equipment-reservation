package io.github.cyson21.reservation.testing;

import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Reservation;
import io.github.cyson21.reservation.domain.TimeSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.github.cyson21.reservation.application.port.out.ReservationStore.CancelResult.CANCELLED;
import static io.github.cyson21.reservation.application.port.out.ReservationStore.CancelResult.NOT_FOUND;
import static io.github.cyson21.reservation.application.port.out.ReservationStore.ReserveResult.CONFIRMED;
import static io.github.cyson21.reservation.application.port.out.ReservationStore.ReserveResult.TIME_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public abstract class ReservationStoreContract {

    protected static final long FIRST_EQUIPMENT_ID = 1L;
    protected static final long SECOND_EQUIPMENT_ID = 2L;

    private static final Instant START = Instant.parse("2030-01-01T14:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private ReservationStore store;

    /** Supplies a clean store for both equipment IDs. */
    protected abstract ReservationStore createStore();

    @BeforeEach
    protected void prepareStore() {
        store = createStore();
    }

    @Test
    protected void rejectsPartialOverlapForTheSameEquipment() {
        assertEquals(CONFIRMED, store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, END)));
        assertEquals(TIME_CONFLICT,
                store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START.plusSeconds(60), END.plusSeconds(60))));
    }

    @Test
    protected void acceptsSlotsThatTouchAtTheStartOrEnd() {
        assertEquals(CONFIRMED, store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, END)));
        assertEquals(CONFIRMED,
                store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START.minusSeconds(3600), START)));
        assertEquals(CONFIRMED,
                store.tryReserve(reservation(FIRST_EQUIPMENT_ID, END, END.plusSeconds(3600))));
    }

    @Test
    protected void acceptsTheSameSlotForDifferentEquipment() {
        assertEquals(CONFIRMED, store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, END)));
        assertEquals(CONFIRMED, store.tryReserve(reservation(SECOND_EQUIPMENT_ID, START, END)));
    }

    @Test
    protected void repeatedCancellationKeepsTheSlotOpenWithoutCancellingAReplacement() {
        var original = reservation(FIRST_EQUIPMENT_ID, START, END);
        assertEquals(CONFIRMED, store.tryReserve(original));
        assertEquals(CANCELLED, store.cancel(original.id()));

        var replacement = reservation(FIRST_EQUIPMENT_ID, START, END);
        assertEquals(CONFIRMED, store.tryReserve(replacement));
        assertEquals(CANCELLED, store.cancel(original.id()));
        assertEquals(TIME_CONFLICT, store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, END)));
    }

    @Test
    protected void cancelReportsAnUnknownId() {
        assertEquals(NOT_FOUND, store.cancel(UUID.randomUUID()));
    }

    @Test
    protected void duplicateIdRemainsAFailureWithNoTimeConflict() {
        var original = reservation(FIRST_EQUIPMENT_ID, START, END);
        assertEquals(CONFIRMED, store.tryReserve(original));

        var duplicate = new Reservation(original.id(), SECOND_EQUIPMENT_ID, original.timeSlot());
        assertThrows(RuntimeException.class, () -> store.tryReserve(duplicate));
    }

    @Test
    protected void usesMicrosecondBoundariesWithoutRoundingAwayShortReservations() {
        var nextMicrosecond = START.plusNanos(1_000);
        var followingMicrosecond = START.plusNanos(2_000);

        assertEquals(CONFIRMED, store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, nextMicrosecond)));
        assertEquals(CONFIRMED,
                store.tryReserve(reservation(FIRST_EQUIPMENT_ID, nextMicrosecond, followingMicrosecond)));
        assertEquals(TIME_CONFLICT,
                store.tryReserve(reservation(FIRST_EQUIPMENT_ID, START, followingMicrosecond)));
    }

    private static Reservation reservation(long equipmentId, Instant startsAt, Instant endsAt) {
        return new Reservation(UUID.randomUUID(), equipmentId, new TimeSlot(startsAt, endsAt));
    }
}
