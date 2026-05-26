package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.pawnbroking.app.models.User;
import com.pawnbroking.app.services.ApiService;

/**
 * Two-tap email-OTP login over Magizhchi Share.
 *
 * The layout (single email field, single OTP field, single button) is
 * intentionally unchanged — the same widgets drive both steps:
 *   • Step 1: OTP field empty → "Send OTP" sends a code to the email
 *   • Step 2: OTP field filled → "Verify & Sign In" exchanges the code
 *
 * The password-toggle eye icon stays useful for the OTP step (lets the
 * user reveal what they typed). A long-press on the button resends a
 * fresh OTP if the user wants one.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button   btnLogin;
    private ProgressBar progressBar;
    private TextView tvError;
    private ImageButton ibTogglePass;
    private boolean passVisible = false;
    private boolean otpSent     = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername   = findViewById(R.id.etUsername);
        etPassword   = findViewById(R.id.etPassword);
        btnLogin     = findViewById(R.id.btnLogin);
        progressBar  = findViewById(R.id.progressBar);
        tvError      = findViewById(R.id.tvError);
        ibTogglePass = findViewById(R.id.ibTogglePass);

        etUsername.setHint("Email");
        etPassword.setHint("Tap \"Send OTP\" first");
        btnLogin.setText("Send OTP");

        ibTogglePass.setOnClickListener(v -> {
            passVisible = !passVisible;
            int type = passVisible
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            etPassword.setInputType(type);
            etPassword.setSelection(etPassword.getText().length());
            ibTogglePass.setImageResource(passVisible ? android.R.drawable.ic_menu_view : android.R.drawable.ic_secure);
        });

        btnLogin.setOnClickListener(v -> onLoginTap());
        // Long-press resends a fresh OTP without leaving the screen.
        btnLogin.setOnLongClickListener(v -> {
            String email = etUsername.getText().toString().trim();
            if (!email.isEmpty()) sendOtp(email);
            return true;
        });
    }

    private void onLoginTap() {
        String email = etUsername.getText().toString().trim();
        String code  = etPassword.getText().toString().trim();
        if (email.isEmpty()) { etUsername.setError("Enter email"); return; }

        if (!otpSent || code.isEmpty()) {
            sendOtp(email);
        } else {
            verifyOtp(email, code);
        }
    }

    private void sendOtp(String email) {
        showError(null);
        setBusy(true);
        ApiService.requestOtp(email, new ApiService.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                runOnUiThread(() -> {
                    setBusy(false);
                    otpSent = true;
                    etPassword.setHint("Enter 6-digit OTP");
                    etPassword.requestFocus();
                    btnLogin.setText("Verify & Sign In");
                    showInfo("OTP sent to " + email);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> { setBusy(false); showError(message); });
            }
        });
    }

    private void verifyOtp(String email, String code) {
        showError(null);
        setBusy(true);
        ApiService.verifyOtpAndLogin(this, email, code, new ApiService.Callback<User>() {
            @Override public void onSuccess(User result) {
                runOnUiThread(() -> {
                    setBusy(false);
                    startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                    finish();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> { setBusy(false); showError(message); });
            }
        });
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!busy);
    }

    private void showError(String msg) {
        if (msg == null || msg.isEmpty()) { tvError.setVisibility(View.GONE); return; }
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    /** Reuses the error TextView as a neutral status line. */
    private void showInfo(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
