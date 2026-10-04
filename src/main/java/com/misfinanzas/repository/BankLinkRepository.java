package com.misfinanzas.repository;

import com.misfinanzas.entity.BankLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BankLinkRepository extends JpaRepository<BankLink, Long> {
    Optional<BankLink> findTopByOrderByIdDesc();
}
