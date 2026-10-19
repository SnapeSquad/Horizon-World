package ru.hrp.client;

import ru.hrp.core.network.HrpNetwork;

public final class HrpFinanceState {
    private static HrpNetwork.FinanceResponse snapshot;
    private static String message = "";
    private static boolean success;
    private static boolean pending;

    private HrpFinanceState() {}

    public static HrpNetwork.FinanceResponse getSnapshot() {
        return snapshot;
    }

    public static String getMessage() {
        return message;
    }

    public static boolean isSuccess() {
        return success;
    }

    public static boolean isPending() {
        return pending;
    }

    public static void setPending(String value) {
        message = value == null ? "" : value;
        success = false;
        pending = true;
    }

    public static void setResponse(HrpNetwork.FinanceResponse response) {
        snapshot = response;
        message = response.message();
        success = response.success();
        pending = false;
    }

    public static void setLocalError(String value) {
        message = value == null ? "" : value;
        success = false;
        pending = false;
    }
}
