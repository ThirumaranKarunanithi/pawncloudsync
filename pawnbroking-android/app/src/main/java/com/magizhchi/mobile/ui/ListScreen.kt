package com.magizhchi.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.Row
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListVM @Inject constructor(private val api: Api) : ViewModel() {
    var rows by mutableStateOf<List<Row>>(emptyList()); private set
    var query by mutableStateOf(""); private set

    fun load(table: String) {
        viewModelScope.launch {
            runCatching { rows = api.list(table, query.ifBlank { null }, 100) }
        }
    }

    fun onQuery(q: String, table: String) { query = q; load(table) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(table: String, onRow: (String, String) -> Unit, vm: ListVM = hiltViewModel()) {
    LaunchedEffect(table) { vm.load(table) }
    Scaffold(topBar = { TopAppBar(title = { Text(table) }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.onQuery(it, table) },
                label = { Text("Search") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(vm.rows) { r ->
                    val name = r.payload["name"]?.toString()
                        ?: r.payload["customer_name"]?.toString()
                        ?: r.payload["description"]?.toString() ?: r.row_pk ?: "(no name)"
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("#${r.row_pk ?: "-"}  ·  ${r.last_updated_at ?: ""}") },
                        modifier = Modifier.clickable { onRow(table, r.row_pk ?: return@clickable) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
