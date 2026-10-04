package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Where the user's holidays come from: "work" decides their days off (and the long weekends); "home" is a second
 * calendar to plan visits by. A town code (INE) only for Catalan towns, whose local holidays the Generalitat
 * publishes; elsewhere just the name, and the local days are typed in.
 */
@Entity
@Table(name = "holiday_places")
@Getter
@Setter
public class HolidayPlace {

    @Id
    private String role;

    /** A {@code Region} code. */
    @Column(nullable = false)
    private String region;

    @Column(name = "town_code")
    private String townCode;

    @Column(name = "town_name")
    private String townName;
}
