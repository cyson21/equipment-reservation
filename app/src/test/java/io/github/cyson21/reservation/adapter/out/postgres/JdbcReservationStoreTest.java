package io.github.cyson21.reservation.adapter.out.postgres;

import io.github.cyson21.reservation.application.port.out.ReservationStore;
import io.github.cyson21.reservation.domain.Equipment;
import io.github.cyson21.reservation.domain.Reservation;
import io.github.cyson21.reservation.domain.TimeSlot;
import io.github.cyson21.reservation.testing.ReservationStoreContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static io.github.cyson21.reservation.application.port.out.ReservationStore.ReserveResult.CONFIRMED;
import static io.github.cyson21.reservation.application.port.out.ReservationStore.ReserveResult.TIME_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class JdbcReservationStoreTest extends ReservationStoreContract {

    private final String schema = "reservation_test_" + UUID.randomUUID().toString().replace("-", "");

    private JdbcClient admin;
    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcReservationStore store;

    @Override
    protected ReservationStore createStore() {
        admin = JdbcClient.create(dataSource(null, null));
        dataSource = dataSource(schema, null);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        store = new JdbcReservationStore(jdbc);
        return store;
    }

    @AfterEach
    void dropTemporarySchema() {
        if (admin != null) {
            admin.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }

    @Test
    void looksUpTheSeededEquipment() {
        var catalog = new JdbcEquipmentCatalog(jdbc);

        assertEquals(List.of(new Equipment(FIRST_EQUIPMENT_ID, "Camera"), new Equipment(SECOND_EQUIPMENT_ID, "Tripod")),
                catalog.findAll());
        assertTrue(catalog.existsById(FIRST_EQUIPMENT_ID));
        assertFalse(catalog.existsById(-1));
    }

    @Test
    void preservesExactMicrosecondsOfOffsetDateTimes() {
        var startsAt = OffsetDateTime.parse("2030-02-02T00:00:00.123456+09:00").toInstant();
        var endsAt = OffsetDateTime.parse("2030-02-01T15:00:00.123457Z").toInstant();
        var reservation = new Reservation(UUID.randomUUID(), FIRST_EQUIPMENT_ID, new TimeSlot(startsAt, endsAt));

        assertEquals(CONFIRMED, store.tryReserve(reservation));

        TimeSlot storedSlot = jdbc.sql("SELECT starts_at, ends_at FROM reservation WHERE id = :id")
                .param("id", reservation.id())
                .query((row, index) -> new TimeSlot(row.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        row.getObject("ends_at", OffsetDateTime.class).toInstant()))
                .single();
        assertEquals(reservation.timeSlot(), storedSlot);
    }

    @Test
    void propagatesPrimaryKeyViolations() {
        var slot = new TimeSlot(Instant.parse("2030-02-02T01:00:00Z"), Instant.parse("2030-02-02T02:00:00Z"));
        var id = UUID.randomUUID();
        assertEquals(CONFIRMED, store.tryReserve(new Reservation(id, FIRST_EQUIPMENT_ID, slot)));

        assertThrows(DuplicateKeyException.class, () -> store.tryReserve(new Reservation(id, SECOND_EQUIPMENT_ID, slot)));
    }

    @Test
    @Timeout(25)
    void overlappingRequestsActuallyContendAndOnlyTheCommittedReservationSurvives() throws Exception {
        var startsAt = Instant.parse("2030-02-02T01:00:00Z");
        var slot = new TimeSlot(startsAt, startsAt.plusSeconds(3600));
        var first = new Reservation(UUID.randomUUID(), FIRST_EQUIPMENT_ID, slot);
        var second = new Reservation(UUID.randomUUID(), FIRST_EQUIPMENT_ID, slot);

        String waiterName = schema + "_waiter";
        var waiterStore = new JdbcReservationStore(JdbcClient.create(dataSource(schema, waiterName)));
        var writerTransaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = writerTransaction.execute(transaction -> {
                assertEquals(CONFIRMED, store.tryReserve(first));
                int writerPid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
                var waiting = executor.submit(() -> waiterStore.tryReserve(second));
                awaitBlockingSession(waiterName, writerPid);
                return waiting;
            });

            assertEquals(TIME_CONFLICT, pending.get(5, TimeUnit.SECONDS));
        }

        List<UUID> active = jdbc.sql("SELECT id FROM reservation WHERE status = 'ACTIVE'")
                .query(UUID.class)
                .list();
        assertEquals(List.of(first.id()), active);
    }

    private void awaitBlockingSession(String waiterName, int writerPid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            boolean blocked = admin.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE application_name = :waiterName
                                  AND :writerPid = ANY (pg_blocking_pids(pid))
                            )
                            """)
                    .param("waiterName", waiterName)
                    .param("writerPid", writerPid)
                    .query(Boolean.class)
                    .single();
            if (blocked) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        } while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline);
        throw new AssertionError("The second database session did not wait on the first transaction.");
    }

    private static DriverManagerDataSource dataSource(String schema, String applicationName) {
        var source = new DriverManagerDataSource(
                requiredEnv("TEST_POSTGRES_URL"),
                requiredEnv("TEST_POSTGRES_USER"),
                System.getenv("TEST_POSTGRES_PASSWORD"));
        var settings = new Properties();
        settings.setProperty("connectTimeout", "5");
        settings.setProperty("socketTimeout", "12");
        settings.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=9000");
        if (schema != null) {
            settings.setProperty("currentSchema", schema + ",public");
        }
        if (applicationName != null) {
            settings.setProperty("ApplicationName", applicationName);
        }
        source.setConnectionProperties(settings);
        return source;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must point to a disposable PostgreSQL test database.");
        }
        return value;
    }
}
