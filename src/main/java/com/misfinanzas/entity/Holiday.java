package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A public holiday (the Holidays view). From the BOE, one row per region that has it ({@code region} set, kind
 * "national" or "region"); from the Generalitat, a Catalan town's local ones ({@code townCode}, the INE code, kind
 * "local"); or typed in by the user for one of their places ({@code placeRole}, source "manual").
 */
@Entity
@Table(name = "holidays", indexes = @Index(name = "idx_holidays_day", columnList = "day"))
@Getter
@Setter
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private String name;

    /** "national", "region" or "local". */
    @Column(nullable = false)
    private String kind;

    /** "boe", "generalitat" or "manual". */
    @Column(nullable = false)
    private String source;

    /** A {@code Region} code, for the BOE's. */
    private String region;

    @Column(name = "town_code")
    private String townCode;

    /** "work" or "home", for the ones typed in. */
    @Column(name = "place_role")
    private String placeRole;
}
