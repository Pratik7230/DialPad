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
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-call screen for incoming, outgoing, waiting and held calls.
 *
 * It renders whatever CallManager says is the primary call and reacts to
 * changes via CallManager.Listener, so it can be closed and reopened (from the
 * ongoing-call notification) at any time without losing state.
 */
public class CallScreenActivity extends AppCompatActivity implements CallManager.Listener {

    /** Notification "Answer" button opens this activity with this action. */
    public static final String ACTION_ANSWER = "com.pratikpatil.dialpad.action.ANSWER";

    // ---- Views: common ----
    private TextView tvCallName, tvCallNumber, tvCallStatus, tvCallTimer;
    private View pulseRingInner, pulseRingOuter;

    // ---- Views: active call controls ----
    private LinearLayout llActionGrid, llBottomControls;
    private TextView tvMuteLabel, tvHoldLabel, tvSpeakerLabel, tvRecordLabel, tvKeypadDisplay;
    private ImageButton btnMute, btnHold, btnSpeaker, btnRecord, btnEndCall, btnKeypad, btnAddCall;
    private LinearLayout badgeRecording, keypadOverlay;

    // ---- Views: second call banner ----
    private LinearLayout llSecondaryCall;
    private TextView tvSecondaryCall, btnSwap, btnMerge;

    // ---- Views: incoming call controls ----
    private LinearLayout llIncomingControls;
    private ImageButton btnAnswerCall, btnRejectCall, btnIncomingMessage, btnIncomingRemind;

    // ---- State ----
    private final CallManager callManager = CallManager.getInstance();
    @Nullable private Call shownCall;           // primary call currently displayed
    private long localConnectTime = 0;          // fallback if Telecom has no connect time
    private boolean isKeypadVisible = false;
    private boolean showingIncoming = false;
    private boolean ending = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Status text + animated dots
    private String baseStatus = "";
    private boolean animateDots = false;
    private int dotCount = 0;
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            if (animateDots) {
                dotCount = (dotCount + 1) % 4;
                StringBuilder sb = new StringBuilder(baseStatus);
                for (int i = 0; i < dotCount; i++) sb.append('.');
                tvCallStatus.setText(sb);
            }
            updateTimerText();
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable finishRunnable = this::finish;

    // Audio
    private ToneGenerator toneGenerator;

    // Proximity sensor (screen off when held to the ear)
    @Nullable private PowerManager.WakeLock proximityLock;

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        setContentView(R.layout.activity_call_screen);

        try { toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80); }
        catch (Exception ignored) {}

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "DialPad:proximity");
            proximityLock.setReferenceCounted(false);
        }

        bindViews();
        setupClickListeners();
        startPulseAnimation();

        callManager.addListener(this);
        handler.post(statusTicker);

        handleIntentAction(getIntent());
        render();
        renderAudio();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentAction(intent);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateProximity();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releaseProximity();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        callManager.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        releaseProximity();
        if (dotBlink != null) dotBlink.cancel();
        // Recording is NOT stopped here — RecordingService keeps it going until the call ends
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
    }

    private void handleIntentAction(@Nullable Intent intent) {
        if (intent != null && ACTION_ANSWER.equals(intent.getAction())) {
            Call ringing = callManager.getRingingCall();
            if (ringing != null) ringing.answer(VideoProfile.STATE_AUDIO_ONLY);
            intent.setAction(null); // don't re-answer on recreation
        }
    }

    /** Volume keys silence the ringer for an incoming call (standard dialer behaviour). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
                && callManager.getRingingCall() != null) {
            callManager.silenceRinger();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== CallManager.Listener ====================

    @Override
    public void onCallsChanged() {
        render();
    }

    @Override
    public void onAudioStateChanged(@Nullable CallAudioState state) {
        renderAudio();
        updateProximity();
    }

    // ==================== RENDER ====================

    private void render() {
        Call primary = callManager.getPrimaryCall();

        if (primary == null) {
            onAllCallsEnded();
            return;
        }
        if (ending) {               // a new call arrived while we were closing
            ending = false;
            handler.removeCallbacks(finishRunnable);
            btnEndCall.setEnabled(true);
        }

        if (primary != shownCall) {
            shownCall = primary;
            localConnectTime = 0;
        }

        String number = CallManager.getNumber(primary);
        String name = callManager.getDisplayName(this, primary);
        tvCallName.setText(name);
        tvCallNumber.setText(!name.equals(number) ? number : "");

        int state = primary.getState();
        boolean ringing = state == Call.STATE_RINGING;
        setIncomingMode(ringing);

        switch (state) {
            case Call.STATE_RINGING:
                setStatus(callManager.isCallWaiting() ? "CALL WAITING" : "INCOMING CALL", true);
                break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                setStatus("DIALLING", true);
                break;
            case Call.STATE_ACTIVE:
                if (localConnectTime == 0) localConnectTime = System.currentTimeMillis();
                setStatus(isConference(primary) ? "CONFERENCE" : "ACTIVE", false);
                break;
            case Call.STATE_HOLDING:
                setStatus("ON HOLD", false);
                break;
            default:
                setStatus(CallManager.stateToString(state), false);
        }

        renderHold(primary);
        renderSecondary(primary);
        renderRecording();

        boolean canAdd = (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING)
                && callManager.getSecondaryCall(primary) == null;
        btnAddCall.setEnabled(canAdd);
        btnAddCall.setAlpha(canAdd ? 1f : 0.4f);

        updateTimerText();
        updateProximity();
    }

    private void setStatus(String text, boolean dots) {
        baseStatus = text;
        animateDots = dots;
        if (!dots) tvCallStatus.setText(text);
        tvCallStatus.setTextColor(text.equals("ACTIVE") || text.equals("CONFERENCE")
                ? 0xFF00E5FF : 0xCC00E5FF);
    }

    private void setIncomingMode(boolean incoming) {
        if (incoming == showingIncoming && llIncomingControls.getVisibility()
                == (incoming ? View.VISIBLE : View.GONE)) {
            return;
        }
        boolean wasIncoming = showingIncoming;
        showingIncoming = incoming;

        llIncomingControls.setVisibility(incoming ? View.VISIBLE : View.GONE);
        llActionGrid.setVisibility(incoming ? View.GONE : View.VISIBLE);
        llBottomControls.setVisibility(incoming ? View.GONE : View.VISIBLE);
        if (incoming) {
            keypadOverlay.setVisibility(View.GONE);
            isKeypadVisible = false;
        } else if (wasIncoming) {
            llActionGrid.setAlpha(0f);
            llBottomControls.setAlpha(0f);
            llActionGrid.animate().alpha(1f).setDuration(300).start();
            llBottomControls.animate().alpha(1f).setDuration(300).start();
        }
    }

    private void renderHold(Call primary) {
        boolean held = primary.getState() == Call.STATE_HOLDING;
        boolean canHold = primary.getDetails() != null
                && (primary.getDetails().can(Call.Details.CAPABILITY_HOLD)
                    || primary.getDetails().can(Call.Details.CAPABILITY_SUPPORT_HOLD));
        btnHold.setEnabled(canHold || held);
        btnHold.setAlpha(canHold || held ? 1f : 0.4f);
        btnHold.setBackgroundResource(held
                ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
        tvHoldLabel.setText(held ? "Resume" : "Hold");
    }

    private void renderSecondary(Call primary) {
        Call secondary = callManager.getSecondaryCall(primary);
        if (secondary == null) {
            llSecondaryCall.setVisibility(View.GONE);
            return;
        }
        llSecondaryCall.setVisibility(View.VISIBLE);
        String secName = callManager.getDisplayName(this, secondary);
        String secState = secondary.getState() == Call.STATE_HOLDING ? "On hold"
                : secondary.getState() == Call.STATE_ACTIVE ? "Active"
                : CallManager.stateToString(secondary.getState()).toLowerCase(Locale.getDefault());
        tvSecondaryCall.setText(secName + " · " + secState);

        boolean primaryRinging = primary.getState() == Call.STATE_RINGING;
        btnSwap.setVisibility(!primaryRinging && secondary.getState() == Call.STATE_HOLDING
                ? View.VISIBLE : View.GONE);
        btnMerge.setVisibility(!primaryRinging && canMerge(primary, secondary)
                ? View.VISIBLE : View.GONE);
    }

    private boolean canMerge(Call primary, Call secondary) {
        if (primary.getConferenceableCalls().contains(secondary)) return true;
        return primary.getDetails() != null
                && primary.getDetails().can(Call.Details.CAPABILITY_MERGE_CONFERENCE);
    }

    private static boolean isConference(Call call) {
        return call.getDetails() != null
                && call.getDetails().hasProperty(Call.Details.PROPERTY_CONFERENCE);
    }

    private void onAllCallsEnded() {
        if (ending) return;
        ending = true;
        animateDots = false;
        tvCallStatus.setText("CALL ENDED");
        btnEndCall.setEnabled(false);
        llSecondaryCall.setVisibility(View.GONE);
        RecordingService.stop(this);
        releaseProximity();
        handler.postDelayed(finishRunnable, 1200);
    }

    // ==================== AUDIO ====================

    private void renderAudio() {
        boolean muted = callManager.isMuted();
        btnMute.setImageResource(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        btnMute.setBackgroundResource(muted
                ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
        tvMuteLabel.setText(muted ? "Unmute" : "Mute");

        int route = callManager.getRoute();
        boolean offEarpiece = route != CallAudioState.ROUTE_EARPIECE;
        btnSpeaker.setBackgroundResource(offEarpiece && route != CallAudioState.ROUTE_WIRED_HEADSET
                ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
        tvSpeakerLabel.setText(routeLabel(route));
    }

    private static String routeLabel(int route) {
        switch (route) {
            case CallAudioState.ROUTE_BLUETOOTH:     return "Bluetooth";
            case CallAudioState.ROUTE_WIRED_HEADSET: return "Headset";
            default:                                 return "Speaker";
        }
    }

    private void onSpeakerClicked() {
        int supported = callManager.getSupportedRoutes();
        int current = callManager.getRoute();

        // With Bluetooth available, let the user pick the device
        if ((supported & CallAudioState.ROUTE_BLUETOOTH) != 0) {
            showRoutePicker(supported, current);
            return;
        }
        // Otherwise toggle speaker <-> earpiece (or wired headset)
        if (current == CallAudioState.ROUTE_SPEAKER) {
            callManager.setAudioRoute((supported & CallAudioState.ROUTE_WIRED_HEADSET) != 0
                    ? CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE);
        } else {
            callManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        }
    }

    private void showRoutePicker(int supported, int current) {
        final List<Integer> routes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int[] all = {CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_BLUETOOTH};
        String[] names = {"Phone", "Wired headset", "Speaker", "Bluetooth"};
        int checked = -1;
        for (int i = 0; i < all.length; i++) {
            if ((supported & all[i]) != 0) {
                if (all[i] == current) checked = routes.size();
                routes.add(all[i]);
                labels.add(names[i]);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Audio output")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    callManager.setAudioRoute(routes.get(which));
                    d.dismiss();
                })
                .show();
    }

    // ==================== PROXIMITY ====================

    /** Turn the screen off near the ear — only for earpiece audio with no keypad open. */
    private void updateProximity() {
        if (proximityLock == null) return;
        Call primary = callManager.getPrimaryCall();
        int state = primary != null ? primary.getState() : Call.STATE_DISCONNECTED;
        boolean inCall = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING
                || state == Call.STATE_CONNECTING || state == Call.STATE_HOLDING;
        boolean wanted = inCall && !ending && !isKeypadVisible
                && callManager.getRoute() == CallAudioState.ROUTE_EARPIECE
                && hasWindowFocusOrStarted();

        if (wanted && !proximityLock.isHeld()) {
            proximityLock.acquire();
        } else if (!wanted) {
            releaseProximity();
        }
    }

    private boolean hasWindowFocusOrStarted() {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    private void releaseProximity() {
        if (proximityLock != null && proximityLock.isHeld()) {
            proximityLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        }
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
        btnAddCall = findViewById(R.id.btnAddCall);
        findViewById(R.id.btnMinimize).setOnClickListener(v -> finish());

        pulseRingInner = findViewById(R.id.pulseRingInner);
        pulseRingOuter = findViewById(R.id.pulseRingOuter);
        badgeRecording = findViewById(R.id.badgeRecording);
        keypadOverlay  = findViewById(R.id.keypadOverlay);

        llActionGrid     = findViewById(R.id.llActionGrid);
        llBottomControls = findViewById(R.id.llBottomControls);

        llSecondaryCall = findViewById(R.id.llSecondaryCall);
        tvSecondaryCall = findViewById(R.id.tvSecondaryCall);
        btnSwap         = findViewById(R.id.btnSwap);
        btnMerge        = findViewById(R.id.btnMerge);

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
            Call ringing = callManager.getRingingCall();
            // Telecom automatically puts an existing active call on hold (call waiting)
            if (ringing != null) ringing.answer(VideoProfile.STATE_AUDIO_ONLY);
        });

        btnRejectCall.setOnClickListener(v -> {
            Call ringing = callManager.getRingingCall();
            if (ringing != null) ringing.reject(false, null);
            else render();
        });

        btnIncomingMessage.setOnClickListener(v -> {
            Call ringing = callManager.getRingingCall();
            if (ringing != null) ringing.reject(true, "I'll call you back");
            brief("Rejected with message");
        });

        btnIncomingRemind.setOnClickListener(v -> {
            brief("Reminder set for 15 minutes");
            Call ringing = callManager.getRingingCall();
            if (ringing != null) ringing.reject(false, null);
        });

        // ---- Active call buttons ----

        btnMute.setOnClickListener(v -> callManager.setMuted(!callManager.isMuted()));

        btnHold.setOnClickListener(v -> {
            Call primary = callManager.getPrimaryCall();
            if (primary == null) return;
            // UI updates from the real state change, not optimistically
            if (primary.getState() == Call.STATE_HOLDING) primary.unhold();
            else if (primary.getState() == Call.STATE_ACTIVE) primary.hold();
        });

        btnSpeaker.setOnClickListener(v -> onSpeakerClicked());

        btnRecord.setOnClickListener(v -> toggleRecording());

        findViewById(R.id.btnVideoCall).setOnClickListener(v ->
                brief("Video calling not supported"));

        // Add call: dial a second number; Telecom holds the current call
        btnAddCall.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));

        btnSwap.setOnClickListener(v -> {
            Call primary = callManager.getPrimaryCall();
            Call held = callManager.getSecondaryCall(primary);
            // Unholding the background call makes Telecom hold the current one
            if (held != null && held.getState() == Call.STATE_HOLDING) held.unhold();
        });

        btnMerge.setOnClickListener(v -> {
            Call primary = callManager.getPrimaryCall();
            Call other = callManager.getSecondaryCall(primary);
            if (primary == null || other == null) return;
            if (primary.getConferenceableCalls().contains(other)) {
                primary.conference(other);
            } else {
                primary.mergeConference();
            }
        });

        btnKeypad.setOnClickListener(v -> {
            isKeypadVisible = !isKeypadVisible;
            keypadOverlay.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
            if (isKeypadVisible) { tvKeypadDisplay.setText(""); slideIn(keypadOverlay); }
            updateProximity();
        });

        btnEndCall.setOnClickListener(v -> {
            Call primary = callManager.getPrimaryCall();
            if (primary != null) primary.disconnect();
            else onAllCallsEnded();
        });
    }

    // ==================== KEYPAD ====================

    public void keypadOverlayClick(View view) {
        String digit = (String) view.getTag();
        if (digit == null) return;
        tvKeypadDisplay.setText(tvKeypadDisplay.getText().toString() + digit);
        if (toneGenerator != null) toneGenerator.startTone(dtmfFor(digit), 100);
        Call primary = callManager.getPrimaryCall();
        if (primary != null) {
            primary.playDtmfTone(digit.charAt(0));
            primary.stopDtmfTone();
        }
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
    // The recorder itself lives in RecordingService so it keeps running when
    // this screen is closed; this screen only starts/stops it and shows state.

    private static final int REQ_RECORD = 99;

    private void toggleRecording() {
        if (RecordingService.isRecording()) {
            RecordingService.stop(this);
            return;
        }
        SharedPreferences prefs = getSharedPreferences("dialpad_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SettingsActivity.PREF_CALL_RECORDING, false)) {
            brief("Enable call recording in Settings first");
            return;
        }
        String[] missing = missingRecordingPermissions();
        if (missing.length > 0) {
            ActivityCompat.requestPermissions(this, missing, REQ_RECORD);
            return;
        }
        startRecording();
    }

    private String[] missingRecordingPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        // Android 9 and older write to the public Music folder with the File API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return needed.toArray(new String[0]);
    }

    private void startRecording() {
        Call primary = callManager.getPrimaryCall();
        if (primary == null) return;
        try {
            RecordingService.start(this, callManager.getDisplayName(this, primary));
        } catch (Exception e) {
            brief("Could not start recording");
        }
    }

    private ObjectAnimator dotBlink;

    private void renderRecording() {
        boolean rec = RecordingService.isRecording();
        btnRecord.setBackgroundResource(rec
                ? R.drawable.bg_call_action_btn_active : R.drawable.bg_call_action_btn);
        tvRecordLabel.setText(rec ? "Stop rec" : "Record");
        badgeRecording.setVisibility(rec ? View.VISIBLE : View.GONE);

        View dot = findViewById(R.id.recordingDot);
        if (rec && dotBlink == null && dot != null) {
            dotBlink = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0f);
            dotBlink.setDuration(700);
            dotBlink.setRepeatMode(ObjectAnimator.REVERSE);
            dotBlink.setRepeatCount(ObjectAnimator.INFINITE);
            dotBlink.start();
        } else if (!rec && dotBlink != null) {
            dotBlink.cancel();
            dotBlink = null;
        }
    }

    // ==================== TIMER ====================

    /** Timer comes from the call's real connect time, so it survives reopening the screen. */
    private void updateTimerText() {
        Call call = shownCall;
        if (ending || call == null
                || (call.getState() != Call.STATE_ACTIVE && call.getState() != Call.STATE_HOLDING)) {
            if (!ending) tvCallTimer.setVisibility(View.GONE);
            return;
        }
        long connect = call.getDetails() != null ? call.getDetails().getConnectTimeMillis() : 0;
        if (connect <= 0) connect = localConnectTime;
        if (connect <= 0) return;
        long secs = Math.max(0, (System.currentTimeMillis() - connect) / 1000);
        tvCallTimer.setVisibility(View.VISIBLE);
        tvCallTimer.setText(secs >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
                : String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60));
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
                if (isFinishing() || isDestroyed()) return;
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD) return;
        if (missingRecordingPermissions().length == 0) {
            startRecording();
        } else {
            brief("Recording needs microphone permission");
        }
    }
}
