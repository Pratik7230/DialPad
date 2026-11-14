package com.pratikpatil.dialpad;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;

/**
 * Handles notification buttons that don't need to open UI:
 * Decline (incoming), Hang up (ongoing) and dismissing the missed-call notification.
 * Answer opens CallScreenActivity directly instead (Android 12+ blocks
 * starting activities from a receiver).
 */
public class CallActionReceiver extends BroadcastReceiver {

    static final String ACTION_DECLINE      = "com.pratikpatil.dialpad.action.DECLINE";
    static final String ACTION_HANGUP       = "com.pratikpatil.dialpad.action.HANGUP";
    static final String ACTION_CLEAR_MISSED = "com.pratikpatil.dialpad.action.CLEAR_MISSED";

    static PendingIntent pendingIntent(Context ctx, String action) {
        Intent i = new Intent(ctx, CallActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        CallManager cm = CallManager.getInstance();

        switch (action) {
            case ACTION_DECLINE: {
                Call ringing = cm.getRingingCall();
                if (ringing != null) ringing.reject(false, null);
                break;
            }
            case ACTION_HANGUP: {
                Call primary = cm.getPrimaryCall();
                if (primary != null) primary.disconnect();
                break;
            }
            case ACTION_CLEAR_MISSED:
                CallNotifier.clearMissedCalls(context);
                break;
        }
    }
}
