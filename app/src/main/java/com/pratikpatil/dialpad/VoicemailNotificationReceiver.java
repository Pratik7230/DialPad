package com.pratikpatil.dialpad;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Shows the "new voicemail" notification.
 *
 * When this app is the default dialer, Telephony stops showing its own
 * voicemail notification and sends ACTION_SHOW_VOICEMAIL_NOTIFICATION here
 * instead (Android 8.0+). Count 0 means voicemail was listened to → clear it.
 * The action is a protected broadcast, so only the system can send it.
 */
public class VoicemailNotificationReceiver extends BroadcastReceiver {

    static final int ID_VOICEMAIL = 1004;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION.equals(intent.getAction())) return;

        int count = intent.getIntExtra(TelephonyManager.EXTRA_NOTIFICATION_COUNT, -1);
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (count == 0) {
            nm.cancel(ID_VOICEMAIL);
            return;
        }

        CallNotifier.createChannels(context);

        String number = intent.getStringExtra(TelephonyManager.EXTRA_VOICEMAIL_NUMBER);
        PendingIntent callVoicemail = intent.getParcelableExtra(TelephonyManager.EXTRA_CALL_VOICEMAIL_INTENT);
        PendingIntent settings = intent.getParcelableExtra(TelephonyManager.EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT);
        boolean isRefresh = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && intent.getBooleanExtra(TelephonyManager.EXTRA_IS_REFRESH, false);

        // Fallback when Telephony doesn't hand us a ready-made "call voicemail" intent
        if (callVoicemail == null) {
            callVoicemail = PendingIntent.getActivity(context, 4,
                    new Intent(Intent.ACTION_CALL, VoicemailHelper.VOICEMAIL_URI)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String title = count > 1 ? count + " new voicemails" : "New voicemail";
        String text = number != null && !number.isEmpty()
                ? "Tap to call voicemail (" + number + ")" : "Tap to call voicemail";

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CallNotifier.CHANNEL_VOICEMAIL)
                .setSmallIcon(R.drawable.ic_voicemail)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setNumber(Math.max(count, 0))
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(isRefresh)               // boot / SIM refresh: don't buzz again
                .setContentIntent(callVoicemail)
                .addAction(R.drawable.ic_call_white, "Call voicemail", callVoicemail);
        if (settings != null) b.addAction(R.drawable.ic_voicemail, "Settings", settings);

        try {
            nm.notify(ID_VOICEMAIL, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted
        }
    }
}
