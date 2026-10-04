package com.misfinanzas.service.prices;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFetcherTest {

    private static boolean isPublic(String literal) throws Exception {
        return PageFetcher.isPublic(InetAddress.getByName(literal));
    }

    @Test
    void onlyPublicInternetAddressesAreAllowed() throws Exception {
        assertThat(isPublic("8.8.8.8")).isTrue();
        assertThat(isPublic("2a00:1450:4003:80e::2004")).isTrue();

        assertThat(isPublic("127.0.0.1")).isFalse();
        assertThat(isPublic("192.168.1.10")).isFalse();
        assertThat(isPublic("10.0.0.1")).isFalse();
        assertThat(isPublic("172.16.5.4")).isFalse();
        assertThat(isPublic("169.254.169.254")).isFalse();
        assertThat(isPublic("100.64.0.1")).isFalse();
        assertThat(isPublic("0.0.0.0")).isFalse();
        assertThat(isPublic("::1")).isFalse();
        assertThat(isPublic("fd00::1")).isFalse();
        assertThat(isPublic("::ffff:192.168.1.1")).isFalse();
    }

    @Test
    void linksToThisPcOrTheHomeNetworkAreRefusedBeforeConnecting() {
        for (String link : new String[]{"https://127.0.0.1:8000/api/backup/download", "http://localhost/", "http://192.168.1.1/"}) {
            assertThatThrownBy(() -> PageFetcher.requirePublicWebAddress(URI.create(link)))
                    .isInstanceOf(PriceReadException.class)
                    .hasMessageContaining("no direcciones de tu red");
        }
    }

    @Test
    void onlyWebLinks() {
        assertThatThrownBy(() -> PageFetcher.requirePublicWebAddress(URI.create("file:///etc/passwd")))
                .isInstanceOf(PriceReadException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void recognisesAntiBotWalls() {
        assertThat(PageFetcher.isBotWall(403, "<html><head><title>Just a moment...</title>")).isTrue();
        assertThat(PageFetcher.isBotWall(200, "<meta http-equiv=\"refresh\" content=\"5; URL='/p?bm-verify=AAQ'\">")).isTrue();
        assertThat(PageFetcher.isBotWall(429, "Too many requests")).isTrue();
        assertThat(PageFetcher.isBotWall(200, "<html><title>Zapatillas</title>")).isFalse();
        assertThat(PageFetcher.isBotWall(404, "Not found")).isFalse();
    }

    @Test
    void compressedPagesAreDecompressed() throws Exception {
        String html = "<html><title>Zapatillas · 70,19 €</title></html>";
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(gzip)) { out.write(html.getBytes(StandardCharsets.UTF_8)); }
        ByteArrayOutputStream deflate = new ByteArrayOutputStream();
        try (var out = new DeflaterOutputStream(deflate)) { out.write(html.getBytes(StandardCharsets.UTF_8)); }

        assertThat(new String(PageFetcher.decompressed(new ByteArrayInputStream(gzip.toByteArray()), "gzip").readAllBytes(),
                StandardCharsets.UTF_8)).isEqualTo(html);
        assertThat(new String(PageFetcher.decompressed(new ByteArrayInputStream(deflate.toByteArray()), "deflate").readAllBytes(),
                StandardCharsets.UTF_8)).isEqualTo(html);
        assertThat(new String(PageFetcher.decompressed(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), "").readAllBytes(),
                StandardCharsets.UTF_8)).isEqualTo(html);
    }
}
