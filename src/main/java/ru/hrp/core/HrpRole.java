package ru.hrp.core;

import java.util.Arrays;
import java.util.Locale;

/** Strongly typed player roles for HRPCore. */
public enum HrpRole {
    CITIZEN("CITIZEN", "Гражданин"),
    POLICE("POLICE", "Полицейский"),
    MEDIC("MEDIC", "Медик"),
    GOVERNMENT("GOVERNMENT", "Госслужащий"),
    BANKER("BANKER", "Банкир");

    private final String id;
    private final String russianName;

    HrpRole(String id, String russianName) {
        this.id = id;
        this.russianName = russianName;
    }

    public String getId() {
        return id;
    }

    public String getRussianName() {
        return russianName;
    }

    public String getFormatted() {
        return russianName + " (" + id + ")";
    }

    /**
     * Parses a string representation of a role.
     * Case-insensitive matching against internal ID or Russian name.
     * Returns null if no matching role is found.
     */
    public static HrpRole parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String clean = input.trim();
        for (HrpRole role : values()) {
            if (role.id.equalsIgnoreCase(clean) || role.russianName.equalsIgnoreCase(clean)) {
                return role;
            }
        }
        return null;
    }

    /** Returns valid role identifiers for suggestions. */
    public static String[] getRoleIds() {
        return Arrays.stream(values())
                .map(HrpRole::getId)
                .toArray(String[]::new);
    }
}
