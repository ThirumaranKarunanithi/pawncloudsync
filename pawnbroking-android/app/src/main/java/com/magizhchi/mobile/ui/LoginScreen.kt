package com.magizhchi.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.DeviceReq
import com.magizhchi.mobile.data.LoginReq
import com.magizhchi.mobile.data.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginVM @Inject constructor(
    private val api: Api,
    private val store: TokenStore
) : ViewModel() {
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    fun login(shopId: String, user: String, pass: String, onDone: () -> Unit) {
        busy = true; error = null
        viewModelScope.launch {
            runCatching {
                val r = api.login(LoginReq(shopId.trim(), user.trim(), pass))
                store.token = r.access_token
                store.shopId = r.shop_id
                store.userId = r.user_id
                FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                    viewModelScope.launch {
                        runCatching { api.registerDevice(DeviceReq(r.user_id, tok, android.os.Build.MODEL ?: "android")) }
                    }
                }
            }.onSuccess { onDone() }
             .onFailure { error = it.message }
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, vm: LoginVM = hiltViewModel()) {
    var shop by remember { mutableStateOf("alwarpuram") }
    var u    by remember { mutableStateOf("admin") }
    var p    by remember { mutableStateOf("admin") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Pawnbroking", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(shop, { shop = it }, label = { Text("Shop ID") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(u, { u = it }, label = { Text("Username") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(p, { p = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.login(shop, u, p, onLoggedIn) }, enabled = !vm.busy) {
            Text(if (vm.busy) "Signing in…" else "Sign in")
        }
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
