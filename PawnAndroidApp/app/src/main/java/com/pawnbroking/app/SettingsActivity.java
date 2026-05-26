package com.pawnbroking.app;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {

    private Button btnDark, btnLight;
    private TextView tvThemeDesc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Settings");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        btnDark      = findViewById(R.id.btnDark);
        btnLight     = findViewById(R.id.btnLight);
        tvThemeDesc  = findViewById(R.id.tvThemeDesc);

        refreshButtons();

        btnDark.setOnClickListener(v  -> applyAndSave(PawnApp.THEME_DARK));
        btnLight.setOnClickListener(v -> applyAndSave(PawnApp.THEME_LIGHT));
    }

    private void applyAndSave(String mode) {
        PawnApp.saveTheme(this, mode);
        PawnApp.applyTheme(this);
        refreshButtons();
        // Recreate so the settings screen itself reflects the new theme instantly
        recreate();
    }

    private void refreshButtons() {
        boolean dark = PawnApp.isDark(this);

        int activeColor   = getResources().getColor(R.color.gold, getTheme());
        int inactiveColor = getResources().getColor(R.color.white54, getTheme());

        btnDark.setTextColor( dark ? activeColor : inactiveColor);
        btnLight.setTextColor(!dark ? activeColor : inactiveColor);

        tvThemeDesc.setText(dark ? "Dark mode is active" : "Light mode is active");
    }
}
