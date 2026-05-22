package com.pratikpatil.dialpad;

import android.telecom.Call;

/**
 * Singleton that holds the currently active telecom Call object.
 * Shared between MyInCallService and CallScreenActivity.
 */
public class CallManager {

    private static CallManager instance;
    private Call currentCall;

    private CallManager() {}

    public static synchronized CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }

    public Call getCurrentCall() {
        return currentCall;
    }

    public void setCurrentCall(Call call) {
        this.currentCall = call;
    }

    /** Convenience: get display state string */
    public static String stateToString(int state) {
        switch (state) {
            case Call.STATE_DIALING:        return "DIALLING";
            case Call.STATE_RINGING:        return "INCOMING";
            case Call.STATE_ACTIVE:         return "ACTIVE";
            case Call.STATE_HOLDING:        return "ON HOLD";
            case Call.STATE_DISCONNECTED:   return "CALL ENDED";
            case Call.STATE_CONNECTING:     return "CONNECTING";
            default:                        return "CONNECTING";
        }
    }
}
