package org.figuramc.figura.compat;

import org.figuramc.figura.FiguraMod;

import java.lang.reflect.Method;

/**
 * Soft (reflection-based) compatibility with the Flashback replay mod.
 * <p>
 * During replay playback Flashback re-dispatches recorded clientbound custom payloads
 * (including FSB packets) through the normal client packet pipeline, but the client is
 * not actually connected to an FSB server. FSB handlers that gate on an active connection
 * must therefore also accept packets while a replay is playing.
 * <p>
 * No compile-time dependency on Flashback: if the mod is absent, {@link #isInReplay()}
 * always returns false.
 */
public final class FlashbackCompat {
    private static final boolean LOADED;
    private static final Method IS_IN_REPLAY;

    static {
        boolean loaded = false;
        Method method = null;
        try {
            Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
            method = flashback.getMethod("isInReplay");
            loaded = true;
            FiguraMod.LOGGER.info("Flashback detected, enabling replay compatibility for FSB packets");
        } catch (ClassNotFoundException ignored) {
            // Flashback not installed
        } catch (Exception e) {
            FiguraMod.LOGGER.warn("Flashback found but replay compatibility could not be initialized", e);
        }
        LOADED = loaded;
        IS_IN_REPLAY = method;
    }

    private FlashbackCompat() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * @return true if a Flashback replay is currently playing back, false otherwise
     * (including when Flashback is not installed).
     */
    public static boolean isInReplay() {
        if (!LOADED) return false;
        try {
            return (boolean) IS_IN_REPLAY.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }
}
