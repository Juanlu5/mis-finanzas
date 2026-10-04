package com.misfinanzas.startup;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DbBackupEnvironmentPostProcessorTest {

    private final DbBackupEnvironmentPostProcessor processor = new DbBackupEnvironmentPostProcessor(Supplier::get);

    @TempDir
    Path tmp;

    private void run(MockEnvironment env) {
        processor.postProcessEnvironment(env, null);
    }

    private MockEnvironment env(Path dataDir) {
        return new MockEnvironment().withProperty("app.data-dir", dataDir.toString());
    }

    private List<String> backupNames(Path dataDir) throws IOException {
        Path backups = dataDir.resolve("backups");
        if (!Files.exists(backups)) return List.of();
        try (Stream<Path> files = Files.list(backups)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void createsTheDataDirectoryWhenMissing() {
        Path dataDir = tmp.resolve("nuevo/data");

        run(env(dataDir));

        // sqlite-jdbc refuses to connect if this directory doesn't exist
        assertThat(dataDir).isDirectory();
    }

    @Test
    void doesNothingElseWhenThereIsNoDatabaseYet() throws IOException {
        run(env(tmp));

        assertThat(backupNames(tmp)).isEmpty();
    }

    @Test
    void copiesTheDatabaseIntoBackups() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "contenido de la base de datos");

        run(env(tmp));

        List<String> names = backupNames(tmp);
        assertThat(names).hasSize(1);
        assertThat(names.get(0)).matches("finanzas_\\d{8}_\\d{6}\\.db");
        assertThat(Files.readString(tmp.resolve("backups").resolve(names.get(0)))).isEqualTo("contenido de la base de datos");
        assertThat(Files.readString(tmp.resolve("finanzas.db"))).isEqualTo("contenido de la base de datos"); // original untouched
    }

    // Dated years back, so only the "newest N" rule can keep them, whatever day the tests run.
    private void createOldBackups(int count) throws IOException {
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        for (int i = 1; i <= count; i++) {
            Files.writeString(backups.resolve("finanzas_201901%02d_000000.db".formatted(i)), "old " + i);
        }
    }

    @Test
    void keepsTheTenNewestByDefaultWhateverTheirAge() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        createOldBackups(15);

        run(env(tmp));

        List<String> names = backupNames(tmp);
        assertThat(names).hasSize(10);                                   // the new one + the 9 newest old ones
        assertThat(names).doesNotContain("finanzas_20190106_000000.db").contains("finanzas_20190107_000000.db");
    }

    @Test
    void honoursTheConfiguredNumberOfNewestToKeep() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        createOldBackups(10);

        run(env(tmp).withProperty("app.backups-to-keep", "3"));

        assertThat(backupNames(tmp)).hasSize(3);
    }

    @Test
    void neverPrunesTheBackupJustTaken() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        createOldBackups(5);

        run(env(tmp).withProperty("app.backups-to-keep", "0"));

        assertThat(backupNames(tmp)).hasSize(1);
        assertThat(backupNames(tmp).get(0)).isGreaterThan("finanzas_20190131_000000.db");
    }

    @Test
    void olderCopiesAreThinnedOutByAgeNotDeletedByCount() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        // one restart a day for the last 40 days: keeping the 30 newest would have deleted the oldest ten
        LocalDate today = LocalDate.now();
        for (int i = 1; i <= 40; i++) Files.writeString(backups.resolve(backup(today.minusDays(i), "120000")), "");

        run(env(tmp));

        List<String> names = backupNames(tmp);
        assertThat(names.get(0)).isLessThanOrEqualTo(backup(today.minusDays(31), "120000")); // over a month back
        assertThat(names).contains(backup(today.minusDays(1), "120000"), backup(today.minusDays(13), "120000"));
        assertThat(names.size()).isLessThan(30);
    }

    private static String backup(LocalDate date, String time) {
        return "finanzas_%s_%s.db".formatted(date.format(BASIC_ISO_DATE), time);
    }

    // ---------- the rotation rule, on a fixed day ----------

    private static final LocalDate THURSDAY = LocalDate.parse("2026-09-24");

    @Test
    void theNewestOfEachDayIsKeptForTwoWeeksThenOfEachWeek() {
        // three restarts a day for the last 20 days
        List<String> backups = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            for (String time : List.of("090000", "130000", "220000")) backups.add(backup(THURSDAY.minusDays(i), time));
        }

        Set<String> keep = DbBackupEnvironmentPostProcessor.toKeep(backups, THURSDAY, 1);

        // 14 days (11-24 Sep), each by its last copy of the day...
        for (int i = 0; i < 14; i++) assertThat(keep).contains(backup(THURSDAY.minusDays(i), "220000"));
        // ...then the newest of each week: for the week of Mon 7 Sep that's Sun 13 (already kept as a day), for
        // the week of Mon 31 Aug it's Sun 6 Sep; the rest of the week before the two weeks goes
        assertThat(keep).contains(backup(LocalDate.parse("2026-09-06"), "220000"))
                .doesNotContain(backup(LocalDate.parse("2026-09-10"), "220000"), backup(LocalDate.parse("2026-09-05"), "220000"));
        assertThat(keep).hasSize(15);
    }

    @Test
    void theNewestOfEachMonthIsKeptForAYear() {
        List<String> backups = new ArrayList<>();
        for (int i = 0; i < 18; i++) backups.add(backup(THURSDAY.withDayOfMonth(15).minusMonths(i), "120000"));

        Set<String> keep = DbBackupEnvironmentPostProcessor.toKeep(backups, THURSDAY, 1);

        assertThat(keep).hasSize(12)
                .contains(backup(LocalDate.parse("2025-10-15"), "120000"))
                .doesNotContain(backup(LocalDate.parse("2025-09-15"), "120000"));
    }

    @Test
    void theNewestFewAreKeptWhateverTheirAge() {
        List<String> backups = List.of(backup(LocalDate.parse("2019-01-01"), "000000"), backup(LocalDate.parse("2019-01-02"), "000000"));

        assertThat(DbBackupEnvironmentPostProcessor.toKeep(backups, THURSDAY, 1))
                .containsExactly(backup(LocalDate.parse("2019-01-02"), "000000"));
    }

    @Test
    void aBackupWhoseNameIsNotADateIsKept() {
        List<String> backups = List.of("finanzas_raro.db", "finanzas_20261340_000000.db", backup(LocalDate.parse("2019-01-01"), "000000"),
                backup(LocalDate.parse("2019-01-02"), "000000"));

        assertThat(DbBackupEnvironmentPostProcessor.toKeep(backups, THURSDAY, 1))
                .contains("finanzas_raro.db", "finanzas_20261340_000000.db")
                .doesNotContain(backup(LocalDate.parse("2019-01-01"), "000000"));
    }

    @Test
    void leavesUnrelatedFilesInTheBackupsFolderAlone() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(backups.resolve("notas.txt"), "no me borres");
        Files.writeString(backups.resolve("otro_20200101.db"), "ni a mí");
        IntStream.rangeClosed(1, 5).forEach(i -> {
            try {
                Files.writeString(backups.resolve("finanzas_201901%02d_000000.db".formatted(i)), "old");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        run(env(tmp).withProperty("app.backups-to-keep", "2"));

        assertThat(backups.resolve("notas.txt")).exists();
        assertThat(backups.resolve("otro_20200101.db")).exists();
        assertThat(backupNames(tmp).stream().filter(n -> n.startsWith("finanzas_"))).hasSize(2);
    }

    // A failed backup must never stop the app from starting (better to run without one than not run at all),
    // but it used to fail silently to stderr, easy to miss once nobody is watching the console anymore.
    @Test
    void aFailedBackupDoesNotStopStartupAndIsLogged() throws IOException {
        Files.writeString(tmp.resolve("finanzas.db"), "db");
        // A plain file named "backups" makes Files.createDirectories(backupDir) throw IOException.
        Files.writeString(tmp.resolve("backups"), "esto no es un directorio");
        Log log = mock(Log.class);
        DbBackupEnvironmentPostProcessor processorWithLog = new DbBackupEnvironmentPostProcessor(s -> log);

        processorWithLog.postProcessEnvironment(env(tmp), null);

        verify(log).warn(anyString(), any(IOException.class));
        assertThat(Files.readString(tmp.resolve("finanzas.db"))).isEqualTo("db");
    }
}
