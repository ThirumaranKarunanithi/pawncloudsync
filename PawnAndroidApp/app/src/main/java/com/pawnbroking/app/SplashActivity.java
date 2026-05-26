package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.pawnbroking.app.services.ApiService;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = ApiService.isLoggedIn(this)
            ? new Intent(this, HomeActivity.class)
            : new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}
