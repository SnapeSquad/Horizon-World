package ru.hrp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class HrpSalaryConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void missingFileCreatesDocumentedDefaults() throws Exception {
        Path configPath = tempDir.resolve("config").resolve("hrpcore-salary.properties");

        HrpSalaryConfig config = HrpSalaryConfig.load(configPath);

        assertTrue(config.enabled());
        assertEquals(60, config.intervalMinutes());
        assertEquals(50_000L, config.salaryCents(HrpRole.CITIZEN));
        assertEquals(150_000L, config.salaryCents(HrpRole.POLICE));
        assertEquals(125_000L, config.salaryCents(HrpRole.MEDIC));
        assertEquals(100_000L, config.salaryCents(HrpRole.GOVERNMENT));
        assertEquals(100_000L, config.salaryCents(HrpRole.BANKER));
        assertTrue(Files.exists(configPath));
    }

    @Test
    void validConfigSupportsCentsZeroSalaryAndDisabledPayments() throws Exception {
        Path configPath = tempDir.resolve("hrpcore-salary.properties");
        Files.writeString(configPath, """
                enabled=false
                interval-minutes=1
                salary.CITIZEN=1.25
                salary.POLICE=0.00
                """);

        HrpSalaryConfig config = HrpSalaryConfig.load(configPath);

        assertFalse(config.enabled());
        assertEquals(1, config.intervalMinutes());
        assertEquals(125L, config.salaryCents(HrpRole.CITIZEN));
        assertEquals(0L, config.salaryCents(HrpRole.POLICE));
        assertEquals(125_000L, config.salaryCents(HrpRole.MEDIC));
    }

    @Test
    void invalidIntervalAndMoneyValuesFallBackToSafeDefaults() throws Exception {
        Path configPath = tempDir.resolve("hrpcore-salary.properties");
        Files.writeString(configPath, """
                interval-minutes=0
                salary.CITIZEN=-1.00
                salary.MEDIC=1.234
                salary.BANKER=100000001.00
                """);

        HrpSalaryConfig config = HrpSalaryConfig.load(configPath);

        assertEquals(60, config.intervalMinutes());
        assertEquals(50_000L, config.salaryCents(HrpRole.CITIZEN));
        assertEquals(125_000L, config.salaryCents(HrpRole.MEDIC));
        assertEquals(100_000L, config.salaryCents(HrpRole.BANKER));

        Properties rewritten = new Properties();
        try (var input = Files.newInputStream(configPath)) {
            rewritten.load(input);
        }
        assertEquals("60", rewritten.getProperty("interval-minutes"));
        assertEquals("500.00", rewritten.getProperty("salary.CITIZEN"));
    }
}
