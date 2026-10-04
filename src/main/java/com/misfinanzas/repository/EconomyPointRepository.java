package com.misfinanzas.repository;

import com.misfinanzas.entity.EconomyPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EconomyPointRepository extends JpaRepository<EconomyPoint, Long> {

    Optional<EconomyPoint> findByIndicatorAndRegionAndPeriod(String indicator, String region, String period);

    List<EconomyPoint> findByIndicatorAndRegionAndPeriodGreaterThanEqualOrderByPeriodAsc(String indicator, String region, String from);

    List<EconomyPoint> findByIndicatorInAndRegionAndPeriodGreaterThanEqual(Collection<String> indicators, String region, String from);

    List<EconomyPoint> findByIndicatorStartingWithAndRegionAndPeriod(String prefix, String region, String period);

    List<EconomyPoint> findByIndicatorStartingWithAndRegionAndPeriodGreaterThanEqual(String prefix, String region, String from);

    Optional<EconomyPoint> findTopByIndicatorStartingWithAndRegionOrderByPeriodDesc(String prefix, String region);

    Optional<EconomyPoint> findTopByIndicatorAndRegionOrderByPeriodDesc(String indicator, String region);

    long countByIndicatorAndRegionAndPeriodStartingWith(String indicator, String region, String prefix);

    boolean existsByIndicatorAndRegionAndPeriod(String indicator, String region, String period);

    long deleteByIndicatorAndPeriodLessThan(String indicator, String period);
}
