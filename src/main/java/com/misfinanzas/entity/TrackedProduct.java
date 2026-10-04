package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A product page whose price is being watched: the link the user pasted, what was read from it the last time
 * it was checked, and the price the user is waiting for. Its history is in {@link PricePoint}.
 */
@Entity
@Table(name = "tracked_products")
@Getter
@Setter
public class TrackedProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private String name;

    /** The shop's host without "www." ("zalando.es"). */
    @Column(nullable = false)
    private String store;

    @Column(nullable = false)
    private String currency;

    /** The last price read. Cents exact (see {@link Money}). */
    @Column(nullable = false)
    @Convert(converter = Money.Column.class)
    private BigDecimal price;

    /** Whether the page said it was in stock the last time; null when it didn't say. */
    private Boolean available;

    /** "Avísame cuando baje de…": null = no target. */
    @Column(name = "target_price")
    @Convert(converter = Money.Column.class)
    private BigDecimal targetPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Last time the page was read successfully. */
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    /** Why the last check failed (the price shown is then the one from {@link #checkedAt}); null if it worked. */
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    public void setPrice(BigDecimal price) {
        this.price = Money.of(price);
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = Money.of(targetPrice);
    }
}
