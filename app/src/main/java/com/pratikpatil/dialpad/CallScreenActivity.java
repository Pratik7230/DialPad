package com.pratikpatil.dialpad;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.Call;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Custom in-call screen for both OUTGOING and INCOMING calls.
 * Launched by MyInCallService when this app is the default dialer.
 * It never uses Intent.ACTION_CALL — the call is already active via TelecomManager.
 */
public class CallScreenActivity extends AppCompatActivity {

    public static final String EXTRA_NUMBER    = "call_number";
    public static final String EXTRA_NAME      = "call_name";
    public static final String EXTRA_INCOMING  = "call_incoming";

    // ---- Views: common ----
    private TextView tvCallName, tvCallNumber, tvCallStatus, tvCallTimer;
    private View pulseRingInner, pulseRingOuter;

    // ---- Views: outgoing / active call controls ----
    private LinearLayout llActionGrid, llBottomControls;
    private TextView tvMuteLabel, tvHoldLabel, tvSpeakerLabel, tvRecordLabel, tvKeypadDisplay;
    private ImageButton btnMute, btnHold, btnSpeaker, btnRecord, btnEndCall, btnKeypad;
    private LinearLayout badgeRecording, keypadOverlay;

    // ---- Views: incoming call controls ----
    private LinearLayout llIncomingControls;
    private ImageButton btnAnswerCall, btnRejectCall, btnIncomingMessage, btnIncomingRemind;

    // ---- State ----
    private boolean isMuted = false, isOnHold = false, isSpeaker = false;
    private boolean isRecording = false, isKeypadVisible = false, callEnded = false;
    private boolean isIncoming = false;

    // Timer
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;

    // Status dots
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private int dotCount = 0;
    private String baseStatus = "DIALLING";
    private Runnable statusRunnable;

    // Real telecom Call
    private Call activeCall;
    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            runOnUiThread(() -> handleState(state));
        }
    };

    // Recording
    private MediaRecorder mediaRecorder;

    // Audio
    private AudioManager audioManager;
    private ToneGenerator toneGenerator;

    // Ringtone + vibration (incoming)
    private Ringtone ringtone;
    private Vibrator vibrator;

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show on lock screen
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        setContentView(R.layout.activity_call_screen);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try { toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80); }
        catch (Exception ignored) {}

        bindViews();

        // Read intent extras
        String number = getIntent().getStringExtra(EXTRA_NUMBER);
        String name   = getIntent().getStringExtra(EXTRA_NAME);
        isIncoming    = getIntent().getBooleanExtra(EXTRA_INCOMING, false);

        tvCallName.setText(name != null && !name.isEmpty() ? name
                : number != null ? number : "Unknown");
        tvCallNumber.setText(name != null && !name.isEmpty() && number != null ? number : "");

        setupClickListeners();
        startPulseAnimation();

        // Get the live Call from CallManager
        activeCall = CallManager.getInstance().getCurrentCall();
        if (activeCall != null) {
            activeCall.registerCallback(callCallback);
            handleState(activeCall.getState());
        } else {
            // Retry briefly — InCallService may not have delivered yet
            timerHandler.postDelayed(() -> {
                activeCall = CallManager.getInstance().getCurrentCall();
                if (activeCall != null) {
                    activeCall.registerCallback(callCallback);
                    handleState(activeCall.getState());
                }
            }, 1000);
        }

        // Set initial UI based on direction
        if (isIncoming) {
            showIncomingUI();
        } else {
            showOutgoingUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeCall != null) activeCall.unregisterCallback(callCallback);
        timerRunning = false;
        timerHandler.removeCallbacksAndMessages(null);
        statusHandler.removeCallbacksAndMessages(null);
        stopRecording();
        stopRingtone();
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
    }

    // ==================== INCOMING / OUTGOING UI ====================

    private void showIncomingUI() {
        baseStatus = "INCOMING CALL";
        startStatusAnimation();

        // Show incoming controls, hide outgoing controls
        llIncomingControls.setVisibility(View.VISIBLE);
        llActionGrid.setVisibility(View.GONE);
        llBottomControls.setVisibility(View.GONE);

        // Play ringtone + vibrate
        startRingtone();
        startVibration();
    }

    private void showOutgoingUI() {
        baseStatus = "DIALLING";
        startStatusAnimation();

        llIncomingControls.setVisibility(View.GONE);
        llActionGrid.setVisibility(View.VISIBLE);
        llBottomControls.setVisibility(View.VISIBLE);
    }

    /** Transition from incoming → active call UI after answering */
    private void transitionToActiveUI() {
        stopRingtone();
        stopVibration();

        llIncomingControls.setVisibility(View.GONE);
        llActionGrid.setVisibility(View.VISIBLE);
        llBottomControls.setVisibility(View.VISIBLE);

        // Animate in
        llActionGrid.setAlpha(0f);
        llBottomControls.setAlpha(0f);
        llActionGrid.animate().alpha(1f).setDuration(300).start();
        llBottomControls.animate().alpha(1f).setDuration(300).start();
    }

    // ==================== RINGTONE & VIBRATION ====================

    private void startRingtone() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(this, ringtoneUri);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {}
    }

    private void stopRingtone() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        ringtone = null;
    }

    private void startVibration() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) return;
        long[] pattern = {0, 1000, 1000}; // vibrate 1s, pause 1s, repeat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private void stopVibration() {
        if (vibrator != null) vibrator.cancel();
    }

    // ==================== CALL STATE ====================

    private void handleState(int state) {
        switch (state) {
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                baseStatus = "DIALLING";
                break;
            case Call.STATE_RINGING:
                if (!isIncoming) {
                    isIncoming = true;
                    showIncomingUI();
                }
                baseStatus = "INCOMING CALL";
                break;
            case Call.STATE_ACTIVE:
                onConnected();
                break;
            case Call.STATE_HOLDING:
                tvCallStatus.setText("ON HOLD");
                break;
            case Call.STATE_DISCONNECTED:
                onDisconnected();
                break;
        }
    }

    private void onConnected() {
        stopStatusAnimation();
        stopRingtone();
        stopVibration();

        // If we were showing incoming UI, transition to active
        if (llIncomingControls.getVisibility() == View.VISIBLE) {
            transitionToActiveUI();
        }

        tvCallStatus.setText("ACTIVE");
        tvCallStatus.setTextColor(0xFF00E5FF);
        startCallTimer();
    }

    private void onDisconnected() {
        if (callEnded) return;
        callEnded = true;
        timerRunning = false;
        stopStatusAnimation();
        stopRingtone();
        stopVibration();
        tvCallStatus.setText("CALL ENDED");
        btnEndCall.setEnabled(false);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
    }

    // ==================== VIEW BINDING ====================

    private void bindViews() {
        tvCallName      = findViewById(R.id.tvCallName);
        tvCallNumber    = findViewById(R.id.tvCallNumber);
        tvCallStatus    = findViewById(R.id.tvCallStatus);
        tvCallTimer     = findViewById(R.id.tvCallTimer);
        tvMuteLabel     = findViewById(R.id.tvMuteLabel);
        tvHoldLabel     = findViewById(R.id.tvHoldLabel);
        tvSpeakerLabel  = findViewById(R.id.tvSpeakerLabel);
        tvRecordLabel   = findViewById(R.id.tvRecordLabel);
        tvKeypadDisplay = findViewById(R.id.tvKeypadDisplay);

        btnMute    = findViewById(R.id.btnMute);
        btnHold    = findViewById(R.id.btnHold);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnRecord  = findViewById(R.id.btnRecord);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnKeypad  = findViewById(R.id.btnKeypad);
        findViewById(R.id.btnMinimize).setOnClickListener(v -> finish());

        pulseRingInner = findViewById(R.id.pulseRingInner);
        pulseRingOuter = findViewById(R.id.pulseRingOuter);
        badgeRecording = findViewById(R.id.badgeRecording);
        keypadOverlay  = findViewById(R.id.keypadOverlay);

        // Outgoing controls containers
        llActionGrid    = findViewById(R.id.llActionGrid);
        llBottomControls = findViewById(R.id.llBottomControls);

        // Incoming controls
        llIncomingControls = findViewById(R.id.llIncomingControls);
        btnAnswerCall      = findViewById(R.id.btnAnswerCall);
        btnRejectCall      = findViewById(R.id.btnRejectCall);
        btnIncomingMessage = findViewById(R.id.btnIncomingMessage);
        btnIncomingRemind  = findViewById(R.id.btnIncomingRemind);
    }

    // ==================== CLICK LISTENERS ====================

    private void setupClickListeners() {

        // ---- Incoming call buttons ----

        btnAnswerCall.setOnClickListener(v -> {
            if (activeCall != null) {
                activeCall.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY);
            }
        });

        btnRejectCall.setOnClickListener(v -> {
            if (activeCall != null) {
                activeCall.reject(false, null);
            } else {
                onDisconnected();
            }
        });

        btnIncomingMessage.setOnClickListener(v -> {
            // Reject with SMS reply
            if (activeCall != null) {
                activeCall.reject(true, "I'll call you back");
            }
            brief("Rejected with message");
        });

        btnIncomingRemind.setOnClickListener(v -> {
            brief("Reminder set for 15 minutes");
            if (activeCall != null) {
                activeCall.reject(false, null);
            }
        });

        // ---- Active call buttons ----

        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setBackgroundResource(isMuted
                    ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
            tvMuteLabel.setText(isMuted ? "Unmute" : "Mute");
            if (audioManager != null) audioManager.setMicrophoneMute(isMuted);
        });

        btnHold.setOnClickListener(v -> {
            if (activeCall == null) return;
            isOnHold = !isOnHold;
            btnHold.setBackgroundResource(isOnHold
                    ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
            tvHoldLabel.setText(isOnHold ? "Resume" : "Hold");
            if (isOnHold) activeCall.hold(); else activeCall.unhold();
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            btnSpeaker.setBackgroundResource(isSpeaker
                    ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
            tvSpeakerLabel.setText(isSpeaker ? "Earpiece" : "Speaker");
            if (audioManager != null) {
                audioManager.setSpeakerphoneOn(isSpeaker);
                audioManager.setMode(isSpeaker
                        ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
            }
        });

        btnRecord.setOnClickListener(v -> toggleRecording());

        findViewById(R.id.btnVideoCall).setOnClickListener(v ->
                brief("Video calling not supported"));
        findViewById(R.id.btnAddCall).setOnClickListener(v ->
                brief("Hold the current call first"));

        btnKeypad.setOnClickListener(v -> {
            isKeypadVisible = !isKeypadVisible;
            keypadOverlay.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
            if (isKeypadVisible) { tvKeypadDisplay.setText(""); slideIn(keypadOverlay); }
        });

        btnEndCall.setOnClickListener(v -> {
            if (activeCall != null) {
                activeCall.disconnect();
            } else {
                onDisconnected();
            }
        });
    }

    // ==================== KEYPAD ====================

    public void keypadOverlayClick(View view) {
        String digit = (String) view.getTag();
        if (digit == null) return;
        tvKeypadDisplay.setText(tvKeypadDisplay.getText().toString() + digit);
        if (toneGenerator != null) toneGenerator.startTone(dtmfFor(digit), 100);
        if (activeCall != null) activeCall.playDtmfTone(digit.charAt(0));
    }

    private int dtmfFor(String d) {
        switch (d) {
            case "1": return ToneGenerator.TONE_DTMF_1;
            case "2": return ToneGenerator.TONE_DTMF_2;
            case "3": return ToneGenerator.TONE_DTMF_3;
            case "4": return ToneGenerator.TONE_DTMF_4;
            case "5": return ToneGenerator.TONE_DTMF_5;
            case "6": return ToneGenerator.TONE_DTMF_6;
            case "7": return ToneGenerator.TONE_DTMF_7;
            case "8": return ToneGenerator.TONE_DTMF_8;
            case "9": return ToneGenerator.TONE_DTMF_9;
            case "*": return ToneGenerator.TONE_DTMF_S;
            case "#": return ToneGenerator.TONE_DTMF_P;
            default:  return ToneGenerator.TONE_DTMF_0;
        }
    }

    // ==================== RECORDING ====================

    private void toggleRecording() {
        SharedPreferences prefs = getSharedPreferences("dialpad_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SettingsActivity.PREF_CALL_RECORDING, false)) {
            brief("Enable call recording in Settings first");
            return;
        }
        if (!isRecording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, 99);
                return;
            }
            startRecording();
        } else {
            stopRecording();
            isRecording = false;
            btnRecord.setBackgroundResource(R.drawable.bg_call_action_btn);
            tvRecordLabel.setText("Record");
            badgeRecording.setVisibility(View.GONE);
            brief("Recording saved");
        }
    }

    private void startRecording() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC), "Recordings");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String path = new File(dir, "call_" + ts + ".m4a").getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(path);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            btnRecord.setBackgroundResource(R.drawable.bg_call_action_btn_active);
            tvRecordLabel.setText("Recording");
            badgeRecording.setVisibility(View.VISIBLE);
            blinkDot();
            brief("Recording started");
        } catch (IOException | IllegalStateException e) {
            brief("Could not start recording");
            mediaRecorder = null;
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void blinkDot() {
        View dot = findViewById(R.id.recordingDot);
        if (dot == null) return;
        ObjectAnimator a = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0f);
        a.setDuration(700);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.start();
    }

    // ==================== TIMER ====================

    private void startCallTimer() {
        if (timerRunning) return;
        timerRunning = true;
        tvCallTimer.setVisibility(View.VISIBLE);
        elapsedSeconds = 0;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!timerRunning) return;
                elapsedSeconds++;
                tvCallTimer.setText(String.format(Locale.getDefault(), "%02d:%02d",
                        elapsedSeconds / 60, elapsedSeconds % 60));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(r);
    }

    // ==================== STATUS DOTS ====================

    private void startStatusAnimation() {
        statusRunnable = new Runnable() {
            @Override public void run() {
                dotCount = (dotCount + 1) % 4;
                StringBuilder sb = new StringBuilder(baseStatus);
                for (int i = 0; i < dotCount; i++) sb.append(".");
                tvCallStatus.setText(sb.toString());
                statusHandler.postDelayed(this, 500);
            }
        };
        statusHandler.post(statusRunnable);
    }

    private void stopStatusAnimation() {
        if (statusRunnable != null) statusHandler.removeCallbacks(statusRunnable);
    }

    // ==================== PULSE ANIMATION ====================

    private void startPulseAnimation() {
        pulse(pulseRingInner, 900, 0);
        pulse(pulseRingOuter, 900, 350);
    }

    private void pulse(View ring, long dur, long delay) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 1.5f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 1.5f);
        ObjectAnimator al = ObjectAnimator.ofFloat(ring, "alpha",  0.9f, 0f);
        sx.setDuration(dur); sy.setDuration(dur); al.setDuration(dur);
        sx.setInterpolator(new DecelerateInterpolator());
        sy.setInterpolator(new DecelerateInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy, al);
        set.setStartDelay(delay);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                ring.setScaleX(0.6f); ring.setScaleY(0.6f); ring.setAlpha(0f);
                set.setStartDelay(0); set.start();
            }
        });
        set.start();
    }

    private void slideIn(View v) {
        v.setTranslationY(300f);
        v.animate().translationY(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ==================== UTILITY ====================

    private void brief(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public static Intent buildIntent(Context ctx, String number, String name) {
        return buildIntent(ctx, number, name, false);
    }

    public static Intent buildIntent(Context ctx, String number, String name, boolean incoming) {
        Intent i = new Intent(ctx, CallScreenActivity.class);
        i.putExtra(EXTRA_NUMBER, number);
        i.putExtra(EXTRA_NAME, name);
        i.putExtra(EXTRA_INCOMING, incoming);
        return i;
    }
}
