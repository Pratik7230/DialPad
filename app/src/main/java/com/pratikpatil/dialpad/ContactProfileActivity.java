package com.pratikpatil.dialpad;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class ContactProfileActivity extends AppCompatActivity {

    public static final String EXTRA_NUMBER = "extra_number";
    public static final String EXTRA_NAME = "extra_name";

    private static final int REQUEST_READ_CALL_LOG = 10;
    private static final int REQUEST_CALL_PHONE = 11;

    private TextView tvContactName;
    private TextView tvContactNumber;
    private RecyclerView rvCallHistory;
    private LinearLayout emptyState;
    private LinearLayout permissionState;
    private MaterialButton btnGrantPermission;
    private MaterialButton btnCall;
    private MaterialButton btnMessage;

    private CallLogAdapter callLogAdapter;
    private final List<CallLogEntry> callLogEntries = new ArrayList<>();

    private String phoneNumber;
    private String contactName;
    private String pendingCallNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_profile);

        phoneNumber = getIntent().getStringExtra(EXTRA_NUMBER);
        contactName = getIntent().getStringExtra(EXTRA_NAME);

        ImageButton btnBack = findViewById(R.id.btnBack);
        tvContactName = findViewById(R.id.tvContactName);
        tvContactNumber = findViewById(R.id.tvContactNumber);
        btnCall = findViewById(R.id.btnCallContact);
        btnMessage = findViewById(R.id.btnMessageContact);

        rvCallHistory = findViewById(R.id.rvContactCallHistory);
        emptyState = findViewById(R.id.emptyState);
        permissionState = findViewById(R.id.permissionState);
        btnGrantPermission = findViewById(R.id.btnGrantPermission);

        btnBack.setOnClickListener(v -> finish());

        updateHeader();

        btnCall.setOnClickListener(v -> placeCall());
        btnMessage.setOnClickListener(v -> sendMessage());

        callLogAdapter = new CallLogAdapter(callLogEntries, null, false);
        rvCallHistory.setLayoutManager(new LinearLayoutManager(this));
        rvCallHistory.setAdapter(callLogAdapter);

        btnGrantPermission.setOnClickListener(v -> requestCallLogPermission());

        loadCallHistoryForNumber();
    }

    private void updateHeader() {
        String displayName;
        if (!TextUtils.isEmpty(contactName)) {
            displayName = contactName;
        } else if (!TextUtils.isEmpty(phoneNumber)) {
            displayName = phoneNumber;
        } else {
            displayName = getString(R.string.unknown_number);
        }

        tvContactName.setText(displayName);

        if (!TextUtils.isEmpty(phoneNumber)) {
            tvContactNumber.setVisibility(View.VISIBLE);
            tvContactNumber.setText(phoneNumber);
        } else {
            tvContactNumber.setVisibility(View.GONE);
        }

        boolean hasNumber = !TextUtils.isEmpty(phoneNumber);
        btnCall.setEnabled(hasNumber);
        btnMessage.setEnabled(hasNumber);
        btnCall.setAlpha(hasNumber ? 1f : 0.4f);
        btnMessage.setAlpha(hasNumber ? 1f : 0.4f);
    }

    private void placeCall() {
        if (TextUtils.isEmpty(phoneNumber)) {
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = phoneNumber;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CALL_PHONE},
                    REQUEST_CALL_PHONE);
            return;
        }

        startCall(phoneNumber);
    }

    private void startCall(String number) {
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + number));
        startActivity(intent);
    }

    private void sendMessage() {
        if (TextUtils.isEmpty(phoneNumber)) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + phoneNumber));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_message_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCallHistoryForNumber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            showPermissionState();
            return;
        }

        if (TextUtils.isEmpty(phoneNumber)) {
            callLogEntries.clear();
            callLogAdapter.updateData(callLogEntries);
            updateHistoryVisibility();
            return;
        }

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

        String selection = CallLog.Calls.NUMBER + " = ?";
        String[] selectionArgs = new String[]{phoneNumber};

        try (Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " DESC")) {

            if (cursor != null) {
                int idIdx = cursor.getColumnIndex(CallLog.Calls._ID);
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);

                int count = 0;
                int maxEntries = 50;

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
            showPermissionState();
            return;
        }

        callLogAdapter.updateData(callLogEntries);
        updateHistoryVisibility();

        if (TextUtils.isEmpty(contactName)) {
            for (CallLogEntry entry : callLogEntries) {
                if (!TextUtils.isEmpty(entry.getName())) {
                    contactName = entry.getName();
                    updateHeader();
                    break;
                }
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

    private void showPermissionState() {
        rvCallHistory.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        permissionState.setVisibility(View.VISIBLE);
    }

    private void requestCallLogPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.READ_CALL_LOG},
                REQUEST_READ_CALL_LOG);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_READ_CALL_LOG) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadCallHistoryForNumber();
            } else {
                Toast.makeText(this, R.string.permission_call_log_denied, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CALL_PHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!TextUtils.isEmpty(pendingCallNumber)) {
                    startCall(pendingCallNumber);
                    pendingCallNumber = null;
                }
            } else {
                pendingCallNumber = null;
                Toast.makeText(this, R.string.call_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
