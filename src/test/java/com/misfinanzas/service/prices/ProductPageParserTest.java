package com.misfinanzas.service.prices;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The page shapes below are trimmed copies of what shops really publish (checked 2026-09-25), including shops that
 * block the app's requests today (Zalando, idealo): the data format is worth understanding either way.
 */
class ProductPageParserTest {

    private static final String URL = "https://shop.example/p/1";

    private static String page(String head) {
        return "<!doctype html><html><head><title>Tienda</title>" + head + "</head><body><h1>Visible</h1></body></html>";
    }

    private static String jsonLd(String json) {
        return "<script type=\"application/ld+json\">" + json + "</script>";
    }

    private static ProductPageParser.ProductInfo parse(String head) {
        return ProductPageParser.parse(page(head), URL);
    }

    @Test
    void zalandoProductGroupTakesTheCheapestSizeInStock() {
        var info = parse(jsonLd("""
                [{"@context":"https://schema.org/","@type":"ProductGroup","brand":{"@type":"Brand","name":"Reebok"},
                  "name":"Club C 85","hasVariant":[
                    {"@type":"Product","size":"41","offers":{"@type":"Offer","price":"70.19","priceCurrency":"EUR",
                      "availability":"http://schema.org/InStock"}},
                    {"@type":"Product","size":"43","offers":{"@type":"Offer","price":"65.00","priceCurrency":"EUR",
                      "availability":"http://schema.org/OutOfStock"}}]}]"""));

        assertThat(info.name()).isEqualTo("Club C 85");
        assertThat(info.price()).isEqualByComparingTo("70.19");
        assertThat(info.currency()).isEqualTo("EUR");
        assertThat(info.available()).isTrue();
    }

    @Test
    void idealoAggregateOfferGivesTheLowestPriceAcrossShops() {
        var info = parse(jsonLd("""
                {"@context":"http://schema.org","@type":"Product","name":"Kobo Clara BW",
                 "offers":{"@type":"AggregateOffer","lowPrice":149.99,"highPrice":220.07,"priceCurrency":"EUR",
                   "offerCount":5,"availability":"https://schema.org/InStock"}}"""));

        assertThat(info.name()).isEqualTo("Kobo Clara BW");
        assertThat(info.price()).isEqualByComparingTo("149.99");
        assertThat(info.available()).isTrue();
    }

    @Test
    void chollostyleSingleOfferWithAStringPrice() {
        var info = parse(jsonLd("""
                {"@context":"https://schema.org","@type":"Product","name":"Camiseta logo lavada",
                 "offers":{"@type":"Offer","priceCurrency":"EUR","price":"17.90","availability":"https://schema.org/InStock"}}"""));

        assertThat(info.price()).isEqualByComparingTo("17.90");
    }

    @Test
    void findsTheProductInsideAGraphWithATypeList() {
        var info = parse(jsonLd("""
                {"@context":"https://schema.org","@graph":[
                  {"@type":"WebSite","name":"Tienda"},
                  {"@type":["Product","Thing"],"name":"Mochila","offers":[
                    {"@type":"Offer","price":"39,95","priceCurrency":"eur"}]}]}"""));

        assertThat(info.name()).isEqualTo("Mochila");
        assertThat(info.price()).isEqualByComparingTo("39.95");
        assertThat(info.currency()).isEqualTo("EUR");
        assertThat(info.available()).isNull();
    }

    @Test
    void aBrokenBlockDoesNotHideAGoodOne() {
        var info = parse(jsonLd("{\"@type\": \"Product\", oops") + jsonLd("""
                {"@type":"Product","name":"Taza","offers":{"price":12,"priceCurrency":"EUR"}}"""));

        assertThat(info.name()).isEqualTo("Taza");
        assertThat(info.price()).isEqualByComparingTo("12");
    }

    @Test
    void everySizeSoldOutKeepsTheCheapestPriceAndSaysSo() {
        var info = parse(jsonLd("""
                {"@type":"ProductGroup","name":"Camisa","hasVariant":[
                  {"@type":"Product","offers":{"price":"29.95","availability":"OutOfStock"}},
                  {"@type":"Product","offers":{"price":"25.95","availability":"SoldOut"}}]}"""));

        assertThat(info.price()).isEqualByComparingTo("25.95");
        assertThat(info.available()).isFalse();
    }

    @Test
    void priceInsideAPriceSpecification() {
        var info = parse(jsonLd("""
                {"@type":"Product","name":"Lámpara","offers":{"@type":"Offer",
                 "priceSpecification":[{"@type":"UnitPriceSpecification","price":"59.99","priceCurrency":"EUR"}]}}"""));

        assertThat(info.price()).isEqualByComparingTo("59.99");
    }

    @Test
    void fallsBackToProductMetaTags() {
        var info = parse("""
                <meta property="og:title" content="Sudadera básica">
                <meta property="product:price:amount" content="1.299,00">
                <meta property="product:price:currency" content="EUR">
                <meta property="product:availability" content="in stock">""");

        assertThat(info.name()).isEqualTo("Sudadera básica");
        assertThat(info.price()).isEqualByComparingTo("1299.00");
        assertThat(info.available()).isTrue();
    }

    @Test
    void aPageWithoutAPriceIsExplained() {
        assertThatThrownBy(() -> parse(jsonLd("{\"@type\":\"Organization\",\"name\":\"Tienda\"}")))
                .isInstanceOf(PriceReadException.class)
                .hasMessageContaining("no dice el precio");
    }

    @Test
    void aZeroPriceIsNotAPrice() {
        assertThatThrownBy(() -> parse(jsonLd("{\"@type\":\"Product\",\"name\":\"X\",\"offers\":{\"price\":\"0.00\"}}")))
                .isInstanceOf(PriceReadException.class);
    }

    @Test
    void amountsAreReadWhicheverSeparatorTheShopUses() {
        assertThat(ProductPageParser.amount("70.19")).isEqualByComparingTo("70.19");
        assertThat(ProductPageParser.amount("70,19")).isEqualByComparingTo("70.19");
        assertThat(ProductPageParser.amount("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(ProductPageParser.amount("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(ProductPageParser.amount("12 €")).isEqualByComparingTo("12");
        assertThat(ProductPageParser.amount("EUR 9.99")).isEqualByComparingTo("9.99");
        assertThat(ProductPageParser.amount("gratis")).isNull();
        assertThat(ProductPageParser.amount("")).isNull();
        assertThat(ProductPageParser.amount("-5")).isNull();
    }

    @Test
    void availabilityValues() {
        assertThat(ProductPageParser.availability("https://schema.org/InStock")).isTrue();
        assertThat(ProductPageParser.availability("LimitedAvailability")).isTrue();
        assertThat(ProductPageParser.availability("out of stock")).isFalse();
        assertThat(ProductPageParser.availability("http://schema.org/Discontinued")).isFalse();
        assertThat(ProductPageParser.availability("whatever")).isNull();
        assertThat(ProductPageParser.availability(null)).isNull();
    }

    @Test
    void cheapestOfSeveralPlainOffers() {
        var info = parse(jsonLd("""
                {"@type":"Product","name":"Auriculares","offers":[
                  {"price":"89.00","availability":"InStock"},{"price":"79.00","availability":"InStock"},
                  {"price":"59.00","availability":"OutOfStock"}]}"""));

        assertThat(info.price()).isEqualByComparingTo(new BigDecimal("79.00"));
        assertThat(info.available()).isTrue();
    }
}
