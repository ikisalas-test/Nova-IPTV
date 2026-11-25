package com.novatv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.novatv.iptv.data.Channel
import com.novatv.iptv.data.M3uParser
import com.novatv.iptv.data.SecureProfileStore
import com.novatv.iptv.data.XtreamClient
import com.novatv.iptv.data.XtreamProfile
import com.novatv.iptv.data.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val Ink = Color(0xFF090B12)
private val Panel = Color(0xFF151925)
private val Violet = Color(0xFF9B8CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { NovaApp() } }
}

class NovaViewModel(private val context: Context) : ViewModel() {
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var selected by mutableStateOf<Channel?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var category by mutableStateOf("Todos")
    var favoritesOnly by mutableStateOf(false)
    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    var history by mutableStateOf<List<String>>(emptyList()); private set
    var epg by mutableStateOf<List<EpgProgram>>(emptyList()); private set
    var catalog by mutableStateOf("live")
    private var allItems = emptyList<Channel>()
    private val profileStore = SecureProfileStore(context)
    private val prefs = context.getSharedPreferences("nova_library", Context.MODE_PRIVATE)
    init {
        favorites = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        history = prefs.getString("history", "")!!.split('\n').filter(String::isNotBlank).take(30)
    }
    fun load(text: String) {
        val parsed = M3uParser.parse(text)
        if (parsed.isEmpty()) error = "No encontramos canales. Comprueba que el archivo sea una lista M3U válida."
        else { channels = parsed; allItems = parsed; catalog = "live"; selected = null; category = "Todos"; error = null }
    }
    fun connectXtream(server: String, username: String, password: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val profile = XtreamProfile("Mi perfil", server.trim().trimEnd('/'), username.trim(), password)
                val lists = withContext(Dispatchers.IO) {
                    val client = XtreamClient(profile.server, profile.username, profile.password)
                    client.list("live") + client.list("movies") + client.list("series")
                }
                if (lists.isEmpty()) error = "El proveedor no devolvió canales ni contenidos. Revisa servidor y acceso."
                else {
                    profileStore.save(profile)
                    allItems = lists; channels = lists; catalog = "live"; category = "Todos"
                }
            } catch (e: Exception) { error = "No se pudo conectar con el proveedor: ${e.message ?: "error de conexión"}" }
            finally { loading = false }
        }
    }
    fun loadEpisodes(series: Channel) {
        val profile = profileStore.load() ?: run { error = "Vuelve a conectar tu perfil Xtream para ver episodios."; return }
        viewModelScope.launch {
            loading = true
            try {
                val episodes = withContext(Dispatchers.IO) { XtreamClient(profile.server, profile.username, profile.password).episodes(series.sourceId ?: return@withContext emptyList()) }
                if (episodes.isEmpty()) error = "No hay episodios disponibles para esta serie."
                else { allItems = episodes; channels = episodes; catalog = "episode"; category = "Todos" }
            } catch (e: Exception) { error = "No se pudieron cargar los episodios: ${e.message ?: "error"}" }
            finally { loading = false }
        }
    }
    fun selectCatalog(kind: String) { catalog = kind; channels = if (kind == "live") allItems.filter { it.kind == "live" } else allItems.filter { it.kind == kind }; category = "Todos" }
    fun loadUrl(url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) { error = "Introduce una URL HTTP o HTTPS válida."; return }
        viewModelScope.launch {
            loading = true; error = null
            try {
                val body = withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 20000
                    try { if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}"); connection.inputStream.bufferedReader().use { it.readText() } }
                    finally { connection.disconnect() }
                }
                load(body)
            } catch (e: Exception) { error = "No se pudo cargar la lista: ${e.message ?: "error de conexión"}" }
            finally { loading = false }
        }
    }
    fun play(channel: Channel) {
        selected = channel
        epg = emptyList()
        if (channel.kind == "live" && channel.sourceId != null) {
            profileStore.load()?.let { profile -> viewModelScope.launch {
                epg = withContext(Dispatchers.IO) { XtreamClient(profile.server, profile.username, profile.password).epg(channel.sourceId) }
            } }
        }
        history = (listOf(channel.name) + history.filterNot { it == channel.name }).take(30)
        prefs.edit().putString("history", history.joinToString("\n")).apply()
    }
    fun toggleFavorite(channel: Channel) {
        val key = favoriteKey(channel)
        favorites = if (key in favorites) favorites - key else favorites + key
        prefs.edit().putStringSet("favorites", favorites).apply()
    }
    fun stop() { selected = null }
    fun favoriteKey(channel: Channel): String = MessageDigest.getInstance("SHA-256").digest(channel.url.toByteArray()).joinToString("") { "%02x".format(it) }
    fun visibleChannels(): List<Channel> = channels.filter { (!favoritesOnly || favoriteKey(it) in favorites) && (category == "Todos" || it.group == category) }
}

@Composable private fun NovaApp() {
    val context = LocalContext.current
    val vm: NovaViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = NovaViewModel(context.applicationContext) as T
    })
    var playlistUrl by remember { mutableStateOf("") }
    var showXtream by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> vm.load(r.readText()) } ?: run { } } catch (_: Exception) { } }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Violet, onBackground = Color.White, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Violet, RoundedCornerShape(14.dp)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Ink) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("NOVA", fontWeight = FontWeight.Black, color = Color.White); Text("TU TELEVISIÓN, A TU MANERA", style = MaterialTheme.typography.labelSmall, color = Color.LightGray) }
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Archivo") }
                    Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { showXtream = true }) { Text("Xtream") }
                }
                if (vm.selected != null) {
                    TextButton(onClick = vm::stop) { Icon(Icons.Default.ArrowBack, null); Text(" Volver a canales") }
                    Text(vm.selected!!.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Player(vm.selected!!)
                    if (vm.epg.isNotEmpty()) {
                        Text("Guía de programación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(vm.epg) { item ->
                            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.start.takeLast(5), color = Violet, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
                                Column { Text(item.title, fontWeight = FontWeight.SemiBold); if (item.description.isNotBlank()) Text(item.description, color = Color(0xFF9AA3B7), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            }
                        } }
                    }
                } else {
                    Text(if (vm.channels.isEmpty()) "Empieza con tu lista" else when (vm.catalog) { "movies" -> "Películas"; "series" -> "Series"; "episode" -> "Episodios"; else -> "TV en directo" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (vm.channels.isEmpty()) "Nova no incluye canales. Importa tu propio archivo M3U o M3U8." else "${vm.channels.size} canales · ${vm.channels.map { it.group }.distinct().size} categorías", color = Color(0xFF9AA3B7), modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
                    vm.error?.let { Text(it, color = Color(0xFFFF9B9B), modifier = Modifier.padding(bottom = 10.dp)) }
                    if (vm.channels.isEmpty()) {
                        EmptyState { picker.launch(arrayOf("*/*")) }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            listOf("live" to "En directo", "movies" to "Películas", "series" to "Series").forEach { (key, label) ->
                                FilterChip(selected = vm.catalog == key, onClick = { vm.selectCatalog(key) }, label = { Text(label) })
                            }
                        }
                        var query by remember { mutableStateOf("") }
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), placeholder = { Text("Buscar canal") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !vm.favoritesOnly, onClick = { vm.favoritesOnly = false }, label = { Text("Todos") })
                            FilterChip(selected = vm.favoritesOnly, onClick = { vm.favoritesOnly = !vm.favoritesOnly }, label = { Text("♡ Favoritos") })
                            vm.channels.map { it.group }.distinct().forEach { group -> FilterChip(selected = vm.category == group, onClick = { vm.category = if (vm.category == group) "Todos" else group }, label = { Text(group) }) }
                        }
                        if (vm.history.isNotEmpty()) Text("Recientes: ${vm.history.take(5).joinToString(" · ")}", color = Color(0xFF9AA3B7), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        val filtered = vm.visibleChannels().filter { it.name.contains(query, true) || it.group.contains(query, true) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(filtered, key = { it.sourceId ?: it.url }) { channel -> ChannelRow(channel, vm.favoriteKey(channel) in vm.favorites, { if (channel.kind == "series") vm.loadEpisodes(channel) else vm.play(channel) }, { if (channel.url.isNotBlank()) vm.toggleFavorite(channel) }) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(playlistUrl, { playlistUrl = it }, Modifier.fillMaxWidth(), label = { Text("URL de lista M3U / M3U8") }, singleLine = true, trailingIcon = { TextButton(enabled = !vm.loading, onClick = { vm.loadUrl(playlistUrl.trim()) }) { Text(if (vm.loading) "Cargando…" else "Cargar") } })
                }
            }
        }
    }
    if (showXtream) XtreamDialog(loading = vm.loading, onDismiss = { showXtream = false }, onConnect = { server, user, pass -> showXtream = false; vm.connectXtream(server, user, pass) })
}

@Composable private fun XtreamDialog(loading: Boolean, onDismiss: () -> Unit, onConnect: (String, String, String) -> Unit) {
    var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Conectar proveedor Xtream") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Los datos de acceso se cifran en este dispositivo. Nova no incluye proveedores ni contenido.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(server, { server = it }, label = { Text("URL del servidor") }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Usuario") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        } }, confirmButton = { TextButton(enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(), onClick = { onConnect(server, username, password) }) { Text(if (loading) "Conectando…" else "Conectar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun EmptyState(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).background(Panel, RoundedCornerShape(28.dp)), Alignment.Center) { Icon(Icons.Default.LiveTv, null, tint = Violet, modifier = Modifier.size(42.dp)) }
        Spacer(Modifier.height(18.dp)); Text("Tu pantalla. Tus canales.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Carga una lista M3U para comenzar a ver.", color = Color(0xFF9AA3B7), modifier = Modifier.padding(8.dp))
        Spacer(Modifier.height(14.dp)); Button(onClick = onImport) { Icon(Icons.Default.FolderOpen, null); Text("  Elegir archivo") }
    }
}

@Composable private fun ChannelRow(channel: Channel, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(Color(0xFF22283A), RoundedCornerShape(13.dp)), Alignment.Center) { Icon(Icons.Default.Tv, null, tint = Violet) }
        Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(channel.name, fontWeight = FontWeight.SemiBold); Text(channel.group, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA3B7)) }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = if (favorite) Violet else Color.Gray) }
        Icon(Icons.Default.PlayCircle, null, tint = Violet, modifier = Modifier.size(28.dp))
    }
}

@Composable private fun Player(channel: Channel) {
    val context = LocalContext.current
    val player = remember(channel.url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(channel.url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(18.dp)))
}
