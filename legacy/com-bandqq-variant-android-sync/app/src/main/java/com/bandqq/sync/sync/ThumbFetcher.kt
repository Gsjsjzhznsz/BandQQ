package com.bandqq.sync.sync

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * 图片缩略图抓取：≤2MB 下载 → 采样至 96px → JPEG55 → base64（≤12KB）
 * 并发 2；4s 超时兜底；仅内存态。
 */
class ThumbFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val semaphore = Semaphore(2)
    private val executor = Executors.newFixedThreadPool(2)

    fun fetch(url: String, callback: (String?) -> Unit) {
        executor.submit {
            var call: Call? = null
            var done = false
            var result: String? = null
            semaphore.acquire()
            try {
                call = client.newCall(Request.Builder().url(url).build())
                // 4s 超时兜底
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!done) { try { call.cancel() } catch (_: Exception) {} }
                }, 4000)
                call.execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size <= 2_000_000) {
                            result = encode(bytes)
                        }
                    }
                }
                done = true
            } catch (e: Exception) {
                done = true
                Log.w("ThumbFetcher", "fetch fail: ${e.message}")
            } finally {
                semaphore.release()
                val r = result
                callback(r)
            }
        }
    }

    private fun encode(bytes: ByteArray): String? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            var w = opts.outWidth
            while (w / 2 >= 96) { sample *= 2; w /= 2 }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 96, 96 * bmp.height / bmp.width, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= 12_000) b64 else null
        } catch (_: Exception) { null }
    }
}
