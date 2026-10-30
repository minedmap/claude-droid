package dev.minedmap.claudedroid.ui

/** Pure-Kotlin LRU bounded by total weight (no android.util.LruCache so it is JVM-testable). */
class SizedLru<K : Any, V : Any>(private val maxSize: Long, private val sizeOf: (V) -> Long) {
  private val map = LinkedHashMap<K, V>(16, 0.75f, true)
  private var total = 0L

  @Synchronized fun get(k: K): V? = map[k]

  @Synchronized fun put(k: K, v: V) {
    val w = sizeOf(v)
    if (w > maxSize) { map.remove(k)?.let { total -= sizeOf(it) }; return }
    map.put(k, v)?.let { total -= sizeOf(it) }
    total += w
    val iter = map.entries.iterator()
    while (total > maxSize && iter.hasNext()) {
      val e = iter.next()
      if (e.key == k) continue
      total -= sizeOf(e.value); iter.remove()
    }
  }

  @Synchronized fun size(): Int = map.size
  @Synchronized fun weight(): Long = total
}
