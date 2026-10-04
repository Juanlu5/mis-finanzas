package com.misfinanzas.startup;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshots data/finanzas.db into data/backups/ on every startup, before
 * Hibernate's schema update (or any migration) gets a chance to touch it.
 * Registered via META-INF/spring.factories so it runs before the DataSource
 * bean is even created — cheap insurance against a bad update, since
 * restoring is just copying a file back.
 *
 * <p>Old copies are thinned out by age rather than by count (see {@link #toKeep}): keeping just the N newest
 * meant a few days of restarts pushed out everything older (30 copies covered only 6 days once).
 */
public class DbBackupEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final int DEFAULT_KEEP_NEWEST = 10;
    static final int DAILY_DAYS = 14;
    static final int WEEKLY_WEEKS = 8;
    static final int MONTHLY_MONTHS = 12;
    private static final Pattern BACKUP_NAME = Pattern.compile("finanzas_(\\d{8})_\\d{6}\\.db");

    // This runs before Spring's own logging system is configured (it's what decides where "logs/app.log"
    // even points), so a plain SLF4J logger here would only ever reach the console, never the log file the
    // rest of the app writes to. DeferredLogFactory is Spring Boot's supported way around that: it hands
    // out a Log that buffers what's logged now and replays it once the real logging system is up.
    private final Log log;

    public DbBackupEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            String dataDir = environment.getProperty("app.data-dir", "./data");
            // The sqlite-jdbc driver refuses to connect if this directory doesn't
            // already exist (unlike the old Python app's os.makedirs at startup).
            Files.createDirectories(Paths.get(dataDir));
            Path dbPath = Paths.get(dataDir, "finanzas.db");
            if (!Files.exists(dbPath)) {
                return;
            }
            Path backupDir = Paths.get(dataDir, "backups");
            Files.createDirectories(backupDir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path dest = backupDir.resolve("finanzas_" + stamp + ".db");
            if (!Files.exists(dest)) {
                Files.copy(dbPath, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
            pruneOldBackups(backupDir, Math.max(1, environment.getProperty("app.backups-to-keep", Integer.class, DEFAULT_KEEP_NEWEST)));
        } catch (IOException e) {
            log.warn("No se pudo hacer copia de seguridad de la base de datos, se continúa sin ella: " + e.getMessage(), e);
        }
    }

    private void pruneOldBackups(Path backupDir, int keepNewest) throws IOException {
        List<String> backups;
        try (Stream<Path> stream = Files.list(backupDir)) {
            backups = stream.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("finanzas_.*\\.db"))
                    .toList();
        }
        Set<String> keep = toKeep(backups, LocalDate.now(), keepNewest);
        for (String name : backups) {
            if (!keep.contains(name)) Files.deleteIfExists(backupDir.resolve(name));
        }
    }

    /**
     * Which backups to keep: the {@code keepNewest} most recent, whatever their age (several restarts in a row
     * while trying something), then the newest of each day for the last {@value #DAILY_DAYS} days, of each week
     * for the last {@value #WEEKLY_WEEKS} weeks and of each month for the last {@value #MONTHLY_MONTHS} months,
     * so a year is covered with a few dozen files. The date comes from the file name (the copy keeps the
     * database's own modification time); one whose name can't be read as a date is kept rather than guessed at.
     */
    static Set<String> toKeep(List<String> backups, LocalDate today, int keepNewest) {
        List<String> newestFirst = backups.stream().sorted(Comparator.reverseOrder()).toList();
        Set<String> keep = new HashSet<>(newestFirst.subList(0, Math.min(keepNewest, newestFirst.size())));
        LocalDate firstDay = today.minusDays(DAILY_DAYS - 1);
        LocalDate firstWeek = today.with(DayOfWeek.MONDAY).minusWeeks(WEEKLY_WEEKS - 1);
        YearMonth firstMonth = YearMonth.from(today).minusMonths(MONTHLY_MONTHS - 1);
        Set<Object> periodsSeen = new HashSet<>();
        for (String name : newestFirst) {
            LocalDate date = dateOf(name);
            if (date == null) {
                keep.add(name);
                continue;
            }
            // newest first, so the first copy seen for a period is that period's newest
            if (!date.isBefore(firstDay) && periodsSeen.add(date)) keep.add(name);
            LocalDate week = date.with(DayOfWeek.MONDAY);
            if (!week.isBefore(firstWeek) && periodsSeen.add("week " + week)) keep.add(name);
            YearMonth month = YearMonth.from(date);
            if (!month.isBefore(firstMonth) && periodsSeen.add(month)) keep.add(name);
        }
        return keep;
    }

    private static LocalDate dateOf(String name) {
        Matcher m = BACKUP_NAME.matcher(name);
        if (!m.matches()) return null;
        try {
            return LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
