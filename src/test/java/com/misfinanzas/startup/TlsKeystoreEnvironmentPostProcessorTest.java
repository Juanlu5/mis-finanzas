package com.misfinanzas.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class TlsKeystoreEnvironmentPostProcessorTest {

    @TempDir
    Path dataDir;

    private final TlsKeystoreEnvironmentPostProcessor processor = new TlsKeystoreEnvironmentPostProcessor(Supplier::get);

    private MockEnvironment run() {
        MockEnvironment env = new MockEnvironment().withProperty("app.data-dir", dataDir.toString());
        processor.postProcessEnvironment(env, null);
        return env;
    }

    private Path keystore() {
        return dataDir.resolve("tls").resolve("keystore.p12");
    }

    @Test
    void firstRunGeneratesAKeystoreAndTurnsSslOn() throws Exception {
        MockEnvironment env = run();

        assertThat(keystore()).exists();
        assertThat(env.getProperty("server.ssl.enabled")).isEqualTo("true");
        assertThat(env.getProperty("server.ssl.key-store")).isEqualTo(keystore().toAbsolutePath().toString());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore())) {
            store.load(in, env.getProperty("server.ssl.key-store-password").toCharArray());
        }
        X509Certificate cert = (X509Certificate) store.getCertificate(env.getProperty("server.ssl.key-alias"));
        assertThat(cert.getSubjectAlternativeNames())
                .extracting(name -> (Object) name.get(1))
                .contains((Object) "localhost", "127.0.0.1");
    }

    @Test
    void theKeystoreIsReadableByItsOwnerOnly() throws Exception {
        run();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keystore()))).isEqualTo("rw-------");
    }

    @Test
    void laterRunsReuseTheSameCertificate() throws Exception {
        run();
        FileTime firstWrite = FileTime.fromMillis(0);
        Files.setLastModifiedTime(keystore(), firstWrite);

        MockEnvironment env = run();

        // Regenerating would make every browser and phone warn about the certificate all over again.
        assertThat(Files.getLastModifiedTime(keystore())).isEqualTo(firstWrite);
        assertThat(env.getProperty("server.ssl.enabled")).isEqualTo("true");
    }

    @Test
    void disabledMeansNoCertificateAndPlainHttp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.data-dir", dataDir.toString())
                .withProperty("app.https.enabled", "false");

        processor.postProcessEnvironment(env, null);

        assertThat(dataDir.resolve("tls")).doesNotExist();
        assertThat(env.getProperty("server.ssl.enabled")).isNull();
    }

    @Test
    void aFailureLeavesTheAppOnPlainHttpInsteadOfBlockingStartup() throws Exception {
        // A file where the tls/ folder should be: the keystore can't be created.
        Files.writeString(dataDir.resolve("tls"), "not a folder");

        MockEnvironment env = run();

        assertThat(env.getProperty("server.ssl.enabled")).isNull();
    }

    @Test
    void subjectAlternativeNamesAlwaysIncludeLoopback() {
        assertThat(List.of(TlsKeystoreEnvironmentPostProcessor.subjectAlternativeNames().split(",")))
                .startsWith("dns:localhost", "ip:127.0.0.1");
    }
}
