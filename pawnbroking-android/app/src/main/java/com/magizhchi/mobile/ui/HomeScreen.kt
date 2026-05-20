package com.magizhchi.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.Dashboard
import com.magizhchi.mobile.data.NotificationItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeVM @Inject constructor(private val api: Api) : ViewModel() {
    var dash by mutableStateOf<Dashboard?>(null); private set
    var notifs by mutableStateOf<List<NotificationItem>>(emptyList()); private set
    var err by mutableStateOf<String?>(null); private set

    init { refresh() }
    fun refresh() {
        viewModelScope.launch {
            runCatching { dash = api.dashboard(); notifs = api.notifications(20) }
                .onFailure { err = it.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenList: (String) -> Unit, onLogout: () -> Unit, vm: HomeVM = hiltViewModel()) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Pawnbroking — ${vm.dash?.shop_id ?: ""}") },
            actions = {
                IconButton(onClick = onLogout) {
                    Icon(Icons.Default.Logout, contentDescription = "Logout")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            vm.dash?.let { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Today's bills", d.todays_bills.toString(), Modifier.weight(1f))
                    Stat("Customers",   d.total_customers.toString(), Modifier.weight(1f))
                    Stat("Advance",     "₹${d.advance_total.toInt()}", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Open", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenList("bill_opening") }, modifier = Modifier.weight(1f)) { Text("Bills") }
                OutlinedButton(onClick = { onOpenList("customer_master") }, modifier = Modifier.weight(1f)) { Text("Customers") }
            }
            Spacer(Modifier.height(16.dp))
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(vm.notifs) { n ->
                    ListItem(
                        headlineContent = { Text(n.title) },
                        supportingContent = { Text(n.body) },
                        overlineContent = { Text(n.created_at) }
                    )
                    HorizontalDivider()
                }
            }
            vm.err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
