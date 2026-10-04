package com.misfinanzas.service.prices;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a product's name, price and stock from a shop page, from the data shops put there for search engines
 * rather than from the visible text (which every shop lays out differently and changes often):
 * <ol>
 *   <li>schema.org JSON-LD ({@code <script type="application/ld+json">}): a {@code Product} with {@code offers}
 *   (an {@code Offer}, an {@code AggregateOffer} or a list), or a {@code ProductGroup} whose {@code hasVariant}
 *   products each have theirs (one per size, say).</li>
 *   <li>Open Graph / product meta tags ({@code product:price:amount}), which some shops have instead.</li>
 * </ol>
 * With several offers the price is the cheapest one in stock (the cheapest of all if none is), and the product
 * counts as available when any offer is.
 *
 * <p>Which shops this can follow depends less on this parser than on {@link PageFetcher}: checked 2026-09-25,
 * CholloStyle works; Zalando and idealo publish data this reads but answer 403 to the JDK's HTTP client (they
 * tell programs from browsers by the connection itself, curl got through with the same headers), Zara and
 * PcComponentes wall off every program. Getting past that would mean posing as a browser, which is dodging
 * their bot protection: those shops are left to their own alerts on purpose.
 */
public final class ProductPageParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final Set<String> IN_STOCK = Set.of("instock", "limitedavailability", "onlineonly", "instoreonly",
            "preorder", "presale", "backorder");
    private static final Set<String> OUT_OF_STOCK = Set.of("outofstock", "soldout", "discontinued");

    private ProductPageParser() {
    }

    public record ProductInfo(String name, BigDecimal price, String currency, Boolean available) {
    }

    private record Offer(BigDecimal price, String currency, Boolean available) {
    }

    /** @throws PriceReadException when the page has no product price it can understand */
    public static ProductInfo parse(String html, String url) {
        Document doc = Jsoup.parse(html, url);
        ProductInfo fromJsonLd = fromJsonLd(doc);
        if (fromJsonLd != null) return fromJsonLd;
        ProductInfo fromMeta = fromMeta(doc);
        if (fromMeta != null) return fromMeta;
        throw new PriceReadException("La página no dice el precio de forma que se pueda leer: esta tienda no se puede "
                + "seguir de momento. ¿Es la página de un producto concreto?");
    }

    // ---------- JSON-LD ----------

    private static ProductInfo fromJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = JSON.readTree(script.data());
            } catch (JacksonException e) {
                continue; // a broken block (it happens) doesn't hide a good one further down
            }
            List<JsonNode> products = new ArrayList<>();
            collectProducts(root, products);
            for (JsonNode product : products) {
                ProductInfo info = fromProduct(product);
                if (info != null) return info;
            }
        }
        return null;
    }

    private static void collectProducts(JsonNode node, List<JsonNode> into) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectProducts(child, into));
            return;
        }
        if (!node.isObject()) return;
        if (hasType(node, "Product") || hasType(node, "ProductGroup")) into.add(node);
        collectProducts(node.get("@graph"), into);
    }

    private static boolean hasType(JsonNode node, String type) {
        JsonNode t = node.get("@type");
        if (t == null) return false;
        if (t.isArray()) {
            for (JsonNode one : t) if (type.equals(localName(one.asString()))) return true;
            return false;
        }
        return type.equals(localName(t.asString()));
    }

    // "http://schema.org/Product" -> "Product"
    private static String localName(String value) {
        return value.substring(value.lastIndexOf('/') + 1);
    }

    private static ProductInfo fromProduct(JsonNode product) {
        String name = text(product.get("name"));
        List<Offer> offers = new ArrayList<>();
        collectOffers(product.get("offers"), offers);
        JsonNode variants = product.get("hasVariant");
        if (variants != null) {
            for (JsonNode variant : variants.isArray() ? variants : List.of(variants)) {
                collectOffers(variant.get("offers"), offers);
                if (name == null) name = text(variant.get("name"));
            }
        }
        if (name == null || offers.isEmpty()) return null;
        return summarize(name, offers);
    }

    private static void collectOffers(JsonNode node, List<Offer> into) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectOffers(child, into));
            return;
        }
        if (!node.isObject()) return;
        // An AggregateOffer (idealo: every shop) says the cheapest in lowPrice; a plain Offer, in price
        BigDecimal price = amount(node.get("lowPrice"));
        if (price == null) price = amount(node.get("price"));
        String currency = text(node.get("priceCurrency"));
        JsonNode spec = node.get("priceSpecification");
        if (spec != null && spec.isArray() && !spec.isEmpty()) spec = spec.get(0);
        if (price == null && spec != null) price = amount(spec.get("price"));
        if (currency == null && spec != null) currency = text(spec.get("priceCurrency"));
        if (price != null) into.add(new Offer(price, currency, availability(text(node.get("availability")))));
        collectOffers(node.get("offers"), into); // an AggregateOffer may list the offers it aggregates
    }

    // ---------- meta tags ----------

    private static ProductInfo fromMeta(Document doc) {
        BigDecimal price = amount(meta(doc, "product:price:amount", "og:price:amount"));
        if (price == null) return null;
        String name = meta(doc, "og:title");
        if (name == null) name = doc.title();
        if (name == null || name.isBlank()) return null;
        String currency = meta(doc, "product:price:currency", "og:price:currency");
        Boolean available = availability(meta(doc, "product:availability", "og:availability"));
        return summarize(name.trim(), List.of(new Offer(price, currency, available)));
    }

    private static String meta(Document doc, String... properties) {
        for (String property : properties) {
            Element el = doc.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
            if (el != null && !el.attr("content").isBlank()) return el.attr("content").trim();
        }
        return null;
    }

    // ---------- values ----------

    private static ProductInfo summarize(String name, List<Offer> offers) {
        List<Offer> inStock = offers.stream().filter(o -> Boolean.TRUE.equals(o.available())).toList();
        Offer cheapest = (inStock.isEmpty() ? offers : inStock).stream()
                .min((a, b) -> a.price().compareTo(b.price())).orElseThrow();
        Boolean available = !inStock.isEmpty() ? Boolean.TRUE
                : offers.stream().allMatch(o -> Boolean.FALSE.equals(o.available())) ? Boolean.FALSE : null;
        String currency = cheapest.currency() == null ? "EUR" : cheapest.currency().toUpperCase(Locale.ROOT);
        return new ProductInfo(name.trim(), cheapest.price(), currency, available);
    }

    /** "https://schema.org/InStock", "instock", "in stock" -> true; out of stock -> false; unknown -> null. */
    static Boolean availability(String value) {
        if (value == null) return null;
        String key = localName(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (IN_STOCK.contains(key)) return Boolean.TRUE;
        if (OUT_OF_STOCK.contains(key)) return Boolean.FALSE;
        return null;
    }

    private static BigDecimal amount(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.isNumber() ? positive(node.decimalValue()) : amount(node.asString());
    }

    /** 70.19, "70.19", "70,19", "1.234,56", "1,234.56", "12 €" -> the amount; anything else -> null. */
    static BigDecimal amount(String text) {
        if (text == null) return null;
        String t = text.replaceAll("[\\s\\u00a0\\u202f€$£]|EUR", "");
        if (t.isEmpty()) return null;
        int comma = t.lastIndexOf(','), dot = t.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            // the last separator is the decimal one, the other groups thousands
            t = comma > dot ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        } else if (comma >= 0) {
            t = t.replace(',', '.');
        }
        try {
            return positive(new BigDecimal(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String s = node.isString() ? node.asString() : node.isObject() ? text(node.get("name")) : node.asString();
        return s == null || s.isBlank() ? null : s.trim();
    }
}
