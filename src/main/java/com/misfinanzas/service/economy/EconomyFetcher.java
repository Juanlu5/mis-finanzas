package com.misfinanzas.service.economy;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Downloads the JSON of the Economy view's public sources (INE, the Ministry's fuel prices, Red Eléctrica). Unlike
 * {@code PageFetcher}, the addresses are the app's own, never the user's, so no address checks are needed. The
 * Ministry's national list is ~12 MB, hence the generous limits.
 *
 * <p>The Ministry's fuel server only speaks an old kind of TLS (RSA key exchange, "TLS_RSA_*", no forward
 * secrecy) that Java 21 has switched off in {@code jdk.tls.disabledAlgorithms} for every connection; it hangs up
 * on Java's handshake. Rather than switch it back on for the whole app (the bank connection included), that one
 * source is read with the system's {@code curl} ({@link #getWithCurl}), whose OpenSSL still accepts it.
 */
@Component
public class EconomyFetcher {

    private static final int MAX_BYTES = 40 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** @throws EconomyFetchException with a message for the user when the source can't be read */
    public String get(URI url) {
        return get(url, "application/json");
    }

    /** {@code accept}: some sources go by it rather than by the address (the ECB answers JSON to a CSV query that asks for JSON). */
    public String get(URI url, String accept) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip, deflate")
                .header("User-Agent", "MisFinanzas/1.0 (uso personal)")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new EconomyFetchException("No se ha podido conectar con " + url.getHost() + " (" + e.getClass().getSimpleName() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EconomyFetchException("Se ha interrumpido la descarga de " + url.getHost() + ".");
        }
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").trim().toLowerCase(Locale.ROOT);
        String body;
        try (InputStream in = switch (encoding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(response.body());
            case "deflate" -> new InflaterInputStream(response.body());
            default -> response.body();
        }) {
            body = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EconomyFetchException("La descarga de " + url.getHost() + " se ha cortado.");
        }
        if (response.statusCode() >= 400) {
            throw new EconomyFetchException(url.getHost() + " ha respondido con un error (HTTP " + response.statusCode() + ").");
        }
        return body;
    }

    /** The same through {@code curl}, for the one source Java can't talk to (see the class comment). */
    public String getWithCurl(URI url) {
        Process process;
        try {
            process = new ProcessBuilder(List.of("curl", "--silent", "--show-error", "--fail", "--location", "--compressed",
                    "--max-time", String.valueOf(TIMEOUT.toSeconds()), "--header", "Accept: application/json",
                    "--user-agent", "MisFinanzas/1.0 (uso personal)", url.toString())).start();
        } catch (IOException e) {
            throw new EconomyFetchException("Para leer los precios de los carburantes hace falta el programa curl, y no está instalado.");
        }
        try {
            byte[] body = process.getInputStream().readNBytes(MAX_BYTES);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new EconomyFetchException("La descarga de " + url.getHost() + " ha tardado demasiado.");
            }
            if (process.exitValue() != 0) {
                throw new EconomyFetchException("No se ha podido descargar de " + url.getHost()
                        + (error.isEmpty() ? "" : " (" + error.replaceFirst("^curl: \\(\\d+\\) ", "") + ")") + ".");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new EconomyFetchException("La descarga de " + url.getHost() + " se ha cortado.");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new EconomyFetchException("Se ha interrumpido la descarga de " + url.getHost() + ".");
        }
    }
}
