package io.github.cyson21.reservation.adapter.out.postgres;

import io.github.cyson21.reservation.application.port.out.EquipmentCatalog;
import io.github.cyson21.reservation.domain.Equipment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public final class JdbcEquipmentCatalog implements EquipmentCatalog {

    private final JdbcClient jdbc;

    public JdbcEquipmentCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Equipment> findAll() {
        return jdbc.sql("SELECT id, name FROM equipment ORDER BY id")
                .query((row, index) -> new Equipment(row.getLong("id"), row.getString("name")))
                .list();
    }

    @Override
    public boolean existsById(long equipmentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM equipment WHERE id = :id)")
                .param("id", equipmentId)
                .query(Boolean.class)
                .single();
    }
}
