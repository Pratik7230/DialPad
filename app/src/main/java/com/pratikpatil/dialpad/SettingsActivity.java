package com.pratikpatil.dialpad;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "dialpad_settings";

    // Preference keys
    static final String PREF_CALL_RECORDING = "call_recording";
    static final String PREF_AUTO_RECORD    = "auto_record";
    static final String PREF_RECORDING_BEEP = "recording_beep";
    static final String PREF_RECORDING_FORMAT = "recording_format";
    static final String PREF_DARK_THEME     = "dark_theme";
    static final String PREF_KEYPAD_SOUNDS  = "keypad_sounds";
    static final String PREF_VIBRATE        = "vibrate";
    static final String PREF_CALLER_ID      = "caller_id";
    static final String PREF_BLOCK_UNKNOWN  = "block_unknown";
    static final String PREF_FLASH_ON_CALL  = "flash_on_call";

    // Format options
    private static final String FORMAT_M4A = "M4A";
    private static final String FORMAT_MP3 = "MP3";
    private static final String FORMAT_WAV = "WAV";

    private SharedPreferences prefs;

    // Recording views
    private SwitchMaterial switchCallRecording;
    private SwitchMaterial switchAutoRecord;
    private SwitchMaterial switchRecordingBeep;
    private TextView tvRecordingStatus;
    private TextView tvRecordingFormat;
    private TextView tvStorageUsed;
    private ProgressBar progressStorage;

    // Expandable recording option views
    private View dividerRecordingOptions;
    private LinearLayout rowRecordingFormat;
    private View dividerRecordingStorage;
    private LinearLayout rowRecordingStorage;
    private View dividerAutoRecord;
    private LinearLayout rowAutoRecord;
    private View dividerRecordBeep;
    private LinearLayout rowRecordingBeep;
    private LinearLayout bannerRecordingInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        setupViews();
        loadPreferences();
        setupListeners();
        calculateStorageUsed();
    }

    private void setupViews() {
        // Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Recording views
        switchCallRecording = findViewById(R.id.switchCallRecording);
        switchAutoRecord    = findViewById(R.id.switchAutoRecord);
        switchRecordingBeep = findViewById(R.id.switchRecordingBeep);
        tvRecordingStatus   = findViewById(R.id.tvRecordingStatus);
        tvRecordingFormat   = findViewById(R.id.tvRecordingFormat);
        tvStorageUsed       = findViewById(R.id.tvStorageUsed);
        progressStorage     = findViewById(R.id.progressStorage);

        dividerRecordingOptions = findViewById(R.id.dividerRecordingOptions);
        rowRecordingFormat      = findViewById(R.id.rowRecordingFormat);
        dividerRecordingStorage = findViewById(R.id.dividerRecordingStorage);
        rowRecordingStorage     = findViewById(R.id.rowRecordingStorage);
        dividerAutoRecord       = findViewById(R.id.dividerAutoRecord);
        rowAutoRecord           = findViewById(R.id.rowAutoRecord);
        dividerRecordBeep       = findViewById(R.id.dividerRecordBeep);
        rowRecordingBeep        = findViewById(R.id.rowRecordingBeep);
        bannerRecordingInfo     = findViewById(R.id.bannerRecordingInfo);
    }

    private void loadPreferences() {
        boolean recordingEnabled = prefs.getBoolean(PREF_CALL_RECORDING, false);
        boolean autoRecord       = prefs.getBoolean(PREF_AUTO_RECORD, true);
        boolean recordingBeep    = prefs.getBoolean(PREF_RECORDING_BEEP, false);
        String format            = prefs.getString(PREF_RECORDING_FORMAT, FORMAT_M4A);
        boolean darkTheme        = prefs.getBoolean(PREF_DARK_THEME, false);
        boolean keypadSounds     = prefs.getBoolean(PREF_KEYPAD_SOUNDS, true);
        boolean vibrate          = prefs.getBoolean(PREF_VIBRATE, true);
        boolean callerId         = prefs.getBoolean(PREF_CALLER_ID, true);
        boolean blockUnknown     = prefs.getBoolean(PREF_BLOCK_UNKNOWN, false);
        boolean flashOnCall      = prefs.getBoolean(PREF_FLASH_ON_CALL, false);

        switchCallRecording.setChecked(recordingEnabled);
        switchAutoRecord.setChecked(autoRecord);
        switchRecordingBeep.setChecked(recordingBeep);
        updateFormatText(format);
        setRecordingOptionsVisible(recordingEnabled, false);

        SwitchMaterial switchDarkTheme    = findViewById(R.id.switchDarkTheme);
        SwitchMaterial switchKeypadSounds = findViewById(R.id.switchKeypadSounds);
        SwitchMaterial switchVibrate      = findViewById(R.id.switchVibrate);
        SwitchMaterial switchCallerId     = findViewById(R.id.switchCallerId);
        SwitchMaterial switchBlockUnknown = findViewById(R.id.switchBlockUnknown);
        SwitchMaterial switchFlashOnCall  = findViewById(R.id.switchFlashOnCall);

        switchDarkTheme.setChecked(darkTheme);
        switchKeypadSounds.setChecked(keypadSounds);
        switchVibrate.setChecked(vibrate);
        switchCallerId.setChecked(callerId);
        switchBlockUnknown.setChecked(blockUnknown);
        switchFlashOnCall.setChecked(flashOnCall);
    }

    private void setupListeners() {
        // ===== CALL RECORDING =====
        LinearLayout rowCallRecording = findViewById(R.id.rowCallRecording);
        rowCallRecording.setOnClickListener(v ->
                switchCallRecording.setChecked(!switchCallRecording.isChecked()));

        switchCallRecording.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_CALL_RECORDING, isChecked).apply();
            setRecordingOptionsVisible(isChecked, true);
            updateRecordingStatusText(isChecked);
            String msg = isChecked
                    ? getString(R.string.toast_recording_enabled)
                    : getString(R.string.toast_recording_disabled);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // Recording format picker
        rowRecordingFormat.setOnClickListener(v -> showFormatPicker());

        // Recording storage row
        LinearLayout rowRecordingStorageView = findViewById(R.id.rowRecordingStorage);
        rowRecordingStorageView.setOnClickListener(v ->
                Toast.makeText(this, R.string.toast_feature_note, Toast.LENGTH_SHORT).show());

        // Auto-record toggle
        LinearLayout rowAutoRecordView = findViewById(R.id.rowAutoRecord);
        rowAutoRecordView.setOnClickListener(v ->
                switchAutoRecord.setChecked(!switchAutoRecord.isChecked()));
        switchAutoRecord.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(PREF_AUTO_RECORD, isChecked).apply());

        // Recording beep toggle
        LinearLayout rowRecordingBeepView = findViewById(R.id.rowRecordingBeep);
        rowRecordingBeepView.setOnClickListener(v ->
                switchRecordingBeep.setChecked(!switchRecordingBeep.isChecked()));
        switchRecordingBeep.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(PREF_RECORDING_BEEP, isChecked).apply());

        // ===== DISPLAY & SOUND =====
        SwitchMaterial switchDarkTheme = findViewById(R.id.switchDarkTheme);
        LinearLayout rowDarkTheme = findViewById(R.id.rowDarkTheme);
        rowDarkTheme.setOnClickListener(v ->
                switchDarkTheme.setChecked(!switchDarkTheme.isChecked()));
        switchDarkTheme.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean(PREF_DARK_THEME, isChecked).apply();
            AppCompatDelegate.setDefaultNightMode(isChecked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });

        SwitchMaterial switchKeypadSounds = findViewById(R.id.switchKeypadSounds);
        LinearLayout rowKeypadSounds = findViewById(R.id.rowKeypadSounds);
        rowKeypadSounds.setOnClickListener(v ->
                switchKeypadSounds.setChecked(!switchKeypadSounds.isChecked()));
        switchKeypadSounds.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean(PREF_KEYPAD_SOUNDS, isChecked).apply();
            Toast.makeText(this, R.string.toast_feature_note, Toast.LENGTH_SHORT).show();
        });

        SwitchMaterial switchVibrate = findViewById(R.id.switchVibrate);
        LinearLayout rowVibrate = findViewById(R.id.rowVibrate);
        rowVibrate.setOnClickListener(v ->
                switchVibrate.setChecked(!switchVibrate.isChecked()));
        switchVibrate.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(PREF_VIBRATE, isChecked).apply());

        // ===== CALL SETTINGS =====
        SwitchMaterial switchCallerId = findViewById(R.id.switchCallerId);
        LinearLayout rowCallerId = findViewById(R.id.rowCallerId);
        rowCallerId.setOnClickListener(v ->
                switchCallerId.setChecked(!switchCallerId.isChecked()));
        switchCallerId.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean(PREF_CALLER_ID, isChecked).apply();
            Toast.makeText(this, R.string.toast_feature_note, Toast.LENGTH_SHORT).show();
        });

        SwitchMaterial switchBlockUnknown = findViewById(R.id.switchBlockUnknown);
        LinearLayout rowBlockUnknown = findViewById(R.id.rowBlockUnknown);
        rowBlockUnknown.setOnClickListener(v ->
                switchBlockUnknown.setChecked(!switchBlockUnknown.isChecked()));
        switchBlockUnknown.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(PREF_BLOCK_UNKNOWN, isChecked).apply());

        SwitchMaterial switchFlashOnCall = findViewById(R.id.switchFlashOnCall);
        LinearLayout rowFlashOnCall = findViewById(R.id.rowFlashOnCall);
        rowFlashOnCall.setOnClickListener(v ->
                switchFlashOnCall.setChecked(!switchFlashOnCall.isChecked()));
        switchFlashOnCall.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean(PREF_FLASH_ON_CALL, isChecked).apply());

        // ===== STORAGE =====
        LinearLayout rowClearRecordings = findViewById(R.id.rowClearRecordings);
        rowClearRecordings.setOnClickListener(v -> confirmClearRecordings());

        // ===== ABOUT =====
        LinearLayout rowPrivacyPolicy = findViewById(R.id.rowPrivacyPolicy);
        rowPrivacyPolicy.setOnClickListener(v ->
                Toast.makeText(this, "Privacy policy not available yet", Toast.LENGTH_SHORT).show());
    }

    // ======= Recording options expand/collapse =======

    private void setRecordingOptionsVisible(boolean visible, boolean animate) {
        int targetVis = visible ? View.VISIBLE : View.GONE;

        dividerRecordingOptions.setVisibility(targetVis);
        rowRecordingFormat.setVisibility(targetVis);
        dividerRecordingStorage.setVisibility(targetVis);
        rowRecordingStorage.setVisibility(targetVis);
        dividerAutoRecord.setVisibility(targetVis);
        rowAutoRecord.setVisibility(targetVis);
        dividerRecordBeep.setVisibility(targetVis);
        rowRecordingBeep.setVisibility(targetVis);
        bannerRecordingInfo.setVisibility(targetVis);

        updateRecordingStatusText(visible);
    }

    private void updateRecordingStatusText(boolean enabled) {
        tvRecordingStatus.setText(enabled
                ? R.string.setting_recording_on
                : R.string.setting_recording_off);
        tvRecordingStatus.setTextColor(enabled
                ? getColor(R.color.settings_accent)
                : getColor(R.color.settings_text_secondary));
    }

    private void updateFormatText(String format) {
        String display;
        switch (format) {
            case FORMAT_MP3: display = getString(R.string.recording_format_mp3); break;
            case FORMAT_WAV: display = getString(R.string.recording_format_wav); break;
            default:         display = getString(R.string.recording_format_m4a); break;
        }
        tvRecordingFormat.setText(display);
    }

    private void showFormatPicker() {
        String currentFormat = prefs.getString(PREF_RECORDING_FORMAT, FORMAT_M4A);
        String[] formats = {
                getString(R.string.recording_format_m4a),
                getString(R.string.recording_format_mp3),
                getString(R.string.recording_format_wav)
        };
        String[] formatKeys = { FORMAT_M4A, FORMAT_MP3, FORMAT_WAV };

        int checkedItem = 0;
        for (int i = 0; i < formatKeys.length; i++) {
            if (formatKeys[i].equals(currentFormat)) { checkedItem = i; break; }
        }

        final int[] selectedIndex = { checkedItem };
        new AlertDialog.Builder(this)
                .setTitle(R.string.recording_format_title)
                .setSingleChoiceItems(formats, checkedItem, (dialog, which) ->
                        selectedIndex[0] = which)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String selected = formatKeys[selectedIndex[0]];
                    prefs.edit().putString(PREF_RECORDING_FORMAT, selected).apply();
                    updateFormatText(selected);
                    Toast.makeText(this, R.string.toast_pref_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ======= Storage =======

    private void calculateStorageUsed() {
        File recordingsDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");

        long totalBytes = 0;
        if (recordingsDir.exists() && recordingsDir.isDirectory()) {
            File[] files = recordingsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    totalBytes += f.length();
                }
            }
        }

        long totalMB = totalBytes / (1024 * 1024);
        String usedText;
        if (totalMB < 1) {
            usedText = totalBytes / 1024 + " KB used";
        } else if (totalMB < 1024) {
            usedText = totalMB + " MB used";
        } else {
            usedText = String.format("%.1f GB used", totalMB / 1024.0);
        }
        tvStorageUsed.setText(usedText);

        // Animate progress bar (assume 2GB max for demo)
        long maxBytes = 2L * 1024 * 1024 * 1024;
        int progress = (int) Math.min(100, (totalBytes * 100) / Math.max(maxBytes, 1));
        animateProgressBar(progress);
    }

    private void animateProgressBar(int targetProgress) {
        ValueAnimator animator = ValueAnimator.ofInt(0, targetProgress);
        animator.setDuration(800);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation ->
                progressStorage.setProgress((int) animation.getAnimatedValue()));
        animator.start();
    }

    private void confirmClearRecordings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_recordings_confirm_title)
                .setMessage(R.string.clear_recordings_confirm_msg)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> clearAllRecordings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearAllRecordings() {
        File recordingsDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings");

        boolean anyDeleted = false;
        if (recordingsDir.exists() && recordingsDir.isDirectory()) {
            File[] files = recordingsDir.listFiles();
            if (files != null && files.length > 0) {
                for (File f : files) {
                    if (f.isFile()) {
                        f.delete();
                        anyDeleted = true;
                    }
                }
            }
        }

        if (anyDeleted) {
            Toast.makeText(this, R.string.clear_recordings_success, Toast.LENGTH_SHORT).show();
            calculateStorageUsed();
        } else {
            Toast.makeText(this, R.string.clear_recordings_empty, Toast.LENGTH_SHORT).show();
        }
    }

    // ======= Public getter for settings from other activities =======

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
