package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The single Enable Banking authorization this (single-user) app holds: which bank, which account, and
 * the session that grants read access to it. Lives in finanzas.db like everything else, so it rides along
 * with the existing automatic backup for free. Re-linking updates this row in place rather than creating
 * a new one — there is only ever one bank connection.
 */
@Entity
@Table(name = "bank_links")
@Getter
@Setter
public class BankLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aspsp_name", nullable = false)
    private String aspspName;

    @Column(name = "aspsp_country", nullable = false)
    private String aspspCountry;

    @Column(name = "account_uid", nullable = false)
    private String accountUid;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
