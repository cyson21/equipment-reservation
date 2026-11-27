package io.github.cyson21.reservation.adapter.in.cli;

import io.github.cyson21.reservation.adapter.in.ReservationInput;
import io.github.cyson21.reservation.application.port.in.CancelReservation;
import io.github.cyson21.reservation.application.port.in.ListEquipment;
import io.github.cyson21.reservation.application.port.in.ReserveEquipment;

import java.io.PrintWriter;

public final class ReservationCli {
    private static final int SUCCESS = 0;
    private static final int INVALID_INPUT = 2;
    private static final int NOT_FOUND = 3;
    private static final int CONFLICT = 4;

    private final ListEquipment listEquipment;
    private final ReserveEquipment reserveEquipment;
    private final CancelReservation cancelReservation;

    public ReservationCli(
            ListEquipment listEquipment, ReserveEquipment reserveEquipment, CancelReservation cancelReservation) {
        this.listEquipment = listEquipment;
        this.reserveEquipment = reserveEquipment;
        this.cancelReservation = cancelReservation;
    }

    public int execute(String[] args, PrintWriter out, PrintWriter err) {
        if (args.length == 2 && args[0].equals("equipment") && args[1].equals("list")) {
            for (var equipment : listEquipment.listEquipment()) {
                out.println(equipment.id() + "\t" + equipment.name());
            }
            return SUCCESS;
        }
        if (args.length == 5 && args[0].equals("reservation") && args[1].equals("create")) {
            return reserve(args, out, err);
        }
        if (args.length == 3 && args[0].equals("reservation") && args[1].equals("cancel")) {
            return cancel(args[2], err);
        }

        err.println("INVALID_COMMAND");
        err.println("equipment list");
        err.println("reservation create <equipmentId> <startsAt> <endsAt>");
        err.println("reservation cancel <reservationId>");
        return INVALID_INPUT;
    }

    private int reserve(String[] args, PrintWriter out, PrintWriter err) {
        long equipmentId;
        try {
            equipmentId = Long.parseLong(args[2]);
        } catch (NumberFormatException invalid) {
            return fail(err, INVALID_INPUT, "INVALID_EQUIPMENT_ID");
        }
        var result = reserveEquipment.reserve(
                equipmentId, ReservationInput.instantOrNull(args[3]), ReservationInput.instantOrNull(args[4]));
        return switch (result) {
            case ReserveEquipment.Confirmed confirmed -> {
                out.println(confirmed.reservationId());
                yield SUCCESS;
            }
            case ReserveEquipment.Rejection rejection -> switch (rejection) {
                case INVALID_TIME_SLOT -> fail(err, INVALID_INPUT, rejection.name());
                case EQUIPMENT_NOT_FOUND -> fail(err, NOT_FOUND, rejection.name());
                case TIME_CONFLICT -> fail(err, CONFLICT, rejection.name());
            };
        };
    }

    private int cancel(String value, PrintWriter err) {
        var result = cancelReservation.cancel(ReservationInput.reservationIdOrNull(value));
        return switch (result) {
            case CANCELLED -> SUCCESS;
            case RESERVATION_NOT_FOUND -> fail(err, NOT_FOUND, result.name());
            case INVALID_RESERVATION_ID -> fail(err, INVALID_INPUT, result.name());
        };
    }

    private static int fail(PrintWriter err, int status, String code) {
        err.println(code);
        return status;
    }
}
