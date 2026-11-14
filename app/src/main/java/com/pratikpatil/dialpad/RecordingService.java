package com.pratikpatil.dialpad;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service (type "microphone") that owns the call recording.
 *
 * Why a service: Android 11+ silences the microphone for apps in the
 * background unless a microphone foreground service is running. Keeping the
 * recorder here means recording continues when the user leaves the call
 * screen, and it is stopped by MyInCallService when the last call ends.
 *
 * Audio source: VOICE_CALL (both sides) is reserved for system apps since
 * Android 9, so it is only tried on older versions. Otherwise the MIC source
 * is used, which captures the other person clearly only on speakerphone.
 * If the recording is pure silence (the phone blocks capture during calls),
 * it is discarded and the user is told.
 */
public class RecordingService extends Service {

    private static final String ACTION_START = "com.pratikpatil.dialpad.recording.START";
    private static final String ACTION_STOP  = "com.pratikpatil.dialpad.recording.STOP";
    private static final String EXTRA_CALLER = "caller";

    static final int NOTIFICATION_ID = 1003;

    /** How long to wait before deciding the recording is silent. */
    private static final long SILENCE_CHECK_MS = 6000;
    private static final long AMPLITUDE_POLL_MS = 500;

    // ---- Static state read by the call screen ----
    private static volatile boolean recording = false;
    private static volatile boolean micOnly = false;
    private static volatile long startedAt = 0;

    public static boolean isRecording()    { return recording; }
    /** True when only the microphone could be used (other side needs speakerphone). */
    public static boolean isMicOnly()      { return micOnly; }
    public static long getStartedAt()      { return startedAt; }

    public static void start(Context ctx, String callerLabel) {
        Intent i = new Intent(ctx, RecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CALLER, callerLabel);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Safe from anywhere, including the background (stopService has no FGS restrictions). */
    public static void stop(Context ctx) {
        if (!recording) return;
        ctx.stopService(new Intent(ctx, RecordingService.class));
    }

    // ---- Instance ----
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private MediaRecorder recorder;
    @Nullable private RecordingStore.PendingRecording output;
    private int peakAmplitude = 0;

    private final Runnable amplitudePoll = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            try { peakAmplitude = Math.max(peakAmplitude, recorder.getMaxAmplitude()); }
            catch (Exception ignored) {}
            if (System.currentTimeMillis() - startedAt < SILENCE_CHECK_MS) {
                handler.postDelayed(this, AMPLITUDE_POLL_MS);
            } else if (peakAmplitude == 0) {
                onSilentRecording();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || recording) {
            if (!recording) stopSelf();
            return START_NOT_STICKY;
        }

        // Must go foreground immediately (within 5s of startForegroundService)
        CallNotifier.createChannels(this);
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        } catch (Exception e) {
            // Android 14 refuses a mic FGS without RECORD_AUDIO / from the background
            toast("Could not start recording");
            stopSelf();
            return START_NOT_STICKY;
        }

        String caller = intent.getStringExtra(EXTRA_CALLER);
        if (startRecorder(caller != null ? caller : "Unknown")) {
            recording = true;
            startedAt = System.currentTimeMillis();
            peakAmplitude = 0;
            handler.postDelayed(amplitudePoll, AMPLITUDE_POLL_MS);
            if (micOnly) toast("Recording via microphone — use speaker to capture both sides");
            else toast("Recording started");
        } else {
            toast("Could not start recording");
            stopSelf();
        }
        CallManager.getInstance().notifyCallsChanged(); // refresh call screen
        return START_NOT_STICKY;
    }

    /** Try VOICE_CALL where third-party apps may still use it, then fall back to MIC. */
    private boolean startRecorder(String caller) {
        int[] sources = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                ? new int[]{MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.MIC};

        for (int source : sources) {
            try {
                output = RecordingStore.create(this, caller);
            } catch (Exception e) {
                output = null;
                return false;
            }
            MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            try {
                r.setAudioSource(source);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioChannels(1);
                r.setAudioSamplingRate(44100);
                r.setAudioEncodingBitRate(96000);
                r.setOutputFile(output.fd());
                r.prepare();
                r.start();
                recorder = r;
                micOnly = source != MediaRecorder.AudioSource.VOICE_CALL;
                return true;
            } catch (Exception e) {
                r.release();
                output.abort(this);
                output = null;
            }
        }
        return false;
    }

    private void onSilentRecording() {
        toast("This phone blocks call recording — nothing was captured");
        releaseRecorder(false);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recorder != null) {
            // A recording that never picked up any sound is useless — discard it
            boolean saved = releaseRecorder(
                    peakAmplitude > 0 || System.currentTimeMillis() - startedAt < SILENCE_CHECK_MS);
            toast(saved ? "Recording saved to " + RecordingStore.LOCATION_LABEL
                        : "Recording discarded");
        }
        recording = false;
        micOnly = false;
        startedAt = 0;
        CallManager.getInstance().notifyCallsChanged();
        super.onDestroy();
    }

    /** @return true if the file was kept */
    private boolean releaseRecorder(boolean keep) {
        boolean stoppedCleanly = false;
        boolean saved = false;
        if (recorder != null) {
            try { recorder.stop(); stoppedCleanly = true; } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }
        if (output != null) {
            if (keep && stoppedCleanly) {
                output.commit(this);
                saved = true;
            } else {
                output.abort(this);
            }
            output = null;
        }
        return saved;
    }

    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, CallScreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CallNotifier.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("Recording call")
                .setContentText("Tap Stop to finish")
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(open)
                .addAction(R.drawable.ic_record, "Stop", stop)
                .build();
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
