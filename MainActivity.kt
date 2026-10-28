package ru.chronos.stories

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import ru.chronos.stories.data.*
import ru.chronos.stories.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var selectedUriForPlayback: String? = null

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.orEmpty().forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            lifecycleScope.launch {
                val existing = db.audioDao().findByUri(uri.toString())
                if (existing == null) {
                    val name = displayName(uri) ?: "Аудиоистория"
                    db.audioDao().insert(AudioItem(title = name.substringBeforeLast('.', name), uri = uri.toString(), fileName = name))
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.get(this)
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({ try { controller = controllerFuture?.get() } catch (_: Exception) {} }, ContextCompat.getMainExecutor(this))
        setContent { ChronosApp(db, onImport = { importAudio.launch(arrayOf("audio/*")) }, onPlay = { item -> play(item) }, onPersistPosition = { item, pos -> lifecycleScope.launch { db.audioDao().update(item.copy(positionMs = pos, lastPlayedAt = System.currentTimeMillis())) } }, onRename = { item, title, author -> lifecycleScope.launch { db.audioDao().update(item.copy(title = title, author = author)) } }, onBookmark = { item, pos -> lifecycleScope.launch { db.audioDao().insertBookmark(Bookmark(audioId = item.id, positionMs = pos, title = "Закладка ${formatTime(pos)}")) } }, onSeekBookmark = { bm -> lifecycleScope.launch { db.audioDao().getById(bm.audioId)?.let { play(it, bm.positionMs) } } }, getPosition = { controller?.currentPosition ?: 0L }, isPlaying = { controller?.isPlaying == true }, onPause = { controller?.pause() }, onResume = { controller?.play() }, onSeek = { delta -> controller?.let { it.seekTo((it.currentPosition + delta).coerceAtLeast(0L)) } }, onSpeed = { speed -> controller?.setPlaybackSpeed(speed) }) }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun play(item: AudioItem, position: Long = item.positionMs) {
        val c = controller ?: return
        c.setMediaItem(MediaItem.fromUri(item.uri), position)
        c.prepare()
        c.play()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

private enum class Tab(val label: String) {
    HOME("Главная"), LIBRARY("Библиотека"), BOOKMARKS("Закладки"), AUTHORS("Авторы"), SETTINGS("Настройки")
}

@Composable
private fun ChronosApp(
    db: AppDatabase,
    onImport: () -> Unit,
    onPlay: (AudioItem) -> Unit,
    onPersistPosition: (AudioItem, Long) -> Unit,
    onRename: (AudioItem, String, String) -> Unit,
    onBookmark: (AudioItem, Long) -> Unit,
    onSeekBookmark: (Bookmark) -> Unit,
    getPosition: () -> Long,
    isPlaying: () -> Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit
) {
    val items by db.audioDao().observeAll().collectAsState(initial = emptyList())
    val bookmarks by db.audioDao().observeBookmarks().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(Tab.HOME) }
    var current by remember { mutableStateOf<AudioItem?>(null) }
    var dark by remember { mutableStateOf(false) }
    var amoled by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seekPosition by remember { mutableLongStateOf(0L) }
    var editItem by remember { mutableStateOf<AudioItem?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(current?.id) {
        while (current != null) {
            seekPosition = getPosition()
            current?.let { onPersistPosition(it, seekPosition) }
            delay(4000)
        }
    }
    MaterialTheme(colorScheme = when { amoled -> darkColorScheme(background = androidx.compose.ui.graphics.Color.Black, surface = androidx.compose.ui.graphics.Color.Black, primary = androidx.compose.ui.graphics.Color(0xFFB8C7FF)); dark -> darkColorScheme(); else -> lightColorScheme() }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t; showPlayer = false }, icon = {
                            Icon(when(t) {
                                Tab.HOME -> Icons.Default.Home
                                Tab.LIBRARY -> Icons.Default.LibraryMusic
                                Tab.BOOKMARKS -> Icons.Default.Bookmark
                                Tab.AUTHORS -> Icons.Default.People
                                Tab.SETTINGS -> Icons.Default.Settings
                            }, contentDescription = t.label)
                        }, label = { Text(t.label, maxLines = 1) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = if (showPlayer && current != null) "player" else tab.name, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
                    when (screen) {
                        "player" -> current?.let { item ->
                            PlayerScreen(item, seekPosition, bookmarks.filter { it.audioId == item.id }, isPlaying(), speed,
                                onPlayPause = { if (isPlaying()) onPause() else onResume() },
                                onSeek = { pos -> onSeek(pos - seekPosition); seekPosition = pos },
                                onBack = { showPlayer = false },
                                onBookmark = { onBookmark(item, getPosition()) },
                                onSpeed = { speed = it; onSpeed(it) },
                                onEdit = { editItem = item; editTitle = item.title; editAuthor = item.author })
                        }
                        "HOME" -> HomeScreen(items, onImport, onPlay = { current = it; onPlay(it); showPlayer = true }, onOpenLibrary = { tab = Tab.LIBRARY }, onContinue = { item -> current = item; onPlay(item); showPlayer = true })
                        "LIBRARY" -> LibraryScreen(items, onImport, onPlay = { current = it; onPlay(it); showPlayer = true }, onFavorite = { item -> /* persisted below through edit/update flow */ }, onEdit = { editItem = it; editTitle = it.title; editAuthor = it.author })
                        "BOOKMARKS" -> BookmarkScreen(bookmarks, items, onSeekBookmark)
                        "AUTHORS" -> AuthorsScreen(items)
                        else -> SettingsScreen(dark, amoled, speed, onDark = { dark = it; if (it) amoled = false }, onAmoled = { amoled = it; if (it) dark = true }, onSpeed = { speed = it; onSpeed(it) })
                    }
                }
            }
        }
        if (editItem != null) {
            AlertDialog(onDismissRequest = { editItem = null }, title = { Text("Редактировать историю") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(editAuthor, { editAuthor = it }, label = { Text("Автор") }, singleLine = true)
                }
            }, confirmButton = { TextButton(onClick = { editItem?.let { onRename(it, editTitle.ifBlank { it.title }, editAuthor) }; editItem = null }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { editItem = null }) { Text("Отмена") } })
        }
    }
}

@Composable
private fun HomeScreen(items: List<AudioItem>, onImport: () -> Unit, onPlay: (AudioItem) -> Unit, onOpenLibrary: () -> Unit, onContinue: (AudioItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Chronos", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("Твоя библиотека историй", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Продолжить слушать", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val last = items.firstOrNull { it.positionMs > 0 && !it.isCompleted }
                    if (last != null) {
                        Text(last.title, style = MaterialTheme.typography.bodyLarge)
                        Text(if (last.author.isBlank()) "Автор не указан" else last.author)
                        Button(onClick = { onContinue(last) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Продолжить") }
                    } else {
                        Text("Добавь аудиоисторию, чтобы она появилась здесь.")
                        Button(onClick = onImport) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить истории") }
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Импорт") }
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.weight(1f)) { Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(6.dp)); Text("Библиотека") }
        } }
        item { Text("Недавно добавленные", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (items.isEmpty()) item { EmptyState("Пока здесь тихо", "Импортируй MP3 или другой аудиофайл из памяти телефона.") }
        items(items.take(5), key = { it.id }) { item -> AudioRow(item, onClick = { onPlay(item) }, onEdit = {}) }
    }
}

@Composable
private fun LibraryScreen(items: List<AudioItem>, onImport: () -> Unit, onPlay: (AudioItem) -> Unit, onFavorite: (AudioItem) -> Unit, onEdit: (AudioItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var compact by remember { mutableStateOf(false) }
    val filtered = items.filter { it.title.contains(query, true) || it.author.contains(query, true) || it.fileName.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Библиотека", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onImport) { Icon(Icons.Default.Add, "Добавить аудио") }
            IconButton(onClick = { compact = !compact }) { Icon(if (compact) Icons.Default.ViewAgenda else Icons.Default.ViewList, "Изменить вид списка") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск по названию и автору") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        if (filtered.isEmpty()) EmptyState("Ничего не найдено", "Добавь аудиофайлы или измени запрос.")
        else LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { item -> AudioRow(item, onClick = { onPlay(item) }, onEdit = { onEdit(item) }, compact = compact) }
        }
    }
}

@Composable
private fun AudioRow(item: AudioItem, onClick: () -> Unit, onEdit: () -> Unit, compact: Boolean = false) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(if (compact) 10.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(if (compact) 48.dp else 58.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.author.ifBlank { item.fileName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.positionMs > 0) LinearProgressIndicator(progress = { (item.positionMs.toFloat() / item.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Редактировать") }
        }
    }
}

@Composable
private fun PlayerScreen(item: AudioItem, position: Long, bookmarks: List<Bookmark>, playing: Boolean, speed: Float, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onBack: () -> Unit, onBookmark: () -> Unit, onSpeed: (Float) -> Unit, onEdit: () -> Unit) {
    var slider by remember(item.id) { mutableFloatStateOf(position.toFloat()) }
    var sleep by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ExpandMore, "Свернуть") }; Text("Сейчас играет", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Редактировать") } }
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(34.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 24.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(110.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.author.ifBlank { "Автор не указан" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = slider.coerceIn(0f, maxOf(item.durationMs, 1L).toFloat()), onValueChange = { slider = it }, onValueChangeFinished = { onSeek(slider.toLong()) }, valueRange = 0f..maxOf(item.durationMs, 1L).toFloat())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position)); Text(formatTime(item.durationMs)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            IconButton(onClick = { onSeek((position - 15000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Назад на 15 секунд") }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Пауза" else "Воспроизвести", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = { onSeek(position + 15000) }) { Icon(Icons.Default.Forward10, "Вперёд на 15 секунд") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onBookmark) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(4.dp)); Text("Закладка") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) { Icon(Icons.Default.Speed, null); Spacer(Modifier.width(4.dp)); Text("${"%.2f".format(speed)}×") }
                DropdownMenu(expanded, { expanded = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s -> DropdownMenuItem(text = { Text("${s}×") }, onClick = { onSpeed(s); expanded = false }) }
                }
            }
            TextButton(onClick = { sleep = !sleep }) { Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(4.dp)); Text(if (sleep) "Таймер вкл." else "Сон") }
        }
        if (sleep) Text("Таймер сна: функция остановки через 30 минут будет добавлена следующим этапом.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (bookmarks.isNotEmpty()) {
            Text("Закладки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            bookmarks.take(3).forEach { Text("${it.title} · ${formatTime(it.positionMs)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BookmarkScreen(bookmarks: List<Bookmark>, items: List<AudioItem>, onClick: (Bookmark) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Закладки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (bookmarks.isEmpty()) EmptyState("Здесь будут важные моменты", "Во время прослушивания нажми «Закладка».")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp)) {
            items(bookmarks, key = { it.id }) { bm ->
                val audio = items.firstOrNull { it.id == bm.audioId }
                Card(Modifier.fillMaxWidth().clickable { onClick(bm) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(bm.title, fontWeight = FontWeight.SemiBold); Text("${audio?.title ?: "История недоступна"} · ${formatTime(bm.positionMs)}", style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Default.PlayArrow, "Перейти")
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorsScreen(items: List<AudioItem>) {
    val groups = items.filter { it.author.isNotBlank() }.groupBy { it.author }.toSortedMap()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Авторы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (groups.isEmpty()) EmptyState("Пока нет авторов", "Укажи автора в редакторе информации об истории.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp)) {
            groups.forEach { (author, works) ->
                item(key = author) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(author, fontWeight = FontWeight.SemiBold); Text("${works.size} произведений", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(dark: Boolean, amoled: Boolean, speed: Float, onDark: (Boolean) -> Unit, onAmoled: (Boolean) -> Unit, onSpeed: (Float) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Настрой приложение под себя.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsCard("Внешний вид") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Тёмная тема"); Text("Более спокойный интерфейс", style = MaterialTheme.typography.bodySmall) }; Switch(dark, onDark) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("AMOLED-чёрная тема"); Text("Фон #000000", style = MaterialTheme.typography.bodySmall) }; Switch(amoled, onAmoled) }
        } }
        item { SettingsCard("Проигрыватель") {
            Text("Скорость воспроизведения: ${"%.2f".format(speed)}×")
            Slider(value = speed, onValueChange = onSpeed, valueRange = 0.5f..3f, steps = 9)
            Text("Изменение скорости действует на текущий сеанс.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SettingsCard("Данные и приватность") {
            Text("Библиотека и прогресс хранятся локально на устройстве.")
            Text("Аудиофайлы не загружаются на сервер.", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsCard("О приложении") { Text("Chronos Stories · версия 0.1.0"); Text("Локальный аудиоплеер для личной коллекции историй.") } }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AutoStories, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
