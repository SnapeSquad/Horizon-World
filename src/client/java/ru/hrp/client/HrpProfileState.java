package ru.hrp.client;

import ru.hrp.core.network.HrpNetwork;

public final class HrpProfileState {
    private static HrpNetwork.ProfileResponse profile;

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
}
