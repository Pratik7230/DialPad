package com.pratikpatil.dialpad;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CALL_PHONE = 1;
    private static final int REQUEST_READ_CALL_LOG = 2;
    private static final int REQUEST_WRITE_CALL_LOG = 3;

    private TextView phoneNumberDisplay;
    private ImageButton btnCall, btnDelete;
    private StringBuilder phoneNumber = new StringBuilder();
    private ToneGenerator toneGenerator;

    // Call history views
    private ViewFlipper viewFlipper;
    private RecyclerView rvCallHistory;
    private LinearLayout emptyState;
    private LinearLayout permissionState;
    private MaterialButton btnGrantPermission;
    private CallLogAdapter callLogAdapter;
    private List<CallLogEntry> callLogEntries = new ArrayList<>();
    private CallLogEntry pendingDeleteEntry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Dialpad views
        phoneNumberDisplay = findViewById(R.id.phoneNumberDisplay);
        btnCall = findViewById(R.id.btnCall);
        btnDelete = findViewById(R.id.btnDelete);
        View keyZero = findViewById(R.id.keyZero);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);

        // View flipper
        viewFlipper = findViewById(R.id.viewFlipper);

        // Call history views
        rvCallHistory = findViewById(R.id.rvCallHistory);
        emptyState = findViewById(R.id.emptyState);
        permissionState = findViewById(R.id.permissionState);
        btnGrantPermission = findViewById(R.id.btnGrantPermission);

        // Setup call history RecyclerView
        callLogAdapter = new CallLogAdapter(callLogEntries, new CallLogAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(CallLogEntry entry) {
                openContactProfile(entry);
            }

            @Override
            public void onDeleteClick(CallLogEntry entry) {
                handleDeleteCallLog(entry);
            }
        });
        rvCallHistory.setLayoutManager(new LinearLayoutManager(this));
        rvCallHistory.setAdapter(callLogAdapter);

        // Grant permission button
        btnGrantPermission.setOnClickListener(v -> requestCallLogPermission());

        // Bottom navigation
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_call);
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_call) {
                viewFlipper.setDisplayedChild(0); // Show dialpad
                return true;
            }
            if (itemId == R.id.nav_history) {
                viewFlipper.setDisplayedChild(1); // Show history
                loadCallHistory();
                return true;
            }
            if (itemId == R.id.nav_contacts) {
                Toast.makeText(this, R.string.toast_contacts_coming_soon, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (itemId == R.id.nav_settings) {
                Toast.makeText(this, R.string.toast_settings_coming_soon, Toast.LENGTH_SHORT).show();
                return true;
            }
            return true;
        });

        // Delete last digit on click
        btnDelete.setOnClickListener(v -> {
            if (phoneNumber.length() > 0) {
                phoneNumber.deleteCharAt(phoneNumber.length() - 1);
                updateDisplay();
            }
        });

        if (keyZero != null) {
            keyZero.setOnLongClickListener(this::zeroLongClick);
        }

        // Long press to clear all
        btnDelete.setOnLongClickListener(v -> {
            phoneNumber.setLength(0);
            updateDisplay();
            return true;
        });

        // Call button
        btnCall.setOnClickListener(v -> {
            String number = phoneNumber.toString();
            if (number.isEmpty()) {
                return;
            }

            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + number));

            if (ActivityCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{Manifest.permission.CALL_PHONE},
                        REQUEST_CALL_PHONE);
                return;
            }

            startActivity(intent);
        });

        updateDisplay();
    }

    private void openContactProfile(CallLogEntry entry) {
        if (entry == null) {
            return;
        }

        Intent intent = new Intent(this, ContactProfileActivity.class);
        intent.putExtra(ContactProfileActivity.EXTRA_NUMBER, entry.getNumber());
        intent.putExtra(ContactProfileActivity.EXTRA_NAME, entry.getName());
        startActivity(intent);
    }

    /**
     * Loads call history from the device. Shows permission state if not granted.
     */
    private void loadCallHistory() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            // Show permission request state
            rvCallHistory.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            permissionState.setVisibility(View.VISIBLE);
            return;
        }

        // Permission granted, hide permission state
        permissionState.setVisibility(View.GONE);

        callLogEntries.clear();

        String[] projection = {
            CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };

        try (Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {

            if (cursor != null) {
                int idIdx = cursor.getColumnIndex(CallLog.Calls._ID);
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);

                int count = 0;
                int maxEntries = 100; // Limit to recent 100 entries

                while (cursor.moveToNext() && count < maxEntries) {
                    long id = idIdx != -1 ? cursor.getLong(idIdx) : 0L;
                    String number = cursor.getString(numberIdx);
                    String name = cursor.getString(nameIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);
                    long duration = cursor.getLong(durationIdx);

                    callLogEntries.add(new CallLogEntry(id, number, name, type, date, duration));
                    count++;
                }
            }
        } catch (SecurityException e) {
            // Fallback: show permission state
            rvCallHistory.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            permissionState.setVisibility(View.VISIBLE);
            return;
        }

        callLogAdapter.updateData(callLogEntries);

        updateHistoryVisibility();
    }

    /**
     * Requests READ_CALL_LOG permission from the user.
     */
    private void requestCallLogPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.READ_CALL_LOG},
                REQUEST_READ_CALL_LOG);
    }

    private void handleDeleteCallLog(CallLogEntry entry) {
        if (entry == null) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDeleteEntry = entry;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_CALL_LOG},
                    REQUEST_WRITE_CALL_LOG);
            return;
        }

        deleteCallLogEntry(entry);
    }

    private void deleteCallLogEntry(CallLogEntry entry) {
        if (entry.getId() <= 0) {
            Toast.makeText(this, R.string.delete_call_log_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int rows = getContentResolver().delete(
                    CallLog.Calls.CONTENT_URI,
                    CallLog.Calls._ID + "=?",
                    new String[]{String.valueOf(entry.getId())});

            if (rows > 0) {
                removeEntryById(entry.getId());
                callLogAdapter.updateData(callLogEntries);
                updateHistoryVisibility();
                Toast.makeText(this, R.string.delete_call_log_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.delete_call_log_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.delete_call_log_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void removeEntryById(long id) {
        for (int i = 0; i < callLogEntries.size(); i++) {
            if (callLogEntries.get(i).getId() == id) {
                callLogEntries.remove(i);
                break;
            }
        }
    }

    private void updateHistoryVisibility() {
        if (callLogEntries.isEmpty()) {
            rvCallHistory.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            rvCallHistory.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_READ_CALL_LOG) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadCallHistory();
            } else {
                Toast.makeText(this, "Call log permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_WRITE_CALL_LOG) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingDeleteEntry != null) {
                    deleteCallLogEntry(pendingDeleteEntry);
                    pendingDeleteEntry = null;
                }
            } else {
                pendingDeleteEntry = null;
                Toast.makeText(this, R.string.delete_call_log_permission_denied, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CALL_PHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Retry the call
                String number = phoneNumber.toString();
                if (!number.isEmpty()) {
                    Intent intent = new Intent(Intent.ACTION_CALL);
                    intent.setData(Uri.parse("tel:" + number));
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                            == PackageManager.PERMISSION_GRANTED) {
                        startActivity(intent);
                    }
                }
            }
        }
    }

    /**
     * Called when any dial pad number button is clicked.
     * Uses the tag attribute to determine which digit was pressed.
     */
    public void numberClick(View view) {
        String digit = (String) view.getTag();
        if (digit != null) {
            playDialTone(digit);
            phoneNumber.append(digit);
            updateDisplay();
        }
    }

    /**
     * Long-press on zero inserts '+' for international numbers.
     */
    public boolean zeroLongClick(View view) {
        phoneNumber.append("+");
        updateDisplay();
        return true;
    }

    /**
     * Updates the phone number display and toggles delete button enabled state.
     */
    private void updateDisplay() {
        String formatted = formatPhoneNumber(phoneNumber.toString());
        phoneNumberDisplay.setText(formatted);

        // Enable/disable delete button based on whether there's input
        if (phoneNumber.length() > 0) {
            btnDelete.setEnabled(true);
            btnDelete.setAlpha(1f);
        } else {
            btnDelete.setEnabled(false);
            btnDelete.setAlpha(0.35f);
            phoneNumberDisplay.setText("");
        }
    }

    /**
     * Formats the phone number with spaces for readability.
     * Example: 9028471862 -> 90284 71862
     */
    private String formatPhoneNumber(String number) {
        if (number.length() <= 5) {
            return number;
        }

        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            if (i == 5) {
                formatted.append(' ');
            }
            formatted.append(number.charAt(i));
        }
        return formatted.toString();
    }

    private void playDialTone(String digit) {
        if (toneGenerator == null) {
            return;
        }

        int toneType;
        switch (digit) {
            case "0":
                toneType = ToneGenerator.TONE_DTMF_0;
                break;
            case "1":
                toneType = ToneGenerator.TONE_DTMF_1;
                break;
            case "2":
                toneType = ToneGenerator.TONE_DTMF_2;
                break;
            case "3":
                toneType = ToneGenerator.TONE_DTMF_3;
                break;
            case "4":
                toneType = ToneGenerator.TONE_DTMF_4;
                break;
            case "5":
                toneType = ToneGenerator.TONE_DTMF_5;
                break;
            case "6":
                toneType = ToneGenerator.TONE_DTMF_6;
                break;
            case "7":
                toneType = ToneGenerator.TONE_DTMF_7;
                break;
            case "8":
                toneType = ToneGenerator.TONE_DTMF_8;
                break;
            case "9":
                toneType = ToneGenerator.TONE_DTMF_9;
                break;
            case "*":
                toneType = ToneGenerator.TONE_DTMF_S;
                break;
            case "#":
                toneType = ToneGenerator.TONE_DTMF_P;
                break;
            default:
                return;
        }

        toneGenerator.startTone(toneType, 120);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }
}