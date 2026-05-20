package com.magizhchi.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magizhchi.mobile.data.Api
import com.magizhchi.mobile.data.Row
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailVM @Inject constructor(private val api: Api) : ViewModel() {
    var row by mutableStateOf<Row?>(null); private set
    fun load(table: String, pk: String) {
        viewModelScope.launch { runCatching { row = api.one(table, pk) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(table: String, rowPk: String, vm: DetailVM = hiltViewModel()) {
    LaunchedEffect(table, rowPk) { vm.load(table, rowPk) }
    Scaffold(topBar = { TopAppBar(title = { Text("$table #$rowPk") }) }) { pad ->
        val entries = vm.row?.payload?.entries?.toList().orEmpty()
        LazyColumn(Modifier.padding(pad).padding(12.dp).fillMaxSize()) {
            items(entries) { (k, v) ->
                ListItem(
                    overlineContent = { Text(k) },
                    headlineContent = { Text(v.toString().trim('"')) }
                )
                HorizontalDivider()
            }
        }
    }
}
