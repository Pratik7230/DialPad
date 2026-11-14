package com.pratikpatil.dialpad;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.Call;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Posts the three notifications a default dialer must own:
 *  - incoming call (heads-up + full-screen intent so it shows over the lock screen)
 *  - ongoing call (lets the user get back to the call screen after leaving it)
 *  - missed calls (Android expects the default dialer to show these)
 */
public final class CallNotifier {

    static final String CHANNEL_INCOMING = "incoming_calls";
    static final String CHANNEL_ONGOING  = "ongoing_calls";
    static final String CHANNEL_MISSED   = "missed_calls";
    static final String CHANNEL_RECORDING = "call_recording";

    static final int ID_CALL   = 1001;
    static final int ID_MISSED = 1002;

    static final String EXTRA_OPEN_TAB      = "open_tab";
    static final String TAB_HISTORY         = "history";

    private static final String PREFS               = "dialpad_missed";
    private static final String KEY_MISSED_COUNT    = "missed_count";
    private static final String KEY_MISSED_LAST     = "missed_last_name";

    private CallNotifier() {}

    // ==================== CHANNELS ====================

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel incoming = new NotificationChannel(CHANNEL_INCOMING,
                "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        incoming.setSound(null, null);          // CallRinger plays the ringtone itself
        incoming.enableVibration(false);
        incoming.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);

        NotificationChannel ongoing = new NotificationChannel(CHANNEL_ONGOING,
                "Ongoing calls", NotificationManager.IMPORTANCE_LOW);
        ongoing.setSound(null, null);

        NotificationChannel missed = new NotificationChannel(CHANNEL_MISSED,
                "Missed calls", NotificationManager.IMPORTANCE_DEFAULT);

        NotificationChannel recording = new NotificationChannel(CHANNEL_RECORDING,
                "Call recording", NotificationManager.IMPORTANCE_LOW);
        recording.setSound(null, null);

        nm.createNotificationChannel(recording);
        nm.createNotificationChannel(incoming);
        nm.createNotificationChannel(ongoing);
        nm.createNotificationChannel(missed);
    }

    // ==================== CALL NOTIFICATION ====================

    /** Show the right notification for the current primary call, or remove it. */
    static void updateCallNotification(Context ctx) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        CallManager cm = CallManager.getInstance();
        Call primary = cm.getPrimaryCall();

        if (primary == null) {
            nm.cancel(ID_CALL);
            return;
        }
        if (!canPost(ctx)) return;

        String name = cm.getDisplayName(ctx, primary);
        PendingIntent openScreen = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, CallScreenActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b;
        if (primary.getState() == Call.STATE_RINGING) {
            PendingIntent answer = PendingIntent.getActivity(ctx, 1,
                    new Intent(ctx, CallScreenActivity.class)
                            .setAction(CallScreenActivity.ACTION_ANSWER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            b = new NotificationCompat.Builder(ctx, CHANNEL_INCOMING)
                    .setSmallIcon(R.drawable.ic_call_white)
                    .setContentTitle(name)
                    .setContentText(cm.isCallWaiting() ? "Incoming call (call waiting)" : "Incoming call")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setContentIntent(openScreen)
                    .setFullScreenIntent(openScreen, true)
                    .addAction(R.drawable.ic_call_end_white, "Decline",
                            CallActionReceiver.pendingIntent(ctx, CallActionReceiver.ACTION_DECLINE))
                    .addAction(R.drawable.ic_call_answer, "Answer", answer);
        } else {
            String status;
            switch (primary.getState()) {
                case Call.STATE_ACTIVE:  status = "Ongoing call"; break;
                case Call.STATE_HOLDING: status = "On hold"; break;
                default:                 status = "Dialling…"; break;
            }
            b = new NotificationCompat.Builder(ctx, CHANNEL_ONGOING)
                    .setSmallIcon(R.drawable.ic_call_white)
                    .setContentTitle(name)
                    .setContentText(status)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openScreen)
                    .addAction(R.drawable.ic_call_end_white, "Hang up",
                            CallActionReceiver.pendingIntent(ctx, CallActionReceiver.ACTION_HANGUP));

            long connectTime = primary.getDetails() != null ? primary.getDetails().getConnectTimeMillis() : 0;
            if (primary.getState() == Call.STATE_ACTIVE && connectTime > 0) {
                b.setUsesChronometer(true).setWhen(connectTime).setShowWhen(true);
            }
        }
        try {
            nm.notify(ID_CALL, b.build());
        } catch (SecurityException ignored) {}
    }

    // ==================== MISSED CALLS ====================

    static void showMissedCall(Context ctx, String name) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_MISSED_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_MISSED_COUNT, count).putString(KEY_MISSED_LAST, name).apply();

        if (!canPost(ctx)) return;

        PendingIntent openHistory = PendingIntent.getActivity(ctx, 2,
                new Intent(ctx, MainActivity.class)
                        .putExtra(EXTRA_OPEN_TAB, TAB_HISTORY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_MISSED)
                .setSmallIcon(R.drawable.ic_call_missed)
                .setContentTitle(count == 1 ? "Missed call" : count + " missed calls")
                .setContentText(count == 1 ? name : "Latest from " + name)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setNumber(count)
                .setAutoCancel(true)
                .setContentIntent(openHistory)
                .setDeleteIntent(CallActionReceiver.pendingIntent(ctx, CallActionReceiver.ACTION_CLEAR_MISSED));
        try {
            NotificationManagerCompat.from(ctx).notify(ID_MISSED, b.build());
        } catch (SecurityException ignored) {}
    }

    /** Call when the user has seen their call history. */
    static void clearMissedCalls(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MISSED_COUNT, 0).apply();
        NotificationManagerCompat.from(ctx).cancel(ID_MISSED);
    }

    // ==================== UTIL ====================

    private static boolean canPost(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    static String tabFrom(@Nullable Intent intent) {
        return intent != null ? intent.getStringExtra(EXTRA_OPEN_TAB) : null;
    }
}
