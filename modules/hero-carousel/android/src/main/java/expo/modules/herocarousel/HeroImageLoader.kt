package expo.modules.herocarousel

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Header-aware image loader with an in-memory cache, mirroring
 * `HeroImageLoader` on iOS. The Jellyfin server can sit behind an auth proxy
 * (Cloudflare Access, Pangolin, ...), so every request carries the custom
 * headers passed down from JS.
 *
 * Fetches run on a process-wide scope with a per-URL inflight latch rather
 * than in the caller's coroutine: paging swaps cards in and out constantly,
 * and a card that scrolls away mid-download should hand its work to the next
 * one that asks instead of cancelling it and starting over.
 */
internal object HeroImageLoader {
  // Backdrops decode to a few MB each and ten of them stay reachable while
  // the carousel is on screen.
  private const val MAX_CACHE_BYTES = 48 * 1024 * 1024

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  private const val BLUR_WIDTH = 64
  private const val MAX_BLUR_CACHE_BYTES = 1024 * 1024

  private val blurCache = object : LruCache<String, Bitmap>(MAX_BLUR_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

  fun cached(url: String): Bitmap? = cache.get(url)

  suspend fun load(url: String, headers: Map<String, String>): Bitmap? {
    cache.get(url)?.let { return it }

    val deferred = inflight[url] ?: run {
      val fetch = scope.async(start = CoroutineStart.LAZY) { fetch(url, headers) }
      // Another caller may have registered between the read and the write;
      // whoever lands first owns the fetch and the loser is never started.
      val existing = inflight.putIfAbsent(url, fetch)
      if (existing != null) {
        fetch.cancel()
        existing
      } else {
        fetch.start()
        fetch
      }
    }
    return deferred.await()
  }

  /**
   * A very small copy of a cached image for the glass panel: drawn scaled up, it
   * reads as a blur with no per-frame cost, unlike `Modifier.blur`, which makes
   * the RenderThread re-run a blur shader on an offscreen layer every frame.
   * Halved step by step because one large bilinear step skips most source pixels
   * and comes out speckled.
   */
  fun blurred(url: String): Bitmap? {
    blurCache.get(url)?.let { return it }
    var small = cache.get(url) ?: return null
    while (small.width > BLUR_WIDTH && small.height > 1) {
      small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
    }
    blurCache.put(url, small)
    return small
  }

  /** Decodes at no more than twice the screen width instead of the server's full size. */
  private fun decodeForDisplay(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= screenWidth) {
      sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  private fun fetch(url: String, headers: Map<String, String>): Bitmap? {
    return try {
      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        for ((name, value) in headers) {
          setRequestProperty(name, value)
        }
      }
      try {
        if (connection.responseCode !in 200..299) {
          return null
        }
        decodeForDisplay(connection.inputStream.use { it.readBytes() })
          ?.also { cache.put(url, it) }
      } finally {
        connection.disconnect()
      }
    } catch (_: Exception) {
      null
    } finally {
      inflight.remove(url)
    }
  }
}
