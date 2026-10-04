package com.misfinanzas.service.prices;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Downloads a shop page the way a browser would ask for it, once. Only public internet addresses: the URL comes
 * from the user, and the app must not become a way to reach the PC itself or the home network (checked again
 * on every redirect, which is why redirects are followed here rather than by the HTTP client).
 */
@Component
public class PageFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    // What anti-bot walls answer instead of the page (Cloudflare, Akamai, PerimeterX, Imperva)
    private static final List<String> BOT_WALL_MARKERS = List.of("<title>Just a moment", "cf-challenge", "bm-verify=",
            "px-captcha", "_Incapsula_Resource");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Page(URI url, String html) {
    }

    /** @throws PriceReadException with a message for the user when the page can't be had */
    public Page fetch(URI url) {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublicWebAddress(current);
            HttpResponse<InputStream> response = send(current);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                closeQuietly(response.body());
                if (location == null) throw new PriceReadException("La tienda ha redirigido a ninguna parte (HTTP " + status + ").");
                current = current.resolve(location.replace(" ", "%20"));
                continue;
            }
            String html = read(response);
            if (isBotWall(status, html)) {
                throw new PriceReadException("Esta tienda no deja que un programa lea sus páginas (tiene protección "
                        + "anti-robots), así que no se puede seguir desde aquí: usa sus propios avisos.");
            }
            if (status == 404 || status == 410) throw new PriceReadException("La tienda dice que esa página no existe (ya no está a la venta o el enlace está mal).");
            if (status >= 400) throw new PriceReadException("La tienda ha respondido con un error (HTTP " + status + "). Se volverá a intentar más tarde.");
            return new Page(current, html);
        }
        throw new PriceReadException("La tienda redirige demasiadas veces.");
    }

    static boolean isBotWall(int status, String html) {
        if (BOT_WALL_MARKERS.stream().anyMatch(html::contains)) return true;
        return status == 403 || status == 429;
    }

    private HttpResponse<InputStream> send(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "es-ES,es;q=0.9")
                // Like any browser, accept compressed pages; only what the JDK can decompress (no brotli)
                .header("Accept-Encoding", "gzip, deflate")
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new PriceReadException("No se ha podido conectar con la tienda (" + e.getClass().getSimpleName()
                    + "). ¿Hay internet? Se volverá a intentar más tarde.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceReadException("Se ha interrumpido la consulta a la tienda.");
        }
    }

    private static String read(HttpResponse<InputStream> response) {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").trim().toLowerCase(Locale.ROOT);
        try (InputStream body = decompressed(response.body(), encoding)) {
            byte[] bytes = body.readNBytes(MAX_BYTES);
            return new String(bytes, charsetOf(response.headers().firstValue("Content-Type").orElse("")));
        } catch (IOException e) {
            throw new PriceReadException("La conexión con la tienda se ha cortado. Se volverá a intentar más tarde.");
        }
    }

    static InputStream decompressed(InputStream body, String encoding) throws IOException {
        return switch (encoding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(body);
            case "deflate" -> new InflaterInputStream(body);
            default -> body;
        };
    }

    private static Charset charsetOf(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        try {
            return m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** http(s) to a host that resolves only to public addresses; anything else is refused before connecting. */
    static void requirePublicWebAddress(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || url.getHost() == null) {
            throw new PriceReadException("Eso no parece el enlace de una tienda: tiene que empezar por https://");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException e) {
            throw new PriceReadException("No existe ninguna web llamada «" + url.getHost() + "». ¿Está bien copiado el enlace?");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new PriceReadException("Solo se pueden seguir tiendas de internet, no direcciones de tu red o de este PC.");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 0 || first >= 240) return false;
            if (first == 100 && second >= 64 && second <= 127) return false; // carrier-grade NAT
        }
        if (a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc) return false; // unique local fc00::/7
        return true;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
