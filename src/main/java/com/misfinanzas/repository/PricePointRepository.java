package com.misfinanzas.repository;

import com.misfinanzas.entity.PricePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PricePointRepository extends JpaRepository<PricePoint, Long> {

    /** Oldest first. */
    List<PricePoint> findByProductIdOrderByCheckedAtAscIdAsc(Long productId);

    void deleteByProductId(Long productId);
}
