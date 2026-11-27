package io.github.cyson21.reservation.adapter.in;

import com.jayway.jsonpath.JsonPath;
import io.github.cyson21.reservation.adapter.in.cli.ReservationCli;
import io.github.cyson21.reservation.adapter.in.rest.ReservationController;
import io.github.cyson21.reservation.application.ReservationService;
import io.github.cyson21.reservation.application.port.out.EquipmentCatalog;
import io.github.cyson21.reservation.domain.Equipment;
import io.github.cyson21.reservation.testing.InMemoryReservationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationInputsTest {
    private static final String START = "2026-12-10T11:00:00+09:00";
    private static final String END = "2026-12-10T12:00:00+09:00";
    private static final String START_UTC = "2026-12-10T02:00:00Z";
    private static final String END_UTC = "2026-12-10T03:00:00Z";

    private final ReservationService service = new ReservationService(new EquipmentCatalog() {
        @Override
        public List<Equipment> findAll() {
            return List.of(new Equipment(2, "Tripod"), new Equipment(1, "Camera"));
        }

        @Override
        public boolean existsById(long equipmentId) {
            return findAll().stream().anyMatch(equipment -> equipment.id() == equipmentId);
        }
    }, new InMemoryReservationStore());
    private final MockMvc rest = MockMvcBuilders.standaloneSetup(new ReservationController(service, service, service))
            .build();
    private final ReservationCli cli = new ReservationCli(service, service, service);

    @Test
    void listsEquipmentThroughBothEntryPoints() throws Exception {
        rest.perform(get("/equipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Camera"))
                .andExpect(jsonPath("$[1].id").value(2));

        CommandResult result = run("equipment", "list");
        assertEquals(0, result.exitCode());
        assertEquals(List.of("1\tCamera", "2\tTripod"), result.stdout().lines().toList());
        assertEquals("", result.stderr());
    }

    @Test
    void restAndCliApplySameOverlapAndCancellationRules() throws Exception {
        String createdBody = create("1", START, END).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(createdBody, "$.reservationId");
        assertEquals(reservationId, UUID.fromString(reservationId).toString());

        create("1", START_UTC, END_UTC)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIME_CONFLICT"));
        assertCommandError(4, "TIME_CONFLICT", "reservation", "create", "1", START_UTC, END_UTC);

        assertEquals(new CommandResult(0, "", ""), run("reservation", "cancel", reservationId));
        rest.perform(delete("/reservations/{reservationId}", reservationId)).andExpect(status().isNoContent());

        CommandResult replacement = run("reservation", "create", "1", START_UTC, END_UTC);
        assertEquals(0, replacement.exitCode());
        assertEquals("", replacement.stderr());
        assertNotEquals(reservationId, UUID.fromString(replacement.stdout().strip()).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-12-10T02:00:00", "2026-12-10T02:00:00.000000001Z"})
    void rejectsTimesWithoutAnOffsetOrWithSubMicrosecondPrecision(String invalidStart) throws Exception {
        create("1", invalidStart, END_UTC)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_SLOT"));
        assertCommandError(2, "INVALID_TIME_SLOT", "reservation", "create", "1", invalidStart, END_UTC);
    }

    @Test
    void mapsMissingEquipmentAndReservation() throws Exception {
        create("99", START, END).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        assertCommandError(3, "EQUIPMENT_NOT_FOUND", "reservation", "create", "99", START, END);

        String unknownId = "345ee019-30bb-4e7d-bd60-1f18c396fda4";
        rest.perform(delete("/reservations/{reservationId}", unknownId)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        assertCommandError(3, "RESERVATION_NOT_FOUND", "reservation", "cancel", unknownId);
    }

    @Test
    void rejectsMalformedIdsAtBothEntryPoints() throws Exception {
        create("1.8", START, END)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EQUIPMENT_ID"));
        assertCommandError(2, "INVALID_EQUIPMENT_ID", "reservation", "create", "1.8", START, END);

        rest.perform(delete("/reservations/{reservationId}", "1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_ID"));
        assertCommandError(2, "INVALID_RESERVATION_ID", "reservation", "cancel", "1-1-1-1-1");
    }

    @Test
    void returnsAStableErrorForUnreadableJson() throws Exception {
        rest.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActions create(String equipmentId, String startsAt, String endsAt) throws Exception {
        String json = """
                {"equipmentId":%s,"startsAt":"%s","endsAt":"%s"}
                """.formatted(equipmentId, startsAt, endsAt);
        return rest.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void assertCommandError(int exitCode, String code, String... args) {
        assertEquals(new CommandResult(exitCode, "", code + System.lineSeparator()), run(args));
    }

    private CommandResult run(String... args) {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exitCode = cli.execute(args, new PrintWriter(stdout), new PrintWriter(stderr));
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
