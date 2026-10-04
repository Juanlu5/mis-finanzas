package com.misfinanzas.repository;

import com.misfinanzas.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findBySourceAndDayBetween(String source, LocalDate from, LocalDate to);

    List<Holiday> findByRegionAndDayBetweenOrderByDay(String region, LocalDate from, LocalDate to);

    List<Holiday> findByTownCodeAndDayBetweenOrderByDay(String townCode, LocalDate from, LocalDate to);

    List<Holiday> findByPlaceRoleAndDayBetweenOrderByDay(String placeRole, LocalDate from, LocalDate to);

    long deleteBySourceAndDayBetween(String source, LocalDate from, LocalDate to);

    long deleteBySourceAndTownCodeAndDayBetween(String source, String townCode, LocalDate from, LocalDate to);
}
