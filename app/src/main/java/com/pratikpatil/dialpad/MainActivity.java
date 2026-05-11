package com.pratikpatil.dialpad;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private TextView phoneNumberDisplay;
    private ImageButton btnCall, btnDelete;
    private StringBuilder phoneNumber = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phoneNumberDisplay = findViewById(R.id.phoneNumberDisplay);
        btnCall = findViewById(R.id.btnCall);
        btnDelete = findViewById(R.id.btnDelete);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_call);
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_history) {
                Toast.makeText(this, R.string.toast_history_coming_soon, Toast.LENGTH_SHORT).show();
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
                        1);
                return;
            }

            startActivity(intent);
        });
    }

    /**
     * Called when any dial pad number button is clicked.
     * Uses the tag attribute to determine which digit was pressed.
     */
    public void numberClick(View view) {
        String digit = (String) view.getTag();
        if (digit != null) {
            phoneNumber.append(digit);
            updateDisplay();
        }
    }

    /**
     * Updates the phone number display and toggles delete button visibility.
     */
    private void updateDisplay() {
        String formatted = formatPhoneNumber(phoneNumber.toString());
        phoneNumberDisplay.setText(formatted);

        // Show/hide delete button based on whether there's input
        if (phoneNumber.length() > 0) {
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            btnDelete.setVisibility(View.GONE);
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
}