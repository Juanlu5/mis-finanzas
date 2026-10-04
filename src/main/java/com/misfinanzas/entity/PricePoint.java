package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One step of a tracked product's price history. A point is stored the first time a product is read and then
 * only when its price or stock changes, so the history is a list of changes: the price between two points is
 * the older one's. {@link TrackedProduct#getCheckedAt()} says how recent the last one still is.
 */
@Entity
@Table(name = "price_points", indexes = @Index(name = "idx_price_points_product", columnList = "product_id"))
@Getter
@Setter
public class PricePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    @Convert(converter = Money.Column.class)
    private BigDecimal price;

    private Boolean available;

    public void setPrice(BigDecimal price) {
        this.price = Money.of(price);
    }
}
