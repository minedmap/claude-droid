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

private val IMG_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
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
  val client = remember { val p = Prefs(ctx); BridgeClient(p.bridgeUrl, p.token) }
  val scope = rememberCoroutineScope()
  val name = path.substringAfterLast('/')
  val ext = name.substringAfterLast('.', "").lowercase()
  var stat by remember(path) { mutableStateOf<FileStat?>(null) }
  var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
  var text by remember(path) { mutableStateOf<String?>(null) }
  var expanded by remember(path) { mutableStateOf(false) }
  var zoom by remember(path) { mutableStateOf(false) }
  var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

  val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
    val b = pendingBytes
    if (uri != null && b != null) scope.launch(Dispatchers.IO) {
      ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) }
      withContext(Dispatchers.Main) { Toast.makeText(ctx, "저장됨", Toast.LENGTH_SHORT).show() }
    }
    pendingBytes = null
  }

  LaunchedEffect(path) {
    val st = client.stat(path)
    stat = st
    if (st != null && st.exists && !st.isDir) runCatching {
      when {
        ext in IMG_EXT && st.size <= MAX_IMG -> bitmap = withContext(Dispatchers.Default) { decodeScaled(client.fetch(path)) }
        (ext == "md" || ext == "json" || ext in TEXT_EXT) && st.size <= MAX_TEXT -> {
          val raw = String(client.fetch(path), Charsets.UTF_8)
          text = if (ext == "json") prettyJson(raw) else raw
        }
      }
      Unit
    }
  }

  fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

  fun download() {
    scope.launch {
      try {
        val bytes = client.fetch(path)
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
        val bytes = client.fetch(path)
        val uri = withContext(Dispatchers.IO) {
          val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
          val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
          f.writeBytes(bytes)
          FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        }
        val i = Intent(Intent.ACTION_SEND).apply {
          type = mimeOf(ext); putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            when { stat == null -> "불러오는 중…"; stat?.exists == false -> "파일 없음"; else -> fmtSize(stat!!.size) },
            style = MaterialTheme.typography.bodySmall)
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

  if (zoom && bitmap != null) {
    Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      var scale by remember { mutableStateOf(1f) }
      var ox by remember { mutableStateOf(0f) }
      var oy by remember { mutableStateOf(0f) }
      Box(Modifier.fillMaxSize().background(Color.Black).clickable { zoom = false }
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, z, _ ->
            scale = (scale * z).coerceIn(1f, 8f)
            ox += pan.x; oy += pan.y
          }
        }) {
        Image(bitmap!!.asImageBitmap(), name,
          Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
          contentScale = ContentScale.Fit)
      }
    }
  }
}
