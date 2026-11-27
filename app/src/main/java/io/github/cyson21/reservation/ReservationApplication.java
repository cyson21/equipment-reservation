package io.github.cyson21.reservation;

import io.github.cyson21.reservation.adapter.in.cli.ReservationCli;
import io.github.cyson21.reservation.adapter.out.postgres.JdbcEquipmentCatalog;
import io.github.cyson21.reservation.adapter.out.postgres.JdbcReservationStore;
import io.github.cyson21.reservation.application.ReservationService;
import io.github.cyson21.reservation.application.port.in.CancelReservation;
import io.github.cyson21.reservation.application.port.in.ListEquipment;
import io.github.cyson21.reservation.application.port.in.ReserveEquipment;
import io.github.cyson21.reservation.application.port.out.EquipmentCatalog;
import io.github.cyson21.reservation.application.port.out.ReservationStore;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;

@SpringBootApplication
public class ReservationApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("cli")) {
            System.exit(runCli(Arrays.copyOfRange(args, 1, args.length)));
        } else {
            SpringApplication.run(ReservationApplication.class, args);
        }
    }

    private static int runCli(String[] args) {
        var application = new SpringApplication(ReservationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setDefaultProperties(Map.of("logging.level.root", "OFF"));

        try (var context = application.run()) {
            return context.getBean(ReservationCli.class).execute(args,
                    new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        }
    }

    @Bean
    EquipmentCatalog equipmentCatalog(JdbcClient jdbc) {
        return new JdbcEquipmentCatalog(jdbc);
    }

    @Bean
    ReservationStore reservationStore(JdbcClient jdbc) {
        return new JdbcReservationStore(jdbc);
    }

    @Bean
    ReservationService reservationService(EquipmentCatalog equipment, ReservationStore reservations) {
        return new ReservationService(equipment, reservations);
    }

    @Bean
    @ConditionalOnNotWebApplication
    ReservationCli reservationCli(ListEquipment list, ReserveEquipment reserve, CancelReservation cancel) {
        return new ReservationCli(list, reserve, cancel);
    }
}
