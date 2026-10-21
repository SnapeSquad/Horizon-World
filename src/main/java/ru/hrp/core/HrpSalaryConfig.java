package ru.hrp.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/** Loads server-side role salaries from config/hrpcore-salary.properties. */
public final class HrpSalaryConfig {
    private static final long MAX_SALARY_CENTS = 10_000_000_000L;
    private static final int DEFAULT_INTERVAL_MINUTES = 60;

    private final boolean enabled;
    private final int intervalMinutes;
    private final Map<HrpRole, Long> salariesCents;

    private HrpSalaryConfig(boolean enabled, int intervalMinutes, Map<HrpRole, Long> salariesCents) {
        this.enabled = enabled;
        this.intervalMinutes = intervalMinutes;
        this.salariesCents = Map.copyOf(salariesCents);
    }

    public boolean enabled() {
        return enabled;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public long intervalMillis() {
        return Math.multiplyExact((long) intervalMinutes, 60_000L);
    }

    public long salaryCents(HrpRole role) {
        return salariesCents.getOrDefault(role, 0L);
    }

    public static HrpSalaryConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("hrpcore-salary.properties");
        return load(path);
    }

    static HrpSalaryConfig load(Path path) {
        if (path == null) throw new IllegalArgumentException("config path must not be null");
        Path configPath = path.toAbsolutePath();
        Properties properties = defaults();

        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                Properties loaded = new Properties();
                try (InputStream input = Files.newInputStream(configPath)) {
                    loaded.load(input);
                }
                for (String key : loaded.stringPropertyNames()) {
                    properties.setProperty(key, loaded.getProperty(key));
                }
            }
        } catch (IOException exception) {
            HRPCore.LOGGER.error("Could not read salary config '{}'; using defaults.", configPath, exception);
        }

        boolean enabled = parseEnabled(properties.getProperty("enabled", "true"));
        int interval = parseInterval(properties.getProperty("interval-minutes", "60"));
        Map<HrpRole, Long> salaries = new EnumMap<>(HrpRole.class);
        for (HrpRole role : HrpRole.values()) {
            String key = "salary." + role.getId();
            salaries.put(role, parseSalary(properties.getProperty(key), defaultSalaryCents(role), key));
        }

        HrpSalaryConfig config = new HrpSalaryConfig(enabled, interval, salaries);
        Properties outputProperties = new Properties();
        outputProperties.setProperty("enabled", Boolean.toString(config.enabled));
        outputProperties.setProperty("interval-minutes", Integer.toString(config.intervalMinutes));
        for (HrpRole role : HrpRole.values()) {
            outputProperties.setProperty("salary." + role.getId(), formatRubles(config.salaryCents(role)));
        }

        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream output = Files.newOutputStream(configPath)) {
                outputProperties.store(output, "HRPCore salaries. Amounts are in rubles; changes apply after server restart.");
            }
        } catch (IOException exception) {
            HRPCore.LOGGER.error("Could not write salary config '{}'.", configPath, exception);
        }

        HRPCore.LOGGER.info("HRPCore salaries: enabled={}, interval={} minute(s)", enabled, interval);
        return config;
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("interval-minutes", Integer.toString(DEFAULT_INTERVAL_MINUTES));
        for (HrpRole role : HrpRole.values()) {
            properties.setProperty("salary." + role.getId(), formatRubles(defaultSalaryCents(role)));
        }
        return properties;
    }

    private static boolean parseEnabled(String raw) {
        if (raw == null) return true;
        if (raw.trim().equalsIgnoreCase("true")) return true;
        if (raw.trim().equalsIgnoreCase("false")) return false;
        HRPCore.LOGGER.warn("Invalid salary config enabled='{}'; using true.", raw);
        return true;
    }

    private static long defaultSalaryCents(HrpRole role) {
        return switch (role) {
            case CITIZEN -> 50_000L;
            case POLICE -> 150_000L;
            case MEDIC -> 125_000L;
            case GOVERNMENT -> 100_000L;
            case BANKER -> 100_000L;
        };
    }

    private static int parseInterval(String raw) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            if (value >= 1 && value <= 10_080) return value;
        } catch (NumberFormatException ignored) {
            // Fall through to the safe default.
        }
        HRPCore.LOGGER.warn("Invalid salary interval '{}'; using {} minutes.", raw, DEFAULT_INTERVAL_MINUTES);
        return DEFAULT_INTERVAL_MINUTES;
    }

    private static long parseSalary(String raw, long fallback, String key) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            BigDecimal amount = new BigDecimal(raw.trim().replace(',', '.'))
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0) throw new IllegalArgumentException("Negative salary");
            long cents = amount.movePointRight(2).longValueExact();
            if (cents > MAX_SALARY_CENTS) throw new IllegalArgumentException("Salary exceeds limit");
            return cents;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            HRPCore.LOGGER.warn("Invalid salary value for '{}': '{}'; using default.", key, raw);
            return fallback;
        }
    }

    private static String formatRubles(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
    }
}
