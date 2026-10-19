package ru.hrp.client;

import ru.hrp.core.network.HrpNetwork;

/** Small client-only cache of server-authoritative profile data and save feedback. */
public final class HrpProfileState {
    private static HrpNetwork.ProfileResponse profile;
    private static boolean updateSuccess;
    private static String updateMessage = "";

    private HrpProfileState() {}

    public static void set(HrpNetwork.ProfileResponse value) {
        profile = value;
    }

    public static HrpNetwork.ProfileResponse get() {
        return profile;
    }

    public static void clear() {
        profile = null;
    }

    public static void setUpdateResult(boolean success, String message) {
        updateSuccess = success;
        updateMessage = message == null ? "" : message;
    }

    public static boolean isUpdateSuccess() {
        return updateSuccess;
    }

    public static String getUpdateMessage() {
        return updateMessage;
    }

    public static void clearUpdateResult() {
        updateSuccess = false;
        updateMessage = "";
    }
}
