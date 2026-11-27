package io.github.cyson21.reservation.adapter.in.rest;

import io.github.cyson21.reservation.adapter.in.ReservationInput;
import io.github.cyson21.reservation.application.port.in.CancelReservation;
import io.github.cyson21.reservation.application.port.in.ListEquipment;
import io.github.cyson21.reservation.application.port.in.ReserveEquipment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@RestController
public final class ReservationController {
    private final ListEquipment listEquipment;
    private final ReserveEquipment reserveEquipment;
    private final CancelReservation cancelReservation;

    public ReservationController(
            ListEquipment listEquipment, ReserveEquipment reserveEquipment, CancelReservation cancelReservation) {
        this.listEquipment = listEquipment;
        this.reserveEquipment = reserveEquipment;
        this.cancelReservation = cancelReservation;
    }

    @GetMapping("/equipment")
    List<EquipmentResponse> list() {
        return listEquipment.listEquipment().stream()
                .map(equipment -> new EquipmentResponse(equipment.id(), equipment.name()))
                .toList();
    }

    @PostMapping("/reservations")
    ResponseEntity<?> reserve(@RequestBody CreateRequest request) {
        JsonNode equipmentId = request.equipmentId();
        if (equipmentId == null || !equipmentId.isIntegralNumber() || !equipmentId.canConvertToLong()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_EQUIPMENT_ID");
        }

        var result = reserveEquipment.reserve(
                equipmentId.longValue(),
                ReservationInput.instantOrNull(request.startsAt()),
                ReservationInput.instantOrNull(request.endsAt()));
        return switch (result) {
            case ReserveEquipment.Confirmed confirmed -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(new CreateResponse(confirmed.reservationId()));
            case ReserveEquipment.Rejection rejection -> switch (rejection) {
                case INVALID_TIME_SLOT -> error(HttpStatus.BAD_REQUEST, rejection.name());
                case EQUIPMENT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, rejection.name());
                case TIME_CONFLICT -> error(HttpStatus.CONFLICT, rejection.name());
            };
        };
    }

    @DeleteMapping("/reservations/{reservationId}")
    ResponseEntity<?> cancel(@PathVariable("reservationId") String reservationId) {
        var result = cancelReservation.cancel(ReservationInput.reservationIdOrNull(reservationId));
        return switch (result) {
            case CANCELLED -> ResponseEntity.noContent().build();
            case RESERVATION_NOT_FOUND -> error(HttpStatus.NOT_FOUND, result.name());
            case INVALID_RESERVATION_ID -> error(HttpStatus.BAD_REQUEST, result.name());
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code));
    }

    private record EquipmentResponse(long id, String name) {
    }

    private record CreateRequest(JsonNode equipmentId, String startsAt, String endsAt) {
    }

    private record CreateResponse(UUID reservationId) {
    }

    private record ErrorResponse(String code) {
    }
}
