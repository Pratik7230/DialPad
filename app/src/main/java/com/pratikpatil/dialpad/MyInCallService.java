package com.pratikpatil.dialpad;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;

/**
 * InCallService implementation.
 * Android routes all calls here when this app is the default phone app.
 *
 * Responsibilities:
 *  - keep CallManager's list of live calls up to date (multiple calls / call waiting)
 *  - drive the ringer and the incoming / ongoing / missed-call notifications
 *  - forward audio-route changes (mute, speaker, Bluetooth) to the UI
 *  - open the call screen
 */
public class MyInCallService extends InCallService {

    private CallRinger ringer;

    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            refresh();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            // Capabilities (hold/merge) and connect time live in details
            CallManager.getInstance().notifyCallsChanged();
        }

        @Override
        public void onConferenceableCallsChanged(Call call, java.util.List<Call> conferenceableCalls) {
            CallManager.getInstance().notifyCallsChanged();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ringer = new CallRinger(this);
        CallNotifier.createChannels(this);
        CallManager.getInstance().setService(this, ringer);
    }

    @Override
    public void onDestroy() {
        ringer.release();
        CallManager.getInstance().setService(null, null);
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        call.registerCallback(callCallback);
        CallManager.getInstance().addCall(call);
        refresh();

        // InCallService is bound by the system, so it is allowed to start the
        // call screen from the background. The full-screen notification covers
        // the lock screen / OEMs that still block it.
        Intent intent = new Intent(this, CallScreenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        call.unregisterCallback(callCallback);

        DisconnectCause cause = call.getDetails() != null ? call.getDetails().getDisconnectCause() : null;
        if (cause != null && cause.getCode() == DisconnectCause.MISSED) {
            CallNotifier.showMissedCall(this, CallManager.getInstance().getDisplayName(this, call));
        }

        CallManager.getInstance().removeCall(call);
        // Last call gone → finish and save the recording even if the call screen is closed
        if (!CallManager.getInstance().hasCalls()) RecordingService.stop(this);
        refresh();
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        CallManager.getInstance().setAudioState(audioState);
    }

    /** Re-sync ringer, notification and UI with the current set of calls. */
    private void refresh() {
        ringer.update();
        CallNotifier.updateCallNotification(this);
        CallManager.getInstance().notifyCallsChanged();
    }
}
