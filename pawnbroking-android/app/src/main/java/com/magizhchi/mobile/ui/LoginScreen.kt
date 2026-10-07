package com.magizhchi.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.DeviceReq
import com.magizhchi.mobile.data.SelectShopReq
import com.magizhchi.mobile.data.SendOtpReq
import com.magizhchi.mobile.data.TokenStore
import com.magizhchi.mobile.data.VerifyOtpReq
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Stage { ENTER_EMAIL, ENTER_CODE }

@HiltViewModel
class LoginVM @Inject constructor(
    private val api: Api,
    private val store: TokenStore
) : ViewModel() {
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var stage by mutableStateOf(Stage.ENTER_EMAIL); private set
    var lastEmail by mutableStateOf(""); private set
    var lastShopId by mutableStateOf(""); private set

    fun sendOtp(shopId: String, email: String) {
        busy = true; error = null
        viewModelScope.launch {
            runCatching {
                api.sendOtp(SendOtpReq(
                    email   = email.trim().lowercase(),
                    shop_id = shopId.trim().ifBlank { null }))
            }.onSuccess {
                lastEmail  = email.trim().lowercase()
                lastShopId = shopId.trim()
                stage = Stage.ENTER_CODE
            }.onFailure { error = humanize(it) }
            busy = false
        }
    }

    fun verifyOtp(code: String, onDone: () -> Unit) {
        busy = true; error = null
        viewModelScope.launch {
            runCatching {
                var r = api.verifyOtp(VerifyOtpReq(
                    email   = lastEmail,
                    code    = code.trim(),
                    shop_id = lastShopId.ifBlank { null }))

                // Multi-shop user: backend returned a selector token + list of shops.
                // If the user already typed a Shop ID, automatically exchange the
                // selector for an access token in that shop. Otherwise complain.
                if (r.access_token == null && r.selector_token != null) {
                    val available = r.shops?.map { it.shop_id } ?: emptyList()
                    val pick = lastShopId.ifBlank {
                        throw RuntimeException(
                            "Your email is registered for multiple shops (${available.joinToString(", ")}). " +
                            "Tap Back and type one Shop ID to pick.")
                    }
                    if (pick !in available) {
                        throw RuntimeException(
                            "Shop '$pick' is not in your authorised list (${available.joinToString(", ")}).")
                    }
                    r = api.selectShop("Bearer ${r.selector_token}", SelectShopReq(pick))
                }

                val accessToken = r.access_token
                val resolvedShop = r.shop_id
                val userId = r.user_id
                if (accessToken != null && resolvedShop != null && userId != null) {
                    store.token  = accessToken
                    store.shopId = resolvedShop
                    store.userId = userId
                    runCatching {
                        FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                            viewModelScope.launch {
                                runCatching {
                                    api.registerDevice(DeviceReq(userId, tok, android.os.Build.MODEL ?: "android"))
                                }
                            }
                        }
                    }
                } else {
                    throw RuntimeException("Server returned an unexpected response.")
                }
            }.onSuccess { onDone() }
             .onFailure { error = humanize(it) }
            busy = false
        }
    }

    fun back() { stage = Stage.ENTER_EMAIL; error = null }

    private fun humanize(t: Throwable): String = when {
        t.message?.contains("403", true) == true ->
            "This email is not authorised for that shop. Contact your admin."
        t.message?.contains("400", true) == true ->
            "Invalid code. Please re-enter."
        t.message?.contains("500", true) == true ->
            "Server error. Please try again."
        else -> t.message ?: "Login failed."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, vm: LoginVM = hiltViewModel()) {
    var shop  by remember { mutableStateOf("annanagar") }
    var email by remember { mutableStateOf("") }
    var code  by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Pawnbroking", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))

        when (vm.stage) {
            Stage.ENTER_EMAIL -> {
                OutlinedTextField(
                    value = shop, onValueChange = { shop = it },
                    label = { Text("Shop ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.sendOtp(shop, email) },
                    enabled = !vm.busy && email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (vm.busy) "Sending…" else "Send OTP")
                }
            }

            Stage.ENTER_CODE -> {
                Text("Enter the 6-digit code we sent to",
                     style = MaterialTheme.typography.bodyMedium)
                Text(vm.lastEmail, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.take(8) },
                    label = { Text("Verification code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.verifyOtp(code, onLoggedIn) },
                    enabled = !vm.busy && code.length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (vm.busy) "Verifying…" else "Verify")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.back() }, enabled = !vm.busy) {
                    Text("Back / use different email")
                }
            }
        }

        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
