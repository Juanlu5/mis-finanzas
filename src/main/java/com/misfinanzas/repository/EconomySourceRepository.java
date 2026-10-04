package com.misfinanzas.repository;

import com.misfinanzas.entity.EconomySource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EconomySourceRepository extends JpaRepository<EconomySource, String> {
}
