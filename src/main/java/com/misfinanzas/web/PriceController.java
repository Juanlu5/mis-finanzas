package com.misfinanzas.web;

import com.misfinanzas.entity.PricePoint;
import com.misfinanzas.entity.TrackedProduct;
import com.misfinanzas.service.prices.PriceTrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceTrackingService service;

    public PriceController(PriceTrackingService service) {
        this.service = service;
    }

    /**
     * A tracked product as the page shows it. {@code previous_price} is the price before the last change of price
     * and {@code changed_at} when the current one was first seen (null while it never changed); {@code restocked_at}
     * is when it was last seen in stock again after being sold out (null while in stock since it was followed, or
     * not in stock now); {@code lowest_price} / {@code highest_price} are over the whole history.
     */
    public record ProductDto(Long id, String url, String name, String store, String currency, BigDecimal price,
                             Boolean available, BigDecimal target_price, BigDecimal previous_price, Instant changed_at,
                             Instant restocked_at,
                             BigDecimal lowest_price, BigDecimal highest_price, Instant created_at, Instant checked_at,
                             String last_error, Instant last_error_at) {

        static ProductDto from(TrackedProduct p, List<PricePoint> history) {
            BigDecimal previous = null;
            Instant changedAt = null;
            // newest first: walk back over the points with the current price to the one before them
            for (int i = history.size() - 1; i >= 0; i--) {
                PricePoint point = history.get(i);
                if (point.getPrice().compareTo(p.getPrice()) != 0) {
                    previous = point.getPrice();
                    break;
                }
                changedAt = point.getCheckedAt();
            }
            if (previous == null) changedAt = null;
            Instant restockedAt = null;
            if (Boolean.TRUE.equals(p.getAvailable())) {
                // a point is stored on every change of stock: walk back over the in-stock ones to a sold-out one
                Instant inStockSince = null;
                for (int i = history.size() - 1; i >= 0; i--) {
                    PricePoint point = history.get(i);
                    if (Boolean.FALSE.equals(point.getAvailable())) {
                        restockedAt = inStockSince;
                        break;
                    }
                    inStockSince = point.getCheckedAt();
                }
            }
            BigDecimal lowest = history.stream().map(PricePoint::getPrice).min(Comparator.naturalOrder()).orElse(p.getPrice());
            BigDecimal highest = history.stream().map(PricePoint::getPrice).max(Comparator.naturalOrder()).orElse(p.getPrice());
            return new ProductDto(p.getId(), p.getUrl(), p.getName(), p.getStore(), p.getCurrency(), p.getPrice(),
                    p.getAvailable(), p.getTargetPrice(), previous, changedAt, restockedAt, lowest, highest, p.getCreatedAt(),
                    p.getCheckedAt(), p.getLastError(), p.getLastErrorAt());
        }
    }

    public record PointDto(Instant date, BigDecimal price, Boolean available) {
        static PointDto from(PricePoint p) {
            return new PointDto(p.getCheckedAt(), p.getPrice(), p.getAvailable());
        }
    }

    @GetMapping
    public List<ProductDto> list() {
        return service.list().stream().map(this::dto).toList();
    }

    /** Reads the page before saving anything: a shop that can't be read is refused right away (422 + why). */
    @PostMapping
    public ProductDto add(@RequestParam String url, @RequestParam(required = false) String target_price) {
        return dto(service.add(url, amount(target_price)));
    }

    /** Sets or (empty) removes the target price. */
    @PatchMapping("/{id}")
    public ProductDto setTarget(@PathVariable Long id, @RequestParam(required = false) String target_price) {
        return dto(service.setTarget(id, amount(target_price)));
    }

    /** Reads the page now. A failure doesn't fail the request: it comes back in {@code last_error}. */
    @PostMapping("/{id}/check")
    public ProductDto check(@PathVariable Long id) {
        return dto(service.check(id));
    }

    @GetMapping("/{id}/history")
    public List<PointDto> history(@PathVariable Long id) {
        return service.history(id).stream().map(PointDto::from).toList();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }

    private ProductDto dto(TrackedProduct p) {
        return ProductDto.from(p, service.history(p.getId()));
    }

    // The page sends a plain number ("45.3"); empty means none.
    private static BigDecimal amount(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El precio objetivo no es un número válido.");
        }
    }
}
