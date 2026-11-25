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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.novatv.iptv.data.XtreamLabels
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
private const val ALL_CATEGORIES = "__nova_all_categories__"

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
    var showSources by mutableStateOf(true); private set
    var category by mutableStateOf(ALL_CATEGORIES)
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
        val parsed = M3uParser.parse(text, context.getString(R.string.uncategorized), context.getString(R.string.channel_untitled))
        if (parsed.isEmpty()) { error = context.getString(R.string.error_invalid_m3u); return false }
        catalogItems = (catalogItems + parsed).distinctBy { it.url }
        channels = catalogItems.filter { it.kind == "live" }
        catalog = "live"; selected = null; category = ALL_CATEGORIES; error = null; showSources = false
        return true
    }
    fun loadXmlTv(text: String) {
        try {
            val parsed = XmlTvParser.parse(text)
            if (parsed.isEmpty()) error = context.getString(R.string.error_empty_xmltv)
            else { xmltv = parsed; error = null }
        } catch (e: Exception) { error = context.getString(R.string.error_parse_epg, e.message ?: context.getString(R.string.error_invalid_xml)) }
    }
    fun reportError(message: String) { error = message }
    fun addSource() { showSources = true }
    fun deleteProfile(name: String) {
        profileStore.delete(name); savedProfiles = profileStore.profiles()
        if (activeProfileName == name) activeProfileName = null
    }
    fun connectXtream(name: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val profile = XtreamProfile(name.trim().ifBlank { context.getString(R.string.profile_default) }, server.trim().trimEnd('/'), username.trim(), password)
                val lists = withContext(Dispatchers.IO) {
                    val client = xtreamClient(profile)
                    client.list("live") + client.list("movies") + client.list("series")
                }
                if (lists.isEmpty()) error = context.getString(R.string.error_empty_provider)
                else {
                    profileStore.save(profile)
                    savedProfiles = profileStore.profiles()
                    activeProfileName = profile.name
                    catalogItems = (catalogItems + lists).distinctBy { it.url }
                    channels = catalogItems.filter { it.kind == "live" }
                    catalog = "live"; category = ALL_CATEGORIES; selected = null; showSources = false
                }
            } catch (e: Exception) { error = context.getString(R.string.error_connect_provider, e.message ?: context.getString(R.string.error_connection)) }
            finally { loading = false }
        }
    }
    fun loadEpisodes(series: Channel) {
        val profile = activeProfileName?.let(profileStore::load) ?: run { error = context.getString(R.string.error_select_profile); return }
        viewModelScope.launch {
            loading = true
            try {
                val episodes = withContext(Dispatchers.IO) { xtreamClient(profile).episodes(series.sourceId ?: return@withContext emptyList()) }
                if (episodes.isEmpty()) error = context.getString(R.string.error_no_episodes)
                else { channels = episodes; catalog = "episode"; category = ALL_CATEGORIES }
            } catch (e: Exception) { error = context.getString(R.string.error_load_episodes, e.message ?: context.getString(R.string.error_connection)) }
            finally { loading = false }
        }
    }
    fun selectCatalog(kind: String) { catalog = kind; channels = catalogItems.filter { it.kind == kind }; category = ALL_CATEGORIES }
    fun savePlaylistUrl(url: String) {
        val value = url.trim()
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) {
            error = context.getString(R.string.error_invalid_url_save); return
        }
        if (profileStore.savePlaylistUrl(value)) {
            savedPlaylistUrl = value
            error = null
        } else error = context.getString(R.string.error_save_url)
    }
    fun loadUrl(url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) { error = context.getString(R.string.error_invalid_url); return }
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
                    if (!profileStore.savePlaylistUrl(url)) error = context.getString(R.string.error_loaded_not_saved)
                }
            } catch (e: Exception) { error = context.getString(R.string.error_load_list, e.message ?: context.getString(R.string.error_connection)) }
            finally { loading = false }
        }
    }
    fun play(channel: Channel) {
        selected = channel
        epg = emptyList()
        if (channel.kind == "live" && channel.sourceId != null) {
            activeProfileName?.let(profileStore::load)?.let { profile -> viewModelScope.launch {
                epg = withContext(Dispatchers.IO) { xtreamClient(profile).epg(channel.sourceId) }
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
    fun visibleChannels(): List<Channel> = channels.filter { (!favoritesOnly || favoriteKey(it) in favorites) && (category == ALL_CATEGORIES || it.group == category) }

    private fun xtreamClient(profile: XtreamProfile) = XtreamClient(
        profile.server, profile.username, profile.password,
        XtreamLabels(
            context.getString(R.string.uncategorized),
            context.getString(R.string.channel_untitled),
            { context.getString(R.string.episode_default, it) },
            { context.getString(R.string.season, it) },
            { season, title -> context.getString(R.string.season_episode, season, title) },
        ),
    )
}

@Composable private fun NovaApp() {
    val context = LocalContext.current
    val vm: NovaViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = NovaViewModel(context.applicationContext) as T
    })
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var playlistUrl by remember(vm.savedPlaylistUrl) { mutableStateOf(vm.savedPlaylistUrl) }
    var showXtream by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> vm.load(r.readText()) } ?: vm.reportError(context.getString(R.string.error_open_file)) } catch (e: Exception) { vm.reportError(context.getString(R.string.error_read_list, e.message ?: context.getString(R.string.error_invalid_file))) } }
    }
    val epgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> vm.loadXmlTv(reader.readText()) } } catch (e: Exception) { vm.reportError(context.getString(R.string.error_read_epg, e.message ?: context.getString(R.string.error_invalid_file))) } }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Violet, onBackground = Color.White, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
                if (landscape) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(34.dp).background(Violet, RoundedCornerShape(11.dp)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Ink) }
                        Text("NOVA", fontWeight = FontWeight.Black, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                        Spacer(Modifier.weight(1f))
                        if (vm.showSources) {
                            TextButton(onClick = { showPlaylistDialog = true }) { Text(stringResource(R.string.edit_playlist)) }
                            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.action_file)) }
                            TextButton(onClick = { showXtream = true }) { Text(stringResource(R.string.action_xtream)) }
                            TextButton(onClick = { epgPicker.launch(arrayOf("application/xml", "text/xml", "text/*", "application/octet-stream")) }) { Text(stringResource(R.string.action_epg)) }
                        } else {
                            IconButton(onClick = vm::addSource) {
                                Icon(Icons.Default.Add, stringResource(R.string.add_source), tint = Violet)
                            }
                        }
                    }
                } else Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Violet, RoundedCornerShape(14.dp)), Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Ink) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("NOVA", fontWeight = FontWeight.Black, color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (vm.showSources) {
                            FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_file)) }
                            OutlinedButton(onClick = { showXtream = true }) { Text(stringResource(R.string.action_xtream)) }
                            OutlinedButton(onClick = { epgPicker.launch(arrayOf("application/xml", "text/xml", "text/*", "application/octet-stream")) }) { Text(stringResource(R.string.action_epg)) }
                        } else {
                            FilledTonalButton(onClick = vm::addSource) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_source)) }
                        }
                    }
                }
                if (!vm.showSources && landscape && vm.channels.isNotEmpty()) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LandscapeChannelPane(vm, searchQuery, { searchQuery = it }, Modifier.weight(0.42f).fillMaxHeight())
                        Column(Modifier.weight(0.58f).fillMaxHeight()) {
                            if (vm.selected != null) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(vm.selected!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                    TextButton(onClick = vm::stop) { Text(stringResource(R.string.back_to_channels)) }
                                }
                                Player(vm.selected!!)
                                val xmltvGuide = vm.selected!!.epgId?.let { vm.xmltv[it] }.orEmpty()
                                val guide = vm.epg.ifEmpty { xmltvGuide }
                                if (guide.isNotEmpty()) {
                                    Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { items(guide) { item ->
                                        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.start.takeLast(5), color = Violet, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
                                            Column { Text(item.title, fontWeight = FontWeight.SemiBold); if (item.description.isNotBlank()) Text(item.description, color = Color(0xFF9AA3B7), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                        }
                                    } }
                                }
                            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.select_channel), color = Color(0xFF9AA3B7), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                } else if (!vm.showSources && vm.selected != null) {
                    TextButton(onClick = vm::stop) { Icon(Icons.Default.ArrowBack, null); Text(" ${stringResource(R.string.back_to_channels)}") }
                    Text(vm.selected!!.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Player(vm.selected!!)
                    val xmltvGuide = vm.selected!!.epgId?.let { vm.xmltv[it] }.orEmpty()
                    val guide = vm.epg.ifEmpty { xmltvGuide }
                    if (guide.isNotEmpty()) {
                        Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(guide) { item ->
                            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.start.takeLast(5), color = Violet, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
                                Column { Text(item.title, fontWeight = FontWeight.SemiBold); if (item.description.isNotBlank()) Text(item.description, color = Color(0xFF9AA3B7), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            }
                        } }
                    }
                } else if (vm.showSources) {
                    Text(
                        if (vm.channels.isEmpty()) stringResource(R.string.home_empty_title) else stringResource(R.string.add_another_playlist),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (vm.channels.isEmpty()) stringResource(R.string.home_empty_description) else stringResource(R.string.add_source_description),
                        color = Color(0xFF9AA3B7), modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
                    )
                    vm.error?.let { Text(it, color = Color(0xFFFF9B9B), modifier = Modifier.padding(bottom = 10.dp)) }
                    OutlinedTextField(
                        value = playlistUrl,
                        onValueChange = { playlistUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text(stringResource(R.string.playlist_address_label)) },
                        placeholder = { Text(stringResource(R.string.playlist_address_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { vm.loadUrl(playlistUrl.trim()) }),
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = playlistUrl.isNotBlank(), onClick = { vm.savePlaylistUrl(playlistUrl) }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.savedPlaylistUrl == playlistUrl.trim() && playlistUrl.isNotBlank()) stringResource(R.string.playlist_saved) else stringResource(R.string.save_playlist))
                        }
                        Button(enabled = !vm.loading && playlistUrl.isNotBlank(), onClick = { vm.loadUrl(playlistUrl.trim()) }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.loading) stringResource(R.string.loading) else stringResource(R.string.load_playlist))
                        }
                    }
                    if (vm.channels.isEmpty() && !landscape) EmptyState { picker.launch(arrayOf("*/*")) }
                } else {
                    Text(
                        if (vm.channels.isEmpty()) stringResource(R.string.home_empty_title) else when (vm.catalog) {
                            "movies" -> stringResource(R.string.catalog_movies)
                            "series" -> stringResource(R.string.catalog_series)
                            "episode" -> stringResource(R.string.catalog_episodes)
                            else -> stringResource(R.string.catalog_live)
                        },
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(if (vm.channels.isEmpty()) stringResource(R.string.home_empty_description) else stringResource(R.string.channel_count, vm.channels.size, vm.channels.map { it.group }.distinct().size), color = Color(0xFF9AA3B7), modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
                    vm.error?.let { Text(it, color = Color(0xFFFF9B9B), modifier = Modifier.padding(bottom = 10.dp)) }
                    if (vm.channels.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            listOf("live" to R.string.catalog_live, "movies" to R.string.catalog_movies, "series" to R.string.catalog_series).forEach { (key, label) ->
                                FilterChip(selected = vm.catalog == key, onClick = { vm.selectCatalog(key) }, label = { Text(stringResource(label)) })
                            }
                        }
                        OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), placeholder = { Text(stringResource(R.string.search_channel)) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !vm.favoritesOnly && vm.category == ALL_CATEGORIES, onClick = { vm.favoritesOnly = false; vm.category = ALL_CATEGORIES }, label = { Text(stringResource(R.string.filter_all)) })
                            FilterChip(selected = vm.favoritesOnly, onClick = { vm.favoritesOnly = !vm.favoritesOnly }, label = { Text(stringResource(R.string.filter_favorites)) })
                            vm.channels.map { it.group }.distinct().forEach { group -> FilterChip(selected = vm.category == group, onClick = { vm.category = if (vm.category == group) ALL_CATEGORIES else group }, label = { Text(group) }) }
                        }
                        if (vm.history.isNotEmpty()) Text(stringResource(R.string.recently_played, vm.history.take(5).joinToString(" · ")), color = Color(0xFF9AA3B7), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        val filtered = vm.visibleChannels().filter { it.name.contains(searchQuery, true) || it.group.contains(searchQuery, true) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(filtered, key = { it.sourceId ?: it.url }) { channel -> ChannelRow(channel, vm.favoriteKey(channel) in vm.favorites, { if (channel.kind == "series") vm.loadEpisodes(channel) else vm.play(channel) }, { if (channel.url.isNotBlank()) vm.toggleFavorite(channel) }) } }
                    }
                }
            }
        }
    }
    if (showPlaylistDialog) PlaylistDialog(
        value = playlistUrl,
        saved = vm.savedPlaylistUrl == playlistUrl.trim() && playlistUrl.isNotBlank(),
        loading = vm.loading,
        onValueChange = { playlistUrl = it },
        onSave = { vm.savePlaylistUrl(playlistUrl) },
        onLoad = { vm.loadUrl(playlistUrl.trim()) },
        onDismiss = { showPlaylistDialog = false },
    )
    if (showXtream) XtreamDialog(profiles = vm.savedProfiles, loading = vm.loading, onDismiss = { showXtream = false }, onDelete = vm::deleteProfile, onConnect = { profile -> showXtream = false; vm.connectXtream(profile.name, profile.server, profile.username, profile.password) })
}

@Composable private fun LandscapeChannelPane(vm: NovaViewModel, query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(when (vm.catalog) {
            "movies" -> stringResource(R.string.catalog_movies)
            "series" -> stringResource(R.string.catalog_series)
            "episode" -> stringResource(R.string.catalog_episodes)
            else -> stringResource(R.string.catalog_live)
        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("live" to R.string.catalog_live, "movies" to R.string.catalog_movies, "series" to R.string.catalog_series).forEach { (kind, label) ->
                FilterChip(selected = vm.catalog == kind, onClick = { vm.selectCatalog(kind) }, label = { Text(stringResource(label)) })
            }
        }
        OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth().padding(bottom = 6.dp), placeholder = { Text(stringResource(R.string.search_channel)) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = !vm.favoritesOnly && vm.category == ALL_CATEGORIES, onClick = { vm.favoritesOnly = false; vm.category = ALL_CATEGORIES }, label = { Text(stringResource(R.string.filter_all)) })
            FilterChip(selected = vm.favoritesOnly, onClick = { vm.favoritesOnly = !vm.favoritesOnly }, label = { Text(stringResource(R.string.filter_favorites)) })
            vm.channels.map { it.group }.distinct().forEach { group -> FilterChip(selected = vm.category == group, onClick = { vm.category = if (vm.category == group) ALL_CATEGORIES else group }, label = { Text(group) }) }
        }
        val filtered = vm.visibleChannels().filter { it.name.contains(query, true) || it.group.contains(query, true) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.sourceId ?: it.url }) { channel ->
                ChannelRow(channel, vm.favoriteKey(channel) in vm.favorites,
                    { if (channel.kind == "series") vm.loadEpisodes(channel) else vm.play(channel) },
                    { if (channel.url.isNotBlank()) vm.toggleFavorite(channel) }, compact = true)
            }
        }
    }
}

@Composable private fun PlaylistDialog(value: String, saved: Boolean, loading: Boolean, onValueChange: (String) -> Unit, onSave: () -> Unit, onLoad: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_playlist)) },
        text = { OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.playlist_address_label)) }, placeholder = { Text(stringResource(R.string.playlist_address_hint)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false), keyboardActions = KeyboardActions(onDone = { onLoad() })) },
        confirmButton = { TextButton(enabled = !loading && value.isNotBlank(), onClick = onLoad) { Text(if (loading) stringResource(R.string.loading) else stringResource(R.string.load_playlist)) } },
        dismissButton = { Row { TextButton(enabled = value.isNotBlank(), onClick = onSave) { Text(if (saved) stringResource(R.string.playlist_saved) else stringResource(R.string.save_playlist)) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } } })
}

@Composable private fun XtreamDialog(profiles: List<XtreamProfile>, loading: Boolean, onDismiss: () -> Unit, onDelete: (String) -> Unit, onConnect: (XtreamProfile) -> Unit) {
    val strings = LocalContext.current
    var name by remember { mutableStateOf("") }; var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.xtream_dialog_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.xtream_secure_info), style = MaterialTheme.typography.bodySmall)
            if (profiles.isNotEmpty()) {
                Text(stringResource(R.string.saved_profiles), fontWeight = FontWeight.SemiBold)
                profiles.forEach { profile -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { name = profile.name; server = profile.server; username = profile.username; password = profile.password }) { Text(profile.name, maxLines = 1) }
                    TextButton(onClick = { onConnect(profile) }) { Text(stringResource(R.string.connect)) }
                    TextButton(onClick = { onDelete(profile.name) }) { Text(stringResource(R.string.delete)) }
                } }
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.profile_name)) }, singleLine = true)
            OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.server_url)) }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        } }, confirmButton = { TextButton(enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(), onClick = { onConnect(XtreamProfile(name.ifBlank { strings.getString(R.string.profile_default) }, server, username, password)) }) { Text(if (loading) stringResource(R.string.loading) else stringResource(R.string.save_and_connect)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun EmptyState(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(62.dp).background(Panel, RoundedCornerShape(20.dp)), Alignment.Center) { Icon(Icons.Default.LiveTv, null, tint = Violet, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.empty_state_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.empty_state_description), color = Color(0xFF9AA3B7), modifier = Modifier.padding(8.dp))
        Button(onClick = onImport) { Icon(Icons.Default.FolderOpen, null); Text("  ${stringResource(R.string.choose_file)}") }
    }
}

@Composable private fun ChannelRow(channel: Channel, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(if (compact) 8.dp else 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 36.dp else 46.dp).background(Color(0xFF22283A), RoundedCornerShape(13.dp)), Alignment.Center) { Icon(Icons.Default.Tv, null, tint = Violet) }
        Spacer(Modifier.width(if (compact) 8.dp else 13.dp)); Column(Modifier.weight(1f)) { Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(channel.group, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA3B7), maxLines = 1) }
        IconButton(onClick = onFavorite, modifier = if (compact) Modifier.size(38.dp) else Modifier) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite), tint = if (favorite) Violet else Color.Gray, modifier = Modifier.size(if (compact) 19.dp else 24.dp)) }
        Icon(Icons.Default.PlayCircle, null, tint = Violet, modifier = Modifier.size(if (compact) 24.dp else 28.dp))
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
            null to context.getString(R.string.player_init_error, "${exception.javaClass.simpleName}: ${exception.localizedMessage ?: context.getString(R.string.player_init_unknown)}")
        }
    }
    val player = initResult.first
    if (player == null) {
        Text(initResult.second ?: stringResource(R.string.player_invalid_url), color = Color(0xFFFF9B9B), modifier = Modifier.padding(16.dp))
    } else {
        DisposableEffect(player) {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = context.getString(R.string.player_error, error.errorCodeName, error.message.orEmpty())
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
                    Icon(Icons.Default.FullscreenExit, stringResource(R.string.exit_fullscreen), tint = Color.White)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(18.dp))) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { fullScreen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Fullscreen, stringResource(R.string.fullscreen), tint = Color.White)
                }
            }
        }
        playbackError?.let { Text(it, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
}
