package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * The BOE resolution with a year's holidays, once found ({@code boeId}), and how far the search through the BOE's
 * daily summaries got until then ({@code scannedUntil}), so it goes on from there instead of starting over.
 */
@Entity
@Table(name = "holiday_years")
@Getter
@Setter
public class HolidayYear {

    @Id
    @Column(name = "calendar_year")
    private Integer year;

    @Column(name = "boe_id")
    private String boeId;

    @Column(name = "scanned_until")
    private LocalDate scannedUntil;
}
