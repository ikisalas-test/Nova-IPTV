package com.novatv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novatv.iptv.data.Channel
import com.novatv.iptv.data.EpgProgram
import com.novatv.iptv.data.M3uParser
import com.novatv.iptv.data.SecureProfileStore
import com.novatv.iptv.data.XtreamClient
import com.novatv.iptv.data.XtreamProfile
import com.novatv.iptv.data.XmlTvParser
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { NovaApp() }
    }
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
    var xmltv by mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap()); private set
    var catalog by mutableStateOf("live")
    private var catalogItems = emptyList<Channel>()
    private val profileStore = SecureProfileStore(context)
    var savedPlaylistUrl by mutableStateOf(profileStore.playlistUrl().orEmpty()); private set
    var savedProfiles by mutableStateOf(profileStore.profiles()); private set
    private var activeProfileName: String? = null
    private val prefs = context.getSharedPreferences("nova_library", Context.MODE_PRIVATE)
    init {
        favorites = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        history = prefs.getString("history", "")!!.split('\n').filter(String::isNotBlank).take(30)
        if (savedPlaylistUrl.isNotBlank()) loadUrl(savedPlaylistUrl)
    }
    fun load(text: String): Boolean {
        val parsed = M3uParser.parse(text)
        if (parsed.isEmpty()) { error = "No encontramos canales. Comprueba que el archivo sea una lista M3U válida."; return false }
        channels = parsed; catalogItems = parsed; catalog = "live"; selected = null; category = "Todos"; error = null
        return true
    }
    fun loadXmlTv(text: String) {
        try {
            val parsed = XmlTvParser.parse(text)
            if (parsed.isEmpty()) error = "El archivo XMLTV no contiene emisiones reconocibles."
            else { xmltv = parsed; error = null }
        } catch (e: Exception) { error = "No se pudo analizar el EPG: ${e.message ?: "XML inválido"}" }
    }
    fun reportError(message: String) { error = message }
    fun deleteProfile(name: String) {
        profileStore.delete(name); savedProfiles = profileStore.profiles()
        if (activeProfileName == name) activeProfileName = null
    }
    fun connectXtream(name: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val profile = XtreamProfile(name.trim().ifBlank { "Mi perfil" }, server.trim().trimEnd('/'), username.trim(), password)
                val lists = withContext(Dispatchers.IO) {
                    val client = XtreamClient(profile.server, profile.username, profile.password)
                    client.list("live") + client.list("movies") + client.list("series")
                }
                if (lists.isEmpty()) error = "El proveedor no devolvió canales ni contenidos. Revisa servidor y acceso."
                else {
                    profileStore.save(profile)
                    savedProfiles = profileStore.profiles()
                    activeProfileName = profile.name
                    catalogItems = lists; channels = lists; catalog = "live"; category = "Todos"
                }
            } catch (e: Exception) { error = "No se pudo conectar con el proveedor: ${e.message ?: "error de conexión"}" }
            finally { loading = false }
        }
    }
    fun loadEpisodes(series: Channel) {
        val profile = activeProfileName?.let(profileStore::load) ?: run { error = "Selecciona un perfil Xtream para ver episodios."; return }
        viewModelScope.launch {
            loading = true
            try {
                val episodes = withContext(Dispatchers.IO) { XtreamClient(profile.server, profile.username, profile.password).episodes(series.sourceId ?: return@withContext emptyList()) }
                if (episodes.isEmpty()) error = "No hay episodios disponibles para esta serie."
                else { channels = episodes; catalog = "episode"; category = "Todos" }
            } catch (e: Exception) { error = "No se pudieron cargar los episodios: ${e.message ?: "error"}" }
            finally { loading = false }
        }
    }
    fun selectCatalog(kind: String) { catalog = kind; channels = catalogItems.filter { it.kind == kind }; category = "Todos" }
    fun savePlaylistUrl(url: String) {
        val value = url.trim()
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) {
            error = "Introduce una dirección HTTP o HTTPS antes de guardar."; return
        }
        if (profileStore.savePlaylistUrl(value)) {
            savedPlaylistUrl = value
            error = null
        } else error = "No se pudo guardar la dirección en este dispositivo."
    }
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
                if (load(body)) {
                    savedPlaylistUrl = url
                    if (!profileStore.savePlaylistUrl(url)) error = "Lista cargada, pero no se pudo guardar la dirección."
                }
            } catch (e: Exception) { error = "No se pudo cargar la lista: ${e.message ?: "error de conexión"}" }
            finally { loading = false }
        }
    }
    fun play(channel: Channel) {
        selected = channel
        epg = emptyList()
        if (channel.kind == "live" && channel.sourceId != null) {
            activeProfileName?.let(profileStore::load)?.let { profile -> viewModelScope.launch {
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
    var playlistUrl by remember(vm.savedPlaylistUrl) { mutableStateOf(vm.savedPlaylistUrl) }
    var showXtream by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> vm.load(r.readText()) } ?: vm.reportError("No se pudo abrir el archivo seleccionado.") } catch (e: Exception) { vm.reportError("No se pudo leer la lista: ${e.message ?: "archivo no válido"}") } }
    }
    val epgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> vm.loadXmlTv(reader.readText()) } } catch (e: Exception) { vm.reportError("No se pudo leer el EPG: ${e.message ?: "archivo no válido"}") } }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Violet, onBackground = Color.White, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Violet, RoundedCornerShape(14.dp)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Ink) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("NOVA", fontWeight = FontWeight.Black, color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text("TU TELEVISIÓN, A TU MANERA", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Archivo") }
                        OutlinedButton(onClick = { showXtream = true }) { Text("Xtream") }
                        OutlinedButton(onClick = { epgPicker.launch(arrayOf("application/xml", "text/xml", "text/*", "application/octet-stream")) }) { Text("EPG") }
                    }
                }
                if (vm.selected != null) {
                    TextButton(onClick = vm::stop) { Icon(Icons.Default.ArrowBack, null); Text(" Volver a canales") }
                    Text(vm.selected!!.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Player(vm.selected!!)
                    val xmltvGuide = vm.selected!!.epgId?.let { vm.xmltv[it] }.orEmpty()
                    val guide = vm.epg.ifEmpty { xmltvGuide }
                    if (guide.isNotEmpty()) {
                        Text("Guía de programación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(guide) { item ->
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
                    OutlinedTextField(
                        value = playlistUrl,
                        onValueChange = { playlistUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text("Dirección de lista M3U / M3U8") },
                        placeholder = { Text("https://ejemplo.com/lista.m3u") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { vm.loadUrl(playlistUrl.trim()) }),
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = playlistUrl.isNotBlank(), onClick = { vm.savePlaylistUrl(playlistUrl) }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.savedPlaylistUrl == playlistUrl.trim() && playlistUrl.isNotBlank()) "Lista guardada ✓" else "Guardar lista")
                        }
                        Button(enabled = !vm.loading && playlistUrl.isNotBlank(), onClick = { vm.loadUrl(playlistUrl.trim()) }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.loading) "Cargando…" else "Cargar lista")
                        }
                    }
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
                }
            }
        }
    }
    if (showXtream) XtreamDialog(profiles = vm.savedProfiles, loading = vm.loading, onDismiss = { showXtream = false }, onDelete = vm::deleteProfile, onConnect = { profile -> showXtream = false; vm.connectXtream(profile.name, profile.server, profile.username, profile.password) })
}

@Composable private fun XtreamDialog(profiles: List<XtreamProfile>, loading: Boolean, onDismiss: () -> Unit, onDelete: (String) -> Unit, onConnect: (XtreamProfile) -> Unit) {
    var name by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Conectar proveedor Xtream") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Los datos de acceso se cifran en este dispositivo. Nova no incluye proveedores ni contenido.", style = MaterialTheme.typography.bodySmall)
            if (profiles.isNotEmpty()) {
                Text("Perfiles guardados", fontWeight = FontWeight.SemiBold)
                profiles.forEach { profile -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { name = profile.name; server = profile.server; username = profile.username; password = profile.password }) { Text(profile.name, maxLines = 1) }
                    TextButton(onClick = { onConnect(profile) }) { Text("Conectar") }
                    TextButton(onClick = { onDelete(profile.name) }) { Text("Borrar") }
                } }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del perfil") }, singleLine = true)
            OutlinedTextField(server, { server = it }, label = { Text("URL del servidor") }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Usuario") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        } }, confirmButton = { TextButton(enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(), onClick = { onConnect(XtreamProfile(name.ifBlank { "Mi perfil" }, server, username, password)) }) { Text(if (loading) "Conectando…" else "Guardar y conectar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun EmptyState(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(62.dp).background(Panel, RoundedCornerShape(20.dp)), Alignment.Center) { Icon(Icons.Default.LiveTv, null, tint = Violet, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(12.dp)); Text("Tu pantalla. Tus canales.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Añade una lista M3U o un proveedor Xtream.", color = Color(0xFF9AA3B7), modifier = Modifier.padding(8.dp))
        Button(onClick = onImport) { Icon(Icons.Default.FolderOpen, null); Text("  Elegir archivo") }
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
    var playbackError by remember(channel.url) { mutableStateOf<String?>(null) }
    var fullScreen by remember(channel.url) { mutableStateOf(false) }
    val initResult = remember(channel.url) {
        try {
            val instance = ExoPlayer.Builder(context.applicationContext).build()
            instance.setMediaItem(MediaItem.fromUri(channel.url))
            instance.prepare()
            instance.playWhenReady = true
            instance to null
        } catch (exception: Exception) {
            Log.e("NovaIPTV", "ExoPlayer initialization failed", exception)
            null to "${exception.javaClass.simpleName}: ${exception.localizedMessage ?: "sin detalle"}"
        }
    }
    val player = initResult.first
    if (player == null) {
        Text("No se pudo iniciar el reproductor. ${initResult.second.orEmpty()}", color = Color(0xFFFF9B9B), modifier = Modifier.padding(16.dp))
    } else {
        DisposableEffect(player) {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = "No se pudo reproducir este canal (${error.errorCodeName}). ${error.message.orEmpty()}"
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener); player.release() }
        }
        if (fullScreen) {
            Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val dialogView = LocalView.current
                val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                DisposableEffect(dialogWindow) {
                    val bars = dialogWindow?.let { WindowInsetsControllerCompat(it, it.decorView) }
                    bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars?.hide(WindowInsetsCompat.Type.systemBars())
                    onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                    IconButton(onClick = { fullScreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = Color.White)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(18.dp))) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { fullScreen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Fullscreen, "Pantalla completa", tint = Color.White)
                }
            }
        }
        playbackError?.let { Text(it, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
}
