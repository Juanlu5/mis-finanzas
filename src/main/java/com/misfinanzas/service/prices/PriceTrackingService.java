package com.misfinanzas.service.prices;

import com.misfinanzas.entity.PricePoint;
import com.misfinanzas.entity.TrackedProduct;
import com.misfinanzas.repository.PricePointRepository;
import com.misfinanzas.repository.TrackedProductRepository;
import com.misfinanzas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Watches the price of product pages the user pasted: reads each one when added and then every few hours
 * ({@link PriceCheckScheduler}), keeping a history of the changes. Reading a page is a network call, so none of
 * this runs inside a database transaction (a slow shop would hold the SQLite write lock).
 */
@Service
public class PriceTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PriceTrackingService.class);

    private final TrackedProductRepository products;
    private final PricePointRepository points;
    private final PageFetcher fetcher;
    private final Duration pauseBetween;

    public PriceTrackingService(TrackedProductRepository products, PricePointRepository points, PageFetcher fetcher,
                                @Value("${app.prices.pause-between:PT3S}") Duration pauseBetween) {
        this.products = products;
        this.points = points;
        this.fetcher = fetcher;
        this.pauseBetween = pauseBetween;
    }

    /** Reads the page now and starts following it; nothing is saved if it can't be read. */
    public TrackedProduct add(String link, BigDecimal targetPrice) {
        URI url = parseLink(link);
        requireValidTarget(targetPrice);
        if (products.findByUrl(url.toString()).isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ya estás siguiendo ese producto.");
        }
        ProductPageParser.ProductInfo info;
        try {
            info = read(url);
        } catch (PriceReadException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        Instant now = Instant.now();
        TrackedProduct product = new TrackedProduct();
        product.setUrl(url.toString());
        product.setStore(store(url));
        product.setTargetPrice(targetPrice);
        product.setCreatedAt(now);
        apply(product, info, now);
        products.save(product);
        savePoint(product, now);
        return product;
    }

    /** Reads the page again; a failure is kept on the product (and the last good price stays) rather than thrown. */
    public TrackedProduct check(TrackedProduct product) {
        Instant now = Instant.now();
        try {
            ProductPageParser.ProductInfo info = read(URI.create(product.getUrl()));
            boolean changed = product.getPrice().compareTo(info.price()) != 0
                    || !Objects.equals(product.getAvailable(), info.available());
            apply(product, info, now);
            product.setLastError(null);
            product.setLastErrorAt(null);
            products.save(product);
            if (changed) savePoint(product, now);
        } catch (PriceReadException e) {
            product.setLastError(e.getMessage());
            product.setLastErrorAt(now);
            products.save(product);
            log.info("Price check failed for {}: {}", product.getUrl(), e.getMessage());
        }
        return product;
    }

    public TrackedProduct check(Long id) {
        return check(get(id));
    }

    /** Every product, one after another with a pause in between, so no shop gets a burst of requests. */
    public void checkAll() {
        List<TrackedProduct> all = products.findAll();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0 && !pause()) return;
            check(all.get(i));
        }
        if (!all.isEmpty()) log.info("Checked the price of {} product(s)", all.size());
    }

    public TrackedProduct setTarget(Long id, BigDecimal targetPrice) {
        requireValidTarget(targetPrice);
        TrackedProduct product = get(id);
        product.setTargetPrice(targetPrice);
        return products.save(product);
    }

    @Transactional
    public void delete(Long id) {
        points.deleteByProductId(id);
        products.deleteById(id);
    }

    public List<TrackedProduct> list() {
        return products.findAllByOrderByCreatedAtDesc();
    }

    public List<PricePoint> history(Long id) {
        get(id);
        return points.findByProductIdOrderByCheckedAtAscIdAsc(id);
    }

    public TrackedProduct get(Long id) {
        return products.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ese producto ya no está en la lista."));
    }

    private ProductPageParser.ProductInfo read(URI url) {
        PageFetcher.Page page = fetcher.fetch(url);
        return ProductPageParser.parse(page.html(), page.url().toString());
    }

    private static void apply(TrackedProduct product, ProductPageParser.ProductInfo info, Instant now) {
        product.setName(info.name());
        product.setPrice(info.price());
        product.setCurrency(info.currency());
        product.setAvailable(info.available());
        product.setCheckedAt(now);
    }

    private void savePoint(TrackedProduct product, Instant at) {
        PricePoint point = new PricePoint();
        point.setProductId(product.getId());
        point.setCheckedAt(at);
        point.setPrice(product.getPrice());
        point.setAvailable(product.getAvailable());
        points.save(point);
    }

    private boolean pause() {
        try {
            Thread.sleep(pauseBetween);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down: stop here, the rest waits for the next round
            return false;
        }
    }

    static URI parseLink(String link) {
        String text = link == null ? "" : link.trim();
        if (text.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Pega el enlace del producto.");
        URI url;
        try {
            url = new URI(text);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Eso no parece un enlace: cópialo entero desde la barra del navegador.");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || url.getHost() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Eso no parece el enlace de una tienda: tiene que empezar por https://");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (host.matches("(.+\\.)?amazon\\.[a-z.]+") || host.equals("amzn.eu") || host.equals("amzn.to")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amazon no permite que un programa lea sus páginas. Para "
                    + "Amazon, usa Keepa o camelcamelcamel: se integrarán en un paso siguiente.");
        }
        return url.normalize();
    }

    static String store(URI url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static void requireValidTarget(BigDecimal target) {
        if (target != null && target.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El precio objetivo tiene que ser mayor que 0.");
        }
    }
}
