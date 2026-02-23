package com.example.depthdebug;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

public final class AlertOutput {

    private final Vibrator vibrator;
    private final SoundPool soundPool;
    private final int tickSoundId;
    private boolean soundLoaded = false;

    private long lastBeepMs = 0;
    private long lastVibrateMs = 0;

    // Output pacing
    private static final long FAR_BEEP_MS = 520;
    private static final long NEAR_VIBRO_MS = 260;
    private static final long DROP_VIBRO_MS = 800;

    public AlertOutput(Context ctx) {
        // vibrator
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = (vm != null) ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }

        // sound
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(aa)
                .build();

        tickSoundId = soundPool.load(ctx, R.raw.tick, 1);
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (sampleId == tickSoundId && status == 0) soundLoaded = true;
        });
    }

    public void release() {
        soundPool.release();
    }

    public void emit(HazardDetector.Alert alert) {
        if (alert == null) return;
        long now = android.os.SystemClock.elapsedRealtime();

        if (alert.severity == HazardDetector.Severity.CRITICAL) {
            if (now - lastVibrateMs < DROP_VIBRO_MS) return;
            lastVibrateMs = now;
            vibrateDrop();
            return;
        }

        if (alert.severity == HazardDetector.Severity.NEAR) {
            if (now - lastVibrateMs < NEAR_VIBRO_MS) return;
            lastVibrateMs = now;
            vibrateDirectional(alert.direction);
            return;
        }

        // FAR
        if (now - lastBeepMs < FAR_BEEP_MS) return;
        lastBeepMs = now;
        playTick();
    }

    private void vibrateDirectional(HazardDetector.Direction dir) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        // LEFT = 1 pulse, CENTER = 2 pulses, RIGHT = 3 pulses
        int pulses = (dir == HazardDetector.Direction.LEFT) ? 1
                : (dir == HazardDetector.Direction.CENTER ? 2 : 3);

        long[] pattern;
        if (pulses == 1) pattern = new long[]{0, 160};
        else if (pulses == 2) pattern = new long[]{0, 110, 80, 110};
        else pattern = new long[]{0, 90, 70, 90, 70, 90};

        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else vibrator.vibrate(pattern, -1);
    }

    private void vibrateDrop() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        // "Осторожно вниз" — длинный + короткий
        long[] pattern = new long[]{0, 350, 120, 160};
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else vibrator.vibrate(pattern, -1);
    }

    private void playTick() {
        if (!soundLoaded) return;
        soundPool.play(tickSoundId, 1f, 1f, 1, 0, 1f);
    }
}