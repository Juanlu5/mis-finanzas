package com.misfinanzas.repository;

import com.misfinanzas.entity.TrackedProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedProductRepository extends JpaRepository<TrackedProduct, Long> {

    List<TrackedProduct> findAllByOrderByCreatedAtDesc();

    Optional<TrackedProduct> findByUrl(String url);
}
