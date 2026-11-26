package io.github.cyson21.reservation.testing;

import io.github.cyson21.reservation.application.port.out.ReservationStore;

final class InMemoryReservationStoreTest extends ReservationStoreContract {

    @Override
    protected ReservationStore createStore() {
        return new InMemoryReservationStore();
    }
}
