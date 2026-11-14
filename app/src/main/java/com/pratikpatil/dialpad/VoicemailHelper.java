package com.pratikpatil.dialpad;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Voicemail entry points: call voicemail (long-press 1, Settings, notification)
 * and open the carrier's voicemail settings.
 */
public final class VoicemailHelper {

    /** Telecom resolves this to the SIM's voicemail number. */
    static final Uri VOICEMAIL_URI = Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null);

    private VoicemailHelper() {}

    /** The configured voicemail number, or null if none / no permission. */
    @Nullable
    static String getVoicemailNumber(Context ctx) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            String n = tm != null ? tm.getVoiceMailNumber() : null;
            return TextUtils.isEmpty(n) ? null : n;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Dial voicemail, or explain how to set it up if the SIM has no voicemail number. */
    static void callVoicemail(Activity activity, int callPermissionRequestCode) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CALL_PHONE}, callPermissionRequestCode);
            return;
        }
        boolean hasPhoneState = ContextCompat.checkSelfPermission(activity,
                Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        if (hasPhoneState && getVoicemailNumber(activity) == null) {
            new AlertDialog.Builder(activity)
                    .setTitle("Voicemail isn't set up")
                    .setMessage("Your SIM has no voicemail number. Add one in voicemail settings, "
                            + "or contact your carrier.")
                    .setPositiveButton("Voicemail settings", (d, w) -> openVoicemailSettings(activity))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        try {
            TelecomManager telecom = (TelecomManager) activity.getSystemService(Context.TELECOM_SERVICE);
            telecom.placeCall(VOICEMAIL_URI, new Bundle());
        } catch (SecurityException e) {
            Toast.makeText(activity, "Can't call voicemail", Toast.LENGTH_SHORT).show();
        }
    }

    /** Carrier / system voicemail settings (number, greeting, notifications). */
    static void openVoicemailSettings(Context ctx) {
        Intent[] candidates = {
                new Intent(TelephonyManager.ACTION_CONFIGURE_VOICEMAIL),
                new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)
        };
        for (Intent i : candidates) {
            try {
                if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {}
        }
        Toast.makeText(ctx, "Voicemail settings aren't available on this phone", Toast.LENGTH_SHORT).show();
    }

    /** Settings / long-press menu: call voicemail or open its settings. */
    static void showVoicemailMenu(Activity activity, int callPermissionRequestCode) {
        String number = getVoicemailNumber(activity);
        new AlertDialog.Builder(activity)
                .setTitle(number != null ? "Voicemail · " + number : "Voicemail")
                .setItems(new String[]{"Call voicemail", "Voicemail settings"}, (d, which) -> {
                    if (which == 0) callVoicemail(activity, callPermissionRequestCode);
                    else openVoicemailSettings(activity);
                })
                .show();
    }
}
