package com.pavithra.mowzieomcompat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;

public final class BossMusicController {
    private BossMusicController() {}

    private static BossMusicInstance current;
    private static ResourceLocation currentSoundId;
    private static int stickyTicksLeft = 0;
    private static boolean lastBossActive = false;

    // Exact Mowzie boss music SoundEvent IDs (from Mowzie's sounds.json). We hard-stop these when our
    // detection triggers, so you never hear the original boss themes even for a moment.
    private static final List<ResourceLocation> MOWZIE_BOSS_MUSIC_IDS = List.of(
            new ResourceLocation("mowziesmobs", "music.frostmaw_theme"),
            new ResourceLocation("mowziesmobs", "music.ferrous_wroughtnaut_theme"),
            new ResourceLocation("mowziesmobs", "music.umvuthi_theme"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_intro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_transition"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_ending"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_outro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_combat")
    );

    public static boolean isBossMusicActive() {
        return current != null;
    }

    /**
     * True while a boss is detected or during the short sticky window after leaving range.
     * Used to block/duck other music so boss themes behave as the highest-priority "boss category".
     */
    public static boolean isBossContextActive() {
        return current != null || stickyTicksLeft > 0 || lastBossActive;
    }

    public static ResourceLocation getCurrentSoundId() {
        return currentSoundId;
    }

    /**
     * @param desiredSoundId Our sound event ID to play, or null when no boss is currently detected.
     * @param bossEntity     The actual boss entity object (from Mowzie's Mobs) when available; used only for best-effort
     *                       suppression of Mowzie's built-in boss music.
     */
    public static void tick(ResourceLocation desiredSoundId, Object bossEntity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        boolean bossActive = desiredSoundId != null;

        if (bossActive) {
            stickyTicksLeft = CompatConfig.BOSS_STICKY_TICKS;

            // If we just entered boss range, immediately kill Mowzie's built-in boss music instances
            // so you don't hear them overlapping our theme.
            if (!lastBossActive) {
                stopMowzieBossThemesNow();
            }

            maybeStopMowzieBossMusic(bossEntity);

            // Crossfade: fade other music down while boss is active.
            MusicDuck.setBossActive(true);

            if (current != null && desiredSoundId.equals(currentSoundId)) {
                MusicDuck.tick();
                lastBossActive = true;
                return;
            }

            start(desiredSoundId);
            MusicDuck.tick();
            lastBossActive = true;
            return;
        }

        if (stickyTicksLeft > 0) {
            stickyTicksLeft--;
            MusicDuck.setBossActive(true);
            MusicDuck.tick();
            lastBossActive = true;
            return;
        }

        // No boss -> restore other music, fade ours out.
        MusicDuck.setBossActive(false);
        MusicDuck.tick();
        stop();
        lastBossActive = false;
    }

    private static void stopMowzieBossThemesNow() {
        Minecraft mc = Minecraft.getInstance();
        SoundManager sm = mc.getSoundManager();
        for (ResourceLocation id : MOWZIE_BOSS_MUSIC_IDS) {
            try {
                // SoundManager#stop(ResourceLocation, SoundSource) exists in modern MC.
                sm.stop(id, SoundSource.MUSIC);
            } catch (Throwable ignored) {
                // Worst case: PlaySoundEvent cancellation still prevents new boss music from starting.
            }
        }
    }

    private static void start(ResourceLocation soundId) {
        Minecraft mc = Minecraft.getInstance();
        SoundManager sm = mc.getSoundManager();

        SoundEvent evt = ForgeRegistries.SOUND_EVENTS.getValue(soundId);
        if (evt == null) {
            // Should never happen if our sounds registered correctly.
            return;
        }

        // Fade out old
        if (current != null) {
            current.startFadeOut();
        }

        BossMusicInstance inst = new BossMusicInstance(evt, CompatConfig.FADE_TICKS);
        current = inst;
        currentSoundId = soundId;
        sm.play(inst);
    }

    private static void stop() {
        if (current != null) {
            current.startFadeOut();
            current = null;
            currentSoundId = null;
        }
    }

    /**
     * Best-effort: ask Mowzie's BossMusicPlayer to stop its own boss music so only our track plays.
     * We do this via reflection to avoid a hard compile dependency.
     */
    private static void maybeStopMowzieBossMusic(Object bossEntity) {
        if (bossEntity == null) return;
        try {
            Class<?> playerCls = Class.forName("com.bobmowzie.mowziesmobs.client.sound.BossMusicPlayer");
            // stopBossMusic(MowzieEntity)
            for (Method m : playerCls.getMethods()) {
                if (!m.getName().equals("stopBossMusic")) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isInstance(bossEntity)) {
                    m.invoke(null, bossEntity);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
