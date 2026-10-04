package com.misfinanzas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A regular payment the user said isn't a fixed expense (a transfer to their own savings, say), by the key
 * {@code RecurringService} gives its group. It stays detected, just out of the totals and folded away.
 */
@Entity
@Table(name = "recurring_hidden")
@Getter
@Setter
public class RecurringHidden {

    @Id
    @Column(name = "group_key")
    private String key;

    @Column(name = "hidden_at", nullable = false)
    private Instant hiddenAt;
}
