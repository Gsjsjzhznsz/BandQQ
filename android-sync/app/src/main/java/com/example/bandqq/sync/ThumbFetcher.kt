package com.example.bandqq.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图片缩略图抓取器（手机端下载 → 压缩 → base64 data URI → 互联下发手环）。
 *
 * 手环资源受限（Band 9 内存/带宽），这里做硬约束：
 * - 下载原图上限 2MB，超限直接放弃（回退 [图片] 占位）
 * - 解码后最长边压到 96px、JPEG 质量 55，单图 base64 通常 < 4KB
 * - 全局并发 ≤ 2，4 秒超时，失败静默回退
 */
class ThumbFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val inflight = java.util.concurrent.Semaphore(2)

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024
        private const val THUMB_MAX_EDGE = 96
        private const val JPEG_QUALITY = 55
        private const val TIMEOUT_MS = 4000L
        private const val MAX_BASE64_BYTES = 12 * 1024
    }

    fun fetch(url: String, callback: (String?) -> Unit) {
        if (url.isBlank()) {
            callback(null)
            return
        }
        val finished = AtomicBoolean(false)
        // 超时兜底：到点未完成按失败回退，绝不阻塞手环收消息
        val timeout = android.os.Handler(android.os.Looper.getMainLooper())
        timeout.postDelayed({
            if (finished.compareAndSet(false, true)) callback(null)
        }, TIMEOUT_MS)
        Thread {
            val result = try {
                inflight.acquire()
                try { downloadAndScale(url) } finally { inflight.release() }
            } catch (_: Throwable) {
                null
            }
            if (finished.compareAndSet(false, true)) {
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
                } catch (_: Throwable) {
                    callback(result)
                }
            }
        }.start()
    }

    private fun downloadAndScale(url: String): String? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            // 流式限量读取，防止超大图撑爆内存
            val source = body.source()
            val buffer = okio.Buffer()
            var read: Long
            var total = 0L
            while (source.read(buffer, 8192).also { read = it } != -1L) {
                total += read
                if (total > MAX_DOWNLOAD_BYTES) return null
            }
            val bytes = buffer.readByteArray()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            var edge = maxOf(opts.outWidth, opts.outHeight)
            while (edge / sample > THUMB_MAX_EDGE * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return null
            val scaled = scaleToFit(raw, THUMB_MAX_EDGE)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            if (raw !== scaled) raw.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            if (b64.length > MAX_BASE64_BYTES) return null
            return "data:image/jpeg;base64,$b64"
        }
    }

    private fun scaleToFit(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxEdge && h <= maxEdge) return src
        val ratio = maxEdge.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}
