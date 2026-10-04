package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One value of a public economic indicator (the Economy view): what {@code indicator} was in {@code region} over
 * {@code period}. Periods are text so months, quarters, days and hours sort the same way: "2026-08", "2026-Q2",
 * "2026-09-26", "2026-09-26T13". {@code arrivedAt} is when the app first stored it, to tell what's new.
 */
@Entity
@Table(name = "economy_points",
        uniqueConstraints = @UniqueConstraint(name = "uk_economy_point", columnNames = {"indicator", "region", "period"}),
        indexes = @Index(name = "idx_economy_indicator", columnList = "indicator, region"))
@Getter
@Setter
public class EconomyPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String indicator;

    /** A {@code Region} code ("ES", "CT"...). */
    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private String period;

    @Column(nullable = false)
    private double value;

    @Column(name = "arrived_at", nullable = false)
    private Instant arrivedAt;
}
