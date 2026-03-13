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

    private static final long FREE_BEEP_MS = 900;
    private static final long BLOCK_VIBRO_MS = 450;
    private static final long CRITICAL_VIBRO_MS = 800;

    public AlertOutput(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = (vm != null) ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }

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

    public void emit(PassageDetector.Result result) {
        if (result == null) return;
        long now = android.os.SystemClock.elapsedRealtime();

        if (result.state == PassageDetector.State.BLOCKED) {
            if (result.severity == PassageDetector.Severity.CRITICAL) {
                if (now - lastVibrateMs < CRITICAL_VIBRO_MS) return;
                lastVibrateMs = now;
                vibrateCritical();
                return;
            }
            if (now - lastVibrateMs < BLOCK_VIBRO_MS) return;
            lastVibrateMs = now;
            vibrateBlocked();
            return;
        }

        if (result.state == PassageDetector.State.FREE) {
            if (now - lastBeepMs < FREE_BEEP_MS) return;
            lastBeepMs = now;
            playTick();
        }
    }

    private void vibrateBlocked() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = new long[]{0, 110, 90, 110};
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else vibrator.vibrate(pattern, -1);
    }

    private void vibrateCritical() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = new long[]{0, 220, 90, 220};
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else vibrator.vibrate(pattern, -1);
    }

    private void playTick() {
        if (!soundLoaded) return;
        soundPool.play(tickSoundId, 0.7f, 0.7f, 1, 0, 1f);
    }
}
