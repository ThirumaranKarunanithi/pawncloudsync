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

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button   btnLogin;
    private ProgressBar progressBar;
    private TextView tvError;
    private ImageButton ibTogglePass;
    private boolean passVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername  = findViewById(R.id.etUsername);
        etPassword  = findViewById(R.id.etPassword);
        btnLogin    = findViewById(R.id.btnLogin);
        progressBar = findViewById(R.id.progressBar);
        tvError     = findViewById(R.id.tvError);
        ibTogglePass = findViewById(R.id.ibTogglePass);

        ibTogglePass.setOnClickListener(v -> {
            passVisible = !passVisible;
            int type = passVisible
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            etPassword.setInputType(type);
            etPassword.setSelection(etPassword.getText().length());
            ibTogglePass.setImageResource(passVisible ? android.R.drawable.ic_menu_view : android.R.drawable.ic_secure);
        });

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String user = etUsername.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();
        if (user.isEmpty()) { etUsername.setError("Enter username"); return; }
        if (pass.isEmpty()) { etPassword.setError("Enter password"); return; }

        tvError.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        btnLogin.setEnabled(false);

        ApiService.login(this, user, pass, new ApiService.Callback<User>() {
            @Override public void onSuccess(User result) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                    finish();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnLogin.setEnabled(true);
                    tvError.setText(message);
                    tvError.setVisibility(View.VISIBLE);
                });
            }
        });
    }
}
