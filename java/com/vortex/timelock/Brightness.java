package com.vortex.timelock;

import android.content.Context;
import android.provider.Settings;

/**
 * Screen-brightness control for the kiosk lock screen.
 *
 * <p>The lock screen only ever runs while this app is the real Device Owner, so
 * the sanctioned write path is {@link android.app.admin.DevicePolicyManager#setSystemSetting},
 * whose whitelist is exactly the three settings we need: {@code SCREEN_BRIGHTNESS},
 * {@code SCREEN_BRIGHTNESS_MODE} and {@code SCREEN_OFF_TIMEOUT}. That means
 * brightness can be toggled and set from the locked screen with no runtime
 * permission, no {@code WRITE_SETTINGS} app-op and no Shizuku round-trip.
 *
 * <p>Reads go straight through {@link Settings.System}, which is readable by
 * anyone, so the dashboard can paint the current state as part of its ordinary
 * snapshot. Every call is defensive: a vendor HAL that refuses the write leaves
 * the screen exactly as it was instead of throwing into the render path.
 */
final class Brightness {

    /** {@link Settings.System#SCREEN_BRIGHTNESS} runs 0..255 on Android. */
    static final int MAX = 255;

    private Brightness() {}

    /** True when the panel is in adaptive (auto) brightness mode. */
    static boolean isAuto(Context c) {
        try {
            int mode = Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stored manual brightness (0..255), or -1 when it cannot be read. */
    static int level(Context c) {
        try {
            int v = Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            if (v < 0) return -1;
            return Math.min(MAX, v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Switch adaptive brightness on or off. Returns true when the write stuck. */
    static boolean setAuto(Context c, boolean auto) {
        return write(c, Settings.System.SCREEN_BRIGHTNESS_MODE,
                String.valueOf(auto
                        ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL));
    }

    /**
     * Pin a manual brightness level (0..255) and leave auto mode. The mode is
     * written first so the level actually takes effect on devices that ignore a
     * manual value while adaptive brightness is still on.
     */
    static boolean setLevel(Context c, int level) {
        int v = Math.max(1, Math.min(MAX, level));
        setAuto(c, false);
        return write(c, Settings.System.SCREEN_BRIGHTNESS, String.valueOf(v));
    }

    /** Brightness as a whole-percent read-out for the slider label. */
    static int percent(int level) {
        if (level < 0) return -1;
        return Math.round(Math.min(MAX, level) * 100f / MAX);
    }

    /**
     * Write one Settings.System key. Device-Owner path first; the plain
     * {@link Settings.System#putInt} path is a best-effort fallback for a build
     * that is somehow running without ownership (it will simply fail closed
     * without {@code WRITE_SETTINGS}).
     */
    private static boolean write(Context c, String key, String value) {
        try {
            android.app.admin.DevicePolicyManager d = Engine.dpm(c);
            if (d != null && Engine.isDeviceOwner(c)) {
                d.setSystemSetting(TimeLockAdmin.component(c), key, value);
                return true;
            }
        } catch (Throwable t) {
            // fall through to the plain settings write
        }
        try {
            return Settings.System.putInt(c.getContentResolver(), key, Integer.parseInt(value));
        } catch (Throwable t) {
            return false;
        }
    }
}
