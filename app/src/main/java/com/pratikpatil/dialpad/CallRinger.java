package com.pratikpatil.dialpad;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.Call;

import androidx.annotation.Nullable;

/**
 * Plays the ringtone / vibration for incoming calls.
 *
 * Respects the ringer mode (normal / vibrate / silent), Do Not Disturb,
 * the "Vibrate" setting, and plays a soft call-waiting beep instead of the
 * full ringtone when another call is already in progress.
 * Owned by MyInCallService so it works even if the call screen isn't visible.
 */
public class CallRinger {

    private static final long WAITING_BEEP_INTERVAL_MS = 3000;

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private Ringtone ringtone;
    @Nullable private Vibrator vibrator;
    @Nullable private ToneGenerator waitingTone;

    /** The call we are currently ringing for, and the one the user silenced. */
    @Nullable private Call ringingFor;
    @Nullable private Call silencedCall;

    private boolean ringing = false;
    private boolean waitingBeeping = false;

    private final Runnable loopRingtone = new Runnable() {
        @Override public void run() {
            // Pre-Android 9 Ringtone has no setLooping(); restart it when it finishes.
            if (!ringing || ringtone == null) return;
            if (!ringtone.isPlaying()) ringtone.play();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable waitingBeep = new Runnable() {
        @Override public void run() {
            if (!waitingBeeping) return;
            if (waitingTone != null) waitingTone.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, 500);
            handler.postDelayed(this, WAITING_BEEP_INTERVAL_MS);
        }
    };

    public CallRinger(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Re-evaluate what should be playing based on the current calls. */
    public void update() {
        CallManager cm = CallManager.getInstance();
        Call ringingCall = cm.getRingingCall();

        if (ringingCall == null || ringingCall == silencedCall) {
            stop();
            return;
        }
        if (ringingCall == ringingFor) return; // already handling this one

        stop();
        ringingFor = ringingCall;

        if (cm.isCallWaiting()) {
            startWaitingBeep();
        } else {
            startRinging();
        }
    }

    /** Volume key / user silence: stop sound for the current ringing call only. */
    public void silence() {
        silencedCall = ringingFor;
        stop();
    }

    public void release() {
        stop();
        silencedCall = null;
    }

    // ==================== FULL RINGTONE ====================

    private void startRinging() {
        if (audioManager == null) return;
        int mode = audioManager.getRingerMode();
        boolean dndBlocksCalls = isDndBlockingCalls();

        boolean playSound = mode == AudioManager.RINGER_MODE_NORMAL && !dndBlocksCalls;
        boolean doVibrate = mode != AudioManager.RINGER_MODE_SILENT && !dndBlocksCalls
                && (mode == AudioManager.RINGER_MODE_VIBRATE || vibrateSettingOn());

        if (playSound) playRingtone();
        if (doVibrate) startVibration();
    }

    private void playRingtone() {
        try {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(context, uri);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringing = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.setLooping(true);
                ringtone.play();
            } else {
                ringtone.play();
                handler.postDelayed(loopRingtone, 1000);
            }
        } catch (Exception ignored) {
            ringtone = null;
        }
    }

    private void startVibration() {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 1000, 1000}; // 1s on, 1s off, repeat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build();
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs);
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    // ==================== CALL WAITING ====================

    private void startWaitingBeep() {
        try {
            waitingTone = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60);
        } catch (Exception e) {
            waitingTone = null;
            return;
        }
        waitingBeeping = true;
        handler.post(waitingBeep);
    }

    // ==================== STOP ====================

    private void stop() {
        ringing = false;
        waitingBeeping = false;
        handler.removeCallbacks(loopRingtone);
        handler.removeCallbacks(waitingBeep);
        if (ringtone != null) {
            try { ringtone.stop(); } catch (Exception ignored) {}
            ringtone = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (waitingTone != null) {
            waitingTone.release();
            waitingTone = null;
        }
        ringingFor = null;
    }

    // ==================== POLICY ====================

    private boolean vibrateSettingOn() {
        SharedPreferences prefs = context.getSharedPreferences("dialpad_settings", Context.MODE_PRIVATE);
        return prefs.getBoolean(SettingsActivity.PREF_VIBRATE, true);
    }

    /** True when Do Not Disturb is set so that it silences calls. */
    private boolean isDndBlockingCalls() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        int filter = nm.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE
                || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
            return true;
        }
        if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            // Priority mode: calls ring only if the user's policy allows calls at all.
            try {
                NotificationManager.Policy policy = nm.getNotificationPolicy();
                return policy != null
                        && (policy.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) == 0;
            } catch (SecurityException e) {
                return false; // can't read the policy — err on the side of ringing
            }
        }
        return false;
    }
}
