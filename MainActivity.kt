package com.sfxreader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var openId by remember { mutableStateOf<String?>(null) }
    val id = openId
    if (id == null) Library(onOpen = { openId = it }) else ReaderScreen(id, onBack = { openId = null })
}

@Composable
fun Library(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf(Store.list(ctx)) }
    var lang by remember { mutableStateOf(Lang.KOREAN) }
    var status by remember { mutableStateOf<String?>(null) }
    val live by LiveService.running.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                Importer.import(ctx, uris, lang) { status = it }
                chapters = Store.list(ctx)
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "خطأ: ${e.message}"
            }
        }
    }

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(ctx, LiveService::class.java)
                .putExtra(LiveService.EXTRA_CODE, r.resultCode)
                .putExtra(LiveService.EXTRA_DATA, data)
                .putExtra(LiveService.EXTRA_LANG, lang.name)
            ContextCompat.startForegroundService(ctx, i)
            (ctx as? Activity)?.moveTaskToBack(true) // ارجع لشاشتك السابقة وافتح تطبيق المانهوا
        }
    }
    fun askCapture() {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }

    val busy = status?.startsWith("تحليل") == true || status?.startsWith("جاري") == true

    Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding()) {
        Text("قارئ المانهوا بالمؤثرات", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("لغة المؤثرات بالصور:")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.values().forEach {
                FilterChip(selected = lang == it, onClick = { lang = it }, enabled = !live, label = { Text(it.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("الوضع المباشر (بالخلفية)", style = MaterialTheme.typography.titleMedium)
                Text("شغّله، افتح أي تطبيق مانهوا، واقرأ. الأصوات بتطلع تلقائياً.")
                Spacer(Modifier.height(8.dp))
                if (!live) {
                    Button(onClick = {
                        val needNotif = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else askCapture()
                    }) { Text("ابدأ") }
                } else {
                    Button(onClick = {
                        ctx.startService(Intent(ctx, LiveService::class.java).setAction(LiveService.ACTION_STOP))
                    }) { Text("إيقاف (شغّال حالياً)") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("أو استورد فصل للقراءة داخل التطبيق:")
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) },
            enabled = !busy) {
            Text("استيراد فصل (صور أو CBZ/ZIP)")
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
            if (!it.startsWith("خطأ")) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chapters) { ch ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(ch.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.name, Modifier.weight(1f))
                        TextButton(onClick = { Store.delete(ctx, ch.id); chapters = Store.list(ctx) }) { Text("حذف") }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)

    val data = remember(id) { Store.load(ctx, id) }
    val byPage = remember(data) { data.markers.groupBy { it.page } }
    val player = remember { SfxPlayer(ctx) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var soundOn by remember { mutableStateOf(true) }
    val soundState = rememberUpdatedState(soundOn)
    val listState = rememberLazyListState()
    val played = remember { mutableSetOf<Int>() }

    LaunchedEffect(listState, byPage) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            for (item in info.visibleItemsInfo) {
                val ms = byPage[item.index] ?: continue
                if (item.size < 200) continue // الصورة لسا ما انحمّلت
                for (m in ms) {
                    val y = item.offset + m.y * item.size
                    if (y > mid + 300) played.remove(m.id)            // رجع المستخدم لفوق: أعد تفعيل المؤثر
                    else if (y <= mid && y > mid - 250 && played.add(m.id) && soundState.value) player.play(m.category)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(data.pages) { file ->
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(file)
                        .size(Size(1080, Dimension.Undefined)).allowHardware(false).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().alpha(0.6f)) {
            TextButton(onClick = { soundOn = !soundOn }) { Text(if (soundOn) "🔊" else "🔇") }
            TextButton(onClick = onBack) { Text("✕") }
        }
    }
}
