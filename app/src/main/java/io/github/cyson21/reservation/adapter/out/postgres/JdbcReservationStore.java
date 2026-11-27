package io.github.cyson21.reservation.adapter.out.postgres;

import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Reservation;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.ZoneOffset;
import java.util.UUID;

public final class JdbcReservationStore implements ReservationStore {

    private final JdbcClient jdbc;

    public JdbcReservationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReserveResult tryReserve(Reservation reservation) {
        return jdbc.sql("""
                        INSERT INTO reservation (id, equipment_id, starts_at, ends_at, status)
                        VALUES (:id, :equipmentId, :startsAt, :endsAt, 'ACTIVE')
                        ON CONFLICT ON CONSTRAINT reservation_no_active_overlap DO NOTHING
                        RETURNING id
                        """)
                .param("id", reservation.id())
                .param("equipmentId", reservation.equipmentId())
                .param("startsAt", reservation.timeSlot().startsAt().atOffset(ZoneOffset.UTC))
                .param("endsAt", reservation.timeSlot().endsAt().atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional()
                .map(inserted -> ReserveResult.CONFIRMED)
                .orElse(ReserveResult.TIME_CONFLICT);
    }

    @Override
    public CancelResult cancel(UUID reservationId) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CANCELLED'
                        WHERE id = :id
                        RETURNING id
                        """)
                .param("id", reservationId)
                .query(UUID.class)
                .optional()
                .map(updated -> CancelResult.CANCELLED)
                .orElse(CancelResult.NOT_FOUND);
    }
}
