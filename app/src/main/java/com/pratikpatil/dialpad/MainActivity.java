package com.pratikpatil.dialpad;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    EditText phoneNumber;
    Button btnCall, btnDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phoneNumber = findViewById(R.id.phoneNumber);
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

        btnDelete.setOnClickListener(v -> {
            String text = phoneNumber.getText().toString();

            if (!text.isEmpty()) {
                phoneNumber.setText(text.substring(0, text.length() - 1));
            }
        });

        btnCall.setOnClickListener(v -> {
            String number = phoneNumber.getText().toString();

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

    public void numberClick(View view) {
        Button button = (Button) view;
        String current = phoneNumber.getText().toString();
        phoneNumber.setText(current + button.getText().toString());
    }
}