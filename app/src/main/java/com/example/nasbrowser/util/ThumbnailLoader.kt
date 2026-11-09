package com.example.nasbrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Tiny in-memory thumbnail cache + JPEG/PNG decoder. No third-party image library.
 *
 * v4.1 fix for "大图标视图图片预览不全": the SMB layer now reads the *whole* image (see
 * SmbQueryOps.readHead), and local files are decoded from their path with inSampleSize instead of
 * being loaded as a truncated byte-array prefix. Bitmaps are kept at ~320 px so a large-icon
 * tile can be center-cropped without losing detail.
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        // values are measured in KB
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, b: Bitmap) {
        try { cache.put(key, b) } catch (_: Exception) { }
    }

    fun clear() = cache.evictAll()

    /** Decode a complete image byte array into a bitmap roughly [req] px wide. */
    fun decode(bytes: ByteArray, req: Int = 320): Bitmap? {
        if (bytes.size < 12) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            var w = bounds.outWidth
            while (w / 2 >= req && sample < 32) { w /= 2; sample *= 2 }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Decode a LOCAL image from its path. Reading through BitmapFactory (rather than the first
     * 384 KB of a FileInputStream) is what makes a large-icon tile show the whole picture.
     */
    fun decodeFile(path: String, req: Int = 320): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            var w = bounds.outWidth
            while (w / 2 >= req && sample < 32) { w /= 2; sample *= 2 }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
