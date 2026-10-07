package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
    private TextView tvSwitchMode, tvSetPassword;
    private boolean passVisible = false;
    private boolean otpSent     = false;
    /** true = password login, false = OTP login (default). */
    private boolean passwordMode = false;

    /** Active cool-down countdown — null when Send OTP is free to tap. */
    private CountDownTimer otpCooldown;
    /** Seconds remaining in the current cool-down, 0 if none. */
    private int otpCooldownRemaining = 0;
    /** Minimum gap (sec) between two Send OTP taps from this client. */
    private static final int CLIENT_COOLDOWN_SEC = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername    = findViewById(R.id.etUsername);
        etPassword    = findViewById(R.id.etPassword);
        btnLogin      = findViewById(R.id.btnLogin);
        progressBar   = findViewById(R.id.progressBar);
        tvError       = findViewById(R.id.tvError);
        ibTogglePass  = findViewById(R.id.ibTogglePass);
        tvSwitchMode  = findViewById(R.id.tvSwitchMode);
        tvSetPassword = findViewById(R.id.tvSetPassword);

        etUsername.setHint("Email");
        applyMode();   // sets hints + button text for the current mode

        tvSwitchMode.setOnClickListener(v -> {
            passwordMode = !passwordMode;
            otpSent = false;
            applyMode();
            showError(null);
        });
        // Set/change password: needs a fresh OTP + a new password.
        tvSetPassword.setOnClickListener(v -> promptSetPassword());

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
        // Long-press resends a fresh OTP without leaving the screen — but
        // respects the same cool-down so it can't be used to spam the box.
        btnLogin.setOnLongClickListener(v -> {
            String email = etUsername.getText().toString().trim();
            if (email.isEmpty()) return true;
            if (otpCooldownRemaining > 0) {
                showError("Please wait " + otpCooldownRemaining + "s before resending.");
                return true;
            }
            sendOtp(email);
            return true;
        });
    }

    /** Applies hints + button label for the current login mode. */
    private void applyMode() {
        if (passwordMode) {
            etPassword.setHint("Password");
            etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            btnLogin.setText("Login");
            tvSwitchMode.setText("Use OTP instead");
            tvSetPassword.setVisibility(View.VISIBLE);
        } else {
            etPassword.setHint(otpSent ? "Enter 6-digit OTP" : "Tap \"Send OTP\" first");
            btnLogin.setText(otpSent ? "Verify & Sign In" : "Send OTP");
            tvSwitchMode.setText("Use password instead");
            tvSetPassword.setVisibility(View.GONE);
        }
    }

    private void onLoginTap() {
        String email = etUsername.getText().toString().trim();
        String secret = etPassword.getText().toString().trim();
        if (email.isEmpty()) { etUsername.setError("Enter email"); return; }

        if (passwordMode) {
            if (secret.isEmpty()) { etPassword.setError("Enter password"); return; }
            passwordLoginTap(email, secret);
            return;
        }

        // OTP mode
        if (!otpSent || secret.isEmpty()) {
            if (otpCooldownRemaining > 0) {
                showError("Please wait " + otpCooldownRemaining + "s before requesting another OTP.");
                return;
            }
            sendOtp(email);
        } else {
            verifyOtp(email, secret);
        }
    }

    private void passwordLoginTap(String email, String password) {
        showError(null);
        setBusy(true);
        ApiService.passwordLogin(this, email, password,
            new ApiService.Callback<ApiService.VerifyResult>() {
                @Override public void onSuccess(ApiService.VerifyResult r) {
                    runOnUiThread(() -> { setBusy(false); routeAfterLogin(r); });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> { setBusy(false); showError(message); });
                }
            });
    }

    /** Set/change password flow: email + fresh OTP + new password. */
    private void promptSetPassword() {
        String email = etUsername.getText().toString().trim();
        if (email.isEmpty()) { etUsername.setError("Enter email first"); return; }

        // Email an OTP right away so the code is on its way while the user reads.
        ApiService.requestOtp(email, new ApiService.Callback<Void>() {
            @Override public void onSuccess(Void v) {
                runOnUiThread(() -> Toast.makeText(LoginActivity.this,
                    "OTP sent to " + email, Toast.LENGTH_SHORT).show());
            }
            @Override public void onError(String m) {
                runOnUiThread(() -> Toast.makeText(LoginActivity.this,
                    "OTP: " + m, Toast.LENGTH_LONG).show());
            }
        });

        final EditText etOtp = new EditText(this);
        etOtp.setHint("6-digit OTP from email");
        etOtp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final EditText etNew = new EditText(this);
        etNew.setHint("New password (min 4 chars)");
        etNew.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        box.addView(etOtp); box.addView(etNew);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Set / change password")
            .setMessage("Enter the OTP emailed to " + email + " and your new password.")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String otp = etOtp.getText().toString().trim();
                String np  = etNew.getText().toString().trim();
                if (otp.isEmpty() || np.length() < 4) {
                    Toast.makeText(this, "Enter OTP and a 4+ char password", Toast.LENGTH_SHORT).show();
                    return;
                }
                setBusy(true);
                ApiService.setPassword(email, otp, np, new ApiService.Callback<String>() {
                    @Override public void onSuccess(String msg) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                            passwordMode = true; applyMode();
                            etPassword.setText(np);
                        });
                    }
                    @Override public void onError(String m) {
                        runOnUiThread(() -> { setBusy(false);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show(); });
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
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
                    // Block resend-spam so the box never sees a flood from us.
                    startCooldown(CLIENT_COOLDOWN_SEC);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false);
                    // RATE_LIMIT:<seconds> from ApiService → run the server's
                    // cool-down so the next tap actually has a chance to pass.
                    if (message != null && message.startsWith("RATE_LIMIT:")) {
                        int wait = CLIENT_COOLDOWN_SEC;
                        try { wait = Integer.parseInt(message.substring("RATE_LIMIT:".length())); }
                        catch (NumberFormatException ignored) {}
                        showError("Too many OTP requests — please wait " + wait + "s before retrying.");
                        startCooldown(wait);
                    } else {
                        showError(message);
                    }
                });
            }
        });
    }

    /**
     * Starts a visible countdown on the status line. The button itself stays
     * enabled — the Verify step must always be tappable. The gate is enforced
     * in {@link #onLoginTap()} against {@link #otpCooldownRemaining}.
     */
    private void startCooldown(int seconds) {
        if (otpCooldown != null) otpCooldown.cancel();
        otpCooldownRemaining = seconds;
        otpCooldown = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override public void onTick(long msLeft) {
                otpCooldownRemaining = (int) ((msLeft + 500) / 1000);
                showInfo((otpSent ? "OTP sent. Resend available in "
                                  : "Please wait ")
                        + otpCooldownRemaining + "s");
            }
            @Override public void onFinish() {
                otpCooldownRemaining = 0;
                otpCooldown = null;
                showInfo(otpSent ? "You can resend the OTP now." : "");
            }
        }.start();
    }

    @Override protected void onDestroy() {
        if (otpCooldown != null) { otpCooldown.cancel(); otpCooldown = null; }
        super.onDestroy();
    }

    private void verifyOtp(String email, String code) {
        showError(null);
        setBusy(true);
        ApiService.verifyOtpAndLogin(this, email, code,
            new ApiService.Callback<ApiService.VerifyResult>() {
                @Override public void onSuccess(ApiService.VerifyResult r) {
                    runOnUiThread(() -> { setBusy(false); routeAfterLogin(r); });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> { setBusy(false); showError(message); });
                }
            });
    }

    /** Shared post-login navigation for both OTP verify and password login. */
    private void routeAfterLogin(ApiService.VerifyResult r) {
        if (r.kind == ApiService.VerifyResult.Kind.SINGLE_SHOP) {
            startActivity(new Intent(LoginActivity.this, HomeActivity.class));
        } else {
            // 2+ shops → user picks. Pass selector + shops so no re-fetch.
            Intent intent = new Intent(LoginActivity.this, ShopPickerActivity.class);
            intent.putExtra(ShopPickerActivity.EXTRA_SELECTOR_TOKEN, r.selectorToken);
            intent.putExtra(ShopPickerActivity.EXTRA_EMAIL,          r.email);
            intent.putExtra(ShopPickerActivity.EXTRA_SHOPS_JSON,
                            r.shops == null ? "[]" : r.shops.toString());
            startActivity(intent);
        }
        finish();
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
