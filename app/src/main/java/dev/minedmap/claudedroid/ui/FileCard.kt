package dev.minedmap.claudedroid.ui
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.mikepenz.markdown.m3.Markdown
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val IMG_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private val TEXT_EXT = setOf("txt", "csv", "log", "html", "svg")
private const val MAX_IMG = 20L * 1024 * 1024
private const val MAX_TEXT = 2L * 1024 * 1024

private fun mimeOf(ext: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

private fun fmtSize(n: Long) = when {
  n >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
  n >= 1 shl 10 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
  else -> "$n B"
}

private fun decodeScaled(bytes: ByteArray, maxSide: Int = 1600): Bitmap? {
  val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
  var s = 1
  while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}

private fun prettyJson(t: String): String = try {
  val s = t.trim()
  if (s.startsWith("[")) JSONArray(s).toString(2) else JSONObject(s).toString(2)
} catch (_: Exception) { t }

@androidx.annotation.RequiresApi(29)
private fun saveToDownloads(ctx: Context, name: String, mime: String, bytes: ByteArray): Boolean {
  val cv = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ClaudeDroid")
  }
  val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
  ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
  return true
}

@Composable
fun FileCard(path: String) {
  val ctx = LocalContext.current
  // cheap handle over the shared OkHttpClient; re-read each composition so Settings changes apply
  val client = BridgeClient.of(Prefs(ctx))
  val scope = rememberCoroutineScope()
  val name = path.substringAfterLast('/')
  val ext = name.substringAfterLast('.', "").lowercase()
  var stat by remember(path) { mutableStateOf<FileStat?>(FileCache.freshStat(path)) }
  var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
  var text by remember(path) { mutableStateOf<String?>(null) }
  var expanded by remember(path) { mutableStateOf(false) }
  var zoom by remember(path) { mutableStateOf(false) }
  var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
  var err by remember(path) { mutableStateOf<String?>(null) }
  suspend fun bytes(): ByteArray {
    val k = FileCache.key(path, stat?.size ?: -1L)
    return FileCache.bytes.get(k) ?: client.fetch(path).also { FileCache.bytes.put(k, it) }
  }

  val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
    val b = pendingBytes
    if (uri != null && b != null) scope.launch(Dispatchers.IO) {
      ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) }
      withContext(Dispatchers.Main) { Toast.makeText(ctx, "저장됨", Toast.LENGTH_SHORT).show() }
    }
    pendingBytes = null
  }

  LaunchedEffect(path) {
    val st = FileCache.freshStat(path) ?: client.stat(path)?.also { FileCache.putStat(path, it) }
    stat = st
    if (st == null) err = "브리지 응답 없음 또는 접근 불가 경로"
    if (st != null && st.exists && !st.isDir) try {
      val ck = FileCache.key(path, st.size)
      when {
        ext in IMG_EXT && st.size <= MAX_IMG -> {
          bitmap = FileCache.bitmaps.get(ck)
            ?: (withContext(Dispatchers.Default) { decodeScaled(bytes()) } ?: throw IllegalStateException("이미지 디코딩 실패"))
              .also { FileCache.bitmaps.put(ck, it) }
        }
        ext == "pdf" && st.size <= MAX_IMG -> {
          bitmap = FileCache.bitmaps.get(ck)
            ?: (withContext(Dispatchers.IO) { renderPdfPage(ctx, path, bytes()) } ?: throw IllegalStateException("PDF 렌더 실패"))
              .also { FileCache.bitmaps.put(ck, it) }
        }
        (ext == "md" || ext == "json" || ext in TEXT_EXT) && st.size <= MAX_TEXT -> {
          text = FileCache.texts.get(ck) ?: run {
            val raw = String(bytes(), Charsets.UTF_8)
            (if (ext == "json") prettyJson(raw) else raw).also { FileCache.texts.put(ck, it) }
          }
        }
      }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { err = "미리보기 실패: ${e.message ?: e.javaClass.simpleName}" }
  }

  fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

  fun download() {
    scope.launch {
      try {
        val bytes = bytes()
        if (Build.VERSION.SDK_INT >= 29) {
          val ok = withContext(Dispatchers.IO) { saveToDownloads(ctx, name, mimeOf(ext), bytes) }
          toast(if (ok) "Download/ClaudeDroid 에 저장됨" else "저장 실패")
        } else {
          pendingBytes = bytes
          createDoc.launch(name)
        }
      } catch (e: Exception) { toast("다운로드 실패: ${e.message}") }
    }
  }

  fun share() {
    scope.launch {
      try {
        shareBytes(ctx, name, ext, bytes())
      } catch (e: Exception) { toast("공유 실패: ${e.message}") }
    }
  }

  Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (ext in IMG_EXT) Icons.Filled.Image else Icons.Filled.Description, null)
        Column(Modifier.weight(1f)) {
          Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
          Text(
            when { stat == null && err == null -> "불러오는 중…"; stat == null -> "상태 확인 실패"; stat?.exists == false -> "파일 없음"; else -> fmtSize(stat!!.size) },
            style = MaterialTheme.typography.bodySmall)
          err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
          if (ext == "svg") Text("SVG 소스 표시", style = MaterialTheme.typography.labelSmall)
        }
      }
      bitmap?.let { bmp ->
        Image(bmp.asImageBitmap(), name, Modifier.fillMaxWidth().heightIn(max = 240.dp).clickable { zoom = true }, contentScale = ContentScale.Fit)
      }
      text?.let { t ->
        val shown = t.lines().take(200).joinToString("\n")
        Box(Modifier.fillMaxWidth().heightIn(max = if (expanded) 4000.dp else 160.dp).clipToBounds()) {
          if (ext == "md") SelectionContainer { Markdown(content = shown, modifier = Modifier.fillMaxWidth()) }
          else SelectionContainer { Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
      }
      if (stat?.exists == true) Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        if (bitmap != null || text != null) TextButton(onClick = { if (bitmap != null) zoom = true else expanded = !expanded }) {
          Icon(Icons.Filled.Fullscreen, null, Modifier.size(18.dp)); Text(" 열기/확대")
        }
        TextButton(onClick = { download() }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Text(" 다운로드") }
        TextButton(onClick = { share() }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" 공유") }
      }
    }
  }

  if (zoom && bitmap != null) ZoomDialog(bitmap!!, name) { zoom = false }
}

@Composable
private fun ZoomDialog(bmp: Bitmap, name: String, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    var scale by remember { mutableStateOf(1f) }
    var ox by remember { mutableStateOf(0f) }
    var oy by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() }
      .pointerInput(Unit) {
        detectTransformGestures { _, pan, z, _ ->
          scale = (scale * z).coerceIn(1f, 8f)
          ox += pan.x; oy += pan.y
        }
      }) {
      Image(bmp.asImageBitmap(), name,
        Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
        contentScale = ContentScale.Fit)
    }
  }
}

/** First page of a PDF via the platform PdfRenderer (needs a seekable file). */
private fun renderPdfPage(ctx: Context, key: String, bytes: ByteArray): Bitmap? {
  val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
  val f = File(dir, "${key.hashCode().toUInt()}.pdf").apply { writeBytes(bytes) }
  return try {
    android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
      val r = android.graphics.pdf.PdfRenderer(pfd)
      try {
        if (r.pageCount == 0) return null
        val pg = r.openPage(0)
        try {
          val w = 1200; val h = (w.toFloat() * pg.height / pg.width).toInt().coerceIn(1, 4000)
          Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(android.graphics.Color.WHITE)
            pg.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          }
        } finally { pg.close() }
      } finally { r.close() }
    }
  } finally { f.delete() }
}

/** Share via FileProvider. Old shared temp files (>1h) are purged; each share gets its own subdir so names never collide. */
private suspend fun shareBytes(ctx: Context, name: String, ext: String, bytes: ByteArray) {
  val uri = withContext(Dispatchers.IO) {
    val root = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 3600_000
    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    val dir = File(root, System.nanoTime().toString()).apply { mkdirs() }
    val safe = name.replace(Regex("""[/\\:*?"<>|]"""), "_").ifBlank { "file" }
    val f = File(dir, safe).apply { writeBytes(bytes) }
    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
  }
  val i = Intent(Intent.ACTION_SEND).apply {
    type = mimeOf(ext); putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  ctx.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Image returned inside a tool result, saved by ChatViewModel under filesDir/images. */
@Composable
fun LocalImageCard(localPath: String) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val name = localPath.substringAfterLast('/')
  val ext = name.substringAfterLast('.', "png").lowercase()
  var bitmap by remember(localPath) { mutableStateOf<Bitmap?>(null) }
  var err by remember(localPath) { mutableStateOf<String?>(null) }
  var zoom by remember(localPath) { mutableStateOf(false) }
  LaunchedEffect(localPath) {
    val ck = FileCache.key("local:$localPath", File(localPath).length())
    val b = FileCache.bitmaps.get(ck)
      ?: withContext(Dispatchers.IO) { runCatching { decodeScaled(File(localPath).readBytes()) }.getOrNull() }?.also { FileCache.bitmaps.put(ck, it) }
    if (b == null) err = "이미지를 열 수 없음" else bitmap = b
  }
  fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
  Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Image, null)
        Text("도구 결과 이미지", style = MaterialTheme.typography.titleSmall)
      }
      err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      bitmap?.let { bmp ->
        Image(bmp.asImageBitmap(), name, Modifier.fillMaxWidth().heightIn(max = 240.dp).clickable { zoom = true }, contentScale = ContentScale.Fit)
        Row {
          TextButton(onClick = { zoom = true }) { Icon(Icons.Filled.Fullscreen, null, Modifier.size(18.dp)); Text(" 확대") }
          TextButton(onClick = {
            scope.launch {
              try {
                val bytes = withContext(Dispatchers.IO) { File(localPath).readBytes() }
                if (Build.VERSION.SDK_INT >= 29) {
                  val ok = withContext(Dispatchers.IO) { saveToDownloads(ctx, name, mimeOf(ext), bytes) }
                  toast(if (ok) "Download/ClaudeDroid 에 저장됨" else "저장 실패")
                } else toast("Android 10 이상에서 지원")
              } catch (e: Exception) { toast("저장 실패: ${e.message}") }
            }
          }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Text(" 다운로드") }
          TextButton(onClick = {
            scope.launch {
              try { shareBytes(ctx, name, ext, withContext(Dispatchers.IO) { File(localPath).readBytes() }) }
              catch (e: Exception) { toast("공유 실패: ${e.message}") }
            }
          }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" 공유") }
        }
      }
    }
  }
  if (zoom && bitmap != null) ZoomDialog(bitmap!!, name) { zoom = false }
}
