package com.misfinanzas.startup;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves the app over HTTPS with a self-signed certificate, generated into data/tls/ on the first run. The app
 * is reached from the whole home WiFi (a phone, another laptop) with HTTP Basic, which over plain http sends
 * the password readable on every request; and Enable Banking only accepts https redirect URLs in production.
 * Browsers warn about a self-signed certificate once per device, which is the price of not depending on a
 * domain or a certificate authority.
 *
 * <p>An EnvironmentPostProcessor (like DbBackupEnvironmentPostProcessor) because the server.ssl.* properties
 * have to be in place before the web server is created. The certificate is made with the JDK's own
 * {@code keytool} rather than a crypto library: the JDK has no public API to build an X.509 certificate, and
 * this runs once per install, not per request. If anything fails the app still starts, over plain http, and
 * says so in the log — same rule as the backup: better to run than not to run.
 */
public class TlsKeystoreEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String ALIAS = "mis-finanzas";
    // Not a secret worth protecting: whoever can read data/tls/keystore.p12 can read the password next to it
    // just as easily. What protects the key is the file's permissions (owner only), same as the Enable Banking
    // private key in data/enablebanking/.
    static final String PASSWORD = "mis-finanzas";
    private static final int VALIDITY_DAYS = 3650;

    private final Log log;

    public TlsKeystoreEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("app.https.enabled", Boolean.class, true)) {
            return;
        }
        Path keystore = Paths.get(environment.getProperty("app.data-dir", "./data"), "tls", "keystore.p12");
        try {
            if (!Files.exists(keystore)) {
                generate(keystore);
                log.info("Generado un certificado autofirmado para HTTPS en " + keystore
                        + ". El navegador avisará de él una vez por dispositivo.");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("No se pudo generar el certificado para HTTPS, la app arranca sin cifrar (http): " + e.getMessage(), e);
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource("misFinanzasTls", Map.of(
                "server.ssl.enabled", "true",
                "server.ssl.key-store", keystore.toAbsolutePath().toString(),
                "server.ssl.key-store-type", "PKCS12",
                "server.ssl.key-store-password", PASSWORD,
                "server.ssl.key-alias", ALIAS)));
    }

    private void generate(Path keystore) throws IOException, InterruptedException {
        Files.createDirectories(keystore.getParent());
        Path keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = List.of(keytool.toString(), "-genkeypair",
                "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", String.valueOf(VALIDITY_DAYS),
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-dname", "CN=Mis Finanzas",
                "-ext", "SAN=" + subjectAlternativeNames());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("keytool no terminó en 60 s");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || !Files.exists(keystore)) {
            Files.deleteIfExists(keystore);
            throw new IOException("keytool falló (" + process.exitValue() + "): " + output);
        }
        try {
            Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: no POSIX permissions; the file stays under the user's own profile folder anyway.
        }
    }

    /**
     * The names the certificate is valid for: this machine by name and loopback, plus its current LAN
     * addresses so a phone on the WiFi gets "self-signed" as its only complaint, not a name mismatch too. The
     * LAN address can change later (DHCP); the phone then warns about the name as well, still one click away.
     * Delete data/tls/ to have it regenerated with the new address.
     */
    static String subjectAlternativeNames() {
        Set<String> names = new LinkedHashSet<>(List.of("dns:localhost", "ip:127.0.0.1"));
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nic : interfaces) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        names.add("ip:" + address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // Loopback names alone still work on this machine.
        }
        return String.join(",", new ArrayList<>(names));
    }
}
