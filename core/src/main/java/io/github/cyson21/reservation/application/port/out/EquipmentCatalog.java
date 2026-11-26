package io.github.cyson21.reservation.application.port.out;

import io.github.cyson21.reservation.domain.Equipment;

import java.util.List;

public interface EquipmentCatalog {

    List<Equipment> findAll();

    boolean existsById(long equipmentId);
}
