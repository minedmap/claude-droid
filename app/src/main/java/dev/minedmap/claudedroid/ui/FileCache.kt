package dev.minedmap.claudedroid.ui
import android.graphics.Bitmap
import dev.minedmap.claudedroid.net.FileStat

/** App-level caches so FileCards that scroll off/on screen do not refetch. Keys are path + size. */
object FileCache {
  fun key(path: String, size: Long) = "$path|$size"

  val bitmaps = SizedLru<String, Bitmap>(32L * 1024 * 1024) { it.allocationByteCount.toLong() }
  val texts = SizedLru<String, String>(4L * 1024 * 1024) { it.length * 2L }
  val bytes = SizedLru<String, ByteArray>(16L * 1024 * 1024) { it.size.toLong() }

  private class StatEntry(val at: Long, val stat: FileStat)
  private val stats = SizedLru<String, StatEntry>(512) { 1L }
  private const val STAT_TTL_MS = 30_000L

  /** Recent stat (<30s) so scrolling back does not hit the bridge; null when stale/unknown. */
  fun freshStat(path: String): FileStat? =
    stats.get(path)?.takeIf { System.currentTimeMillis() - it.at < STAT_TTL_MS }?.stat

  fun putStat(path: String, st: FileStat) { stats.put(path, StatEntry(System.currentTimeMillis(), st)) }
}
