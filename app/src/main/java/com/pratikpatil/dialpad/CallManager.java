package com.pratikpatil.dialpad;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for every live telecom Call (supports call waiting,
 * hold/swap and conference) plus the current audio route.
 *
 * All methods are expected to run on the main thread — Telecom delivers
 * InCallService callbacks there, and the UI reads from there.
 */
public class CallManager {

    /** Observers (the call screen) get told whenever calls or audio change. */
    public interface Listener {
        void onCallsChanged();
        void onAudioStateChanged(@Nullable CallAudioState state);
    }

    private static CallManager instance;

    private final List<Call> calls = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> nameCache = new HashMap<>();

    @Nullable private InCallService service;
    @Nullable private CallAudioState audioState;
    @Nullable private CallRinger ringer;

    private CallManager() {}

    public static synchronized CallManager getInstance() {
        if (instance == null) instance = new CallManager();
        return instance;
    }

    // ==================== SERVICE / LISTENERS ====================

    void setService(@Nullable InCallService service, @Nullable CallRinger ringer) {
        this.service = service;
        this.ringer = ringer;
        if (service == null) audioState = null;
    }

    /** Stop the ringtone/vibration for the current ringing call (e.g. volume key). */
    public void silenceRinger() {
        if (ringer != null) ringer.silence();
    }

    public void addListener(Listener l)    { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    void notifyCallsChanged() {
        for (Listener l : listeners) l.onCallsChanged();
    }

    // ==================== CALL LIST ====================

    void addCall(Call call) {
        if (!calls.contains(call)) calls.add(call);
    }

    void removeCall(Call call) {
        calls.remove(call);
        if (calls.isEmpty()) nameCache.clear();
    }

    public List<Call> getCalls() {
        return new ArrayList<>(calls);
    }

    public boolean hasCalls() {
        return !calls.isEmpty();
    }

    @Nullable
    public Call getCallInState(int... states) {
        for (int state : states) {
            for (Call c : calls) {
                // Skip calls that are children of a conference: the parent represents them
                if (c.getParent() != null) continue;
                if (c.getState() == state) return c;
            }
        }
        return null;
    }

    @Nullable public Call getRingingCall() { return getCallInState(Call.STATE_RINGING); }

    /**
     * The call the user is focused on: a ringing call wins (call waiting),
     * then one being dialled, then the active one, then a held one.
     */
    @Nullable
    public Call getPrimaryCall() {
        return getCallInState(
                Call.STATE_RINGING,
                Call.STATE_DIALING,
                Call.STATE_CONNECTING,
                Call.STATE_SELECT_PHONE_ACCOUNT,
                Call.STATE_ACTIVE,
                Call.STATE_HOLDING);
    }

    /** Any other live top-level call besides {@code primary} (the one on hold, typically). */
    @Nullable
    public Call getSecondaryCall(@Nullable Call primary) {
        for (Call c : calls) {
            if (c == primary || c.getParent() != null) continue;
            int s = c.getState();
            if (s != Call.STATE_DISCONNECTED && s != Call.STATE_DISCONNECTING) return c;
        }
        return null;
    }

    /** True when another call is already connected — the new ringing call is "call waiting". */
    public boolean isCallWaiting() {
        return getRingingCall() != null
                && getCallInState(Call.STATE_ACTIVE, Call.STATE_HOLDING) != null;
    }

    // ==================== AUDIO ====================

    void setAudioState(@Nullable CallAudioState state) {
        audioState = state;
        for (Listener l : listeners) l.onAudioStateChanged(state);
    }

    @Nullable
    public CallAudioState getAudioState() {
        return audioState;
    }

    /** Mute through Telecom, so it applies to the call itself (not just the local mic). */
    public void setMuted(boolean muted) {
        if (service != null) service.setMuted(muted);
    }

    /** One of CallAudioState.ROUTE_EARPIECE / SPEAKER / BLUETOOTH / WIRED_HEADSET. */
    public void setAudioRoute(int route) {
        if (service != null) service.setAudioRoute(route);
    }

    public boolean isMuted() {
        return audioState != null && audioState.isMuted();
    }

    public int getRoute() {
        return audioState != null ? audioState.getRoute() : CallAudioState.ROUTE_EARPIECE;
    }

    public int getSupportedRoutes() {
        return audioState != null
                ? audioState.getSupportedRouteMask()
                : CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER;
    }

    // ==================== CALL INFO HELPERS ====================

    public static String getNumber(@Nullable Call call) {
        if (call == null || call.getDetails() == null) return "";
        Uri handle = call.getDetails().getHandle();
        return handle != null ? handle.getSchemeSpecificPart() : "";
    }

    /** True for a call placed to the "voicemail:" URI. */
    public static boolean isVoicemail(@Nullable Call call) {
        if (call == null || call.getDetails() == null || call.getDetails().getHandle() == null) return false;
        return android.telecom.PhoneAccount.SCHEME_VOICEMAIL.equals(call.getDetails().getHandle().getScheme());
    }

    /** Best display name: contact name → network caller name → number → "Unknown". */
    public String getDisplayName(Context ctx, @Nullable Call call) {
        if (call == null) return "Unknown";
        if (call.getDetails() != null && call.getDetails().hasProperty(Call.Details.PROPERTY_CONFERENCE)) {
            return "Conference call";
        }
        if (isVoicemail(call)) return "Voicemail";
        String number = getNumber(call);
        String contact = lookupContactName(ctx, number);
        if (!TextUtils.isEmpty(contact)) return contact;
        if (call.getDetails() != null && !TextUtils.isEmpty(call.getDetails().getCallerDisplayName())) {
            return call.getDetails().getCallerDisplayName();
        }
        return !TextUtils.isEmpty(number) ? number : "Unknown";
    }

    @Nullable
    private String lookupContactName(Context ctx, String number) {
        if (TextUtils.isEmpty(number)) return null;
        if (nameCache.containsKey(number)) return nameCache.get(number);
        String name = null;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {}
        }
        nameCache.put(number, name);
        return name;
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
