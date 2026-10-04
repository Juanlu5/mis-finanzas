package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** When the app last read one of the Economy view's sources, and why the last try failed (null = it worked). */
@Entity
@Table(name = "economy_sources")
@Getter
@Setter
public class EconomySource {

    /** "ine", "fuel", "power". */
    @Id
    @Column(name = "source_key")
    private String key;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
