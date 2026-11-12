package com.pteal79.plugins.imageresize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image resizing. Namespace: "ImageResize.*" */
object ImageResizeFunctions {
    private const val TAG = "Pteal79ImageResize"
    private const val DEFAULT_MAX_EDGE = 1980
    private const val DEFAULT_QUALITY = 80

    /**
     * Scales an image down to a maximum long edge and writes it as a JPEG.
     * Parameters:
     *   - path: string - absolute path (or file:// URL) of a JPEG or PNG; HEIC where the device decodes it, Android 9+ (required)
     *   - maxEdge: int - the longest edge allowed, in pixels (optional, default 1980)
     *   - destination: string - where to write the JPEG (optional, default `{name}-resized.jpg` beside the source)
     *   - quality: int - JPEG quality 1–100 (optional, default 80)
     *
     * Never scales up. Orientation is baked into the pixels, metadata is
     * dropped, and transparency is flattened onto white.
     */
    class Resize(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val rawPath = (parameters["path"] as? String)?.takeIf { it.isNotBlank() }
                ?: return BridgeResponse.error("missing_path", "The path of the image to resize is required.")

            val source = File(filePath(rawPath))

            if (!source.isFile) {
                return BridgeResponse.error("not_found", "No image at ${source.path}.")
            }

            val maxEdge = ((parameters["maxEdge"] as? Number)?.toInt() ?: DEFAULT_MAX_EDGE).coerceAtLeast(1)
            val quality = ((parameters["quality"] as? Number)?.toInt() ?: DEFAULT_QUALITY).coerceIn(1, 100)
            val destination = (parameters["destination"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { File(filePath(it)) }
                ?: defaultDestination(source)

            return try {
                resize(source, destination, maxEdge, quality)
            } catch (e: Throwable) {
                // Throwable, not Exception: a photo too big to decode is an
                // OutOfMemoryError, and that must come back as an error too.
                Log.e(TAG, "Could not resize ${source.path}", e)
                BridgeResponse.error("resize_failed", e.message ?: "The image could not be resized.")
            }
        }

        private fun resize(source: File, destination: File, maxEdge: Int, quality: Int): Map<String, Any> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return BridgeResponse.error("unreadable", "The file is not an image this device can read.")
            }

            val orientation = readOrientation(source)
            val onItsSide = orientation in SIDEWAYS_ORIENTATIONS
            val originalWidth = if (onItsSide) bounds.outHeight else bounds.outWidth
            val originalHeight = if (onItsSide) bounds.outWidth else bounds.outHeight
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            val targetEdge = min(maxEdge, longEdge)

            // Decode at the biggest power-of-two reduction that still leaves
            // the long edge at or above the target, so a 48MP photo never sits
            // in memory at full size; the matrix below takes it the rest of the way.
            var sampleSize = 1
            while (longEdge / (sampleSize * 2) >= targetEdge) {
                sampleSize *= 2
            }

            val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return BridgeResponse.error("unreadable", "The image could not be decoded.")

            val scale = targetEdge.toFloat() / max(decoded.width, decoded.height)
            val matrix = Matrix().apply {
                if (scale < 1f) {
                    postScale(scale, scale)
                }
                applyOrientation(this, orientation)
            }

            val transformed = if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) {
                        decoded.recycle()
                    }
                }
            }

            val output = flattenedOntoWhite(transformed)
            val outputWidth = output.width
            val outputHeight = output.height

            try {
                writeJpeg(output, destination, quality)
            } catch (e: Exception) {
                Log.e(TAG, "Could not write ${destination.path}", e)
                return BridgeResponse.error("write_failed", e.message ?: "Could not write a JPEG to ${destination.path}.")
            } finally {
                output.recycle()
            }

            return BridgeResponse.success(mapOf(
                "path" to destination.path,
                "width" to outputWidth,
                "height" to outputHeight,
                "originalWidth" to originalWidth,
                "originalHeight" to originalHeight,
                "bytes" to destination.length(),
                "resized" to (longEdge > maxEdge),
            ))
        }
    }

    private val SIDEWAYS_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

    /** Accepts a plain path or a file:// URL. */
    private fun filePath(raw: String): String =
        if (raw.startsWith("file://")) Uri.parse(raw).path ?: raw else raw

    /** `{name}-resized.jpg`, beside the source. */
    private fun defaultDestination(source: File): File =
        File(source.parentFile, "${source.nameWithoutExtension}-resized.jpg")

    private fun readOrientation(source: File): Int =
        try {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            // PNGs and files with broken EXIF simply have no orientation to apply.
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun applyOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        }
    }

    /**
     * JPEG has no alpha channel, and the encoder turns transparent areas
     * black. Opaque images are passed through untouched.
     */
    private fun flattenedOntoWhite(image: Bitmap): Bitmap {
        if (!image.hasAlpha()) {
            return image
        }

        val flattened = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, 0f, 0f, null)
        }
        image.recycle()

        return flattened
    }

    /**
     * Writes to a temporary file first and swaps it in, so a destination
     * that is the source itself is never half-written.
     */
    private fun writeJpeg(image: Bitmap, destination: File, quality: Int) {
        val directory = destination.absoluteFile.parentFile
            ?: throw IllegalArgumentException("No directory for ${destination.path}.")

        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Could not create ${directory.path}.")
        }

        val temporary = File(directory, ".${UUID.randomUUID()}.jpg")

        try {
            FileOutputStream(temporary).use { stream ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    throw IllegalStateException("The JPEG encoder refused the image.")
                }
            }

            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not replace ${destination.path}.")
            }

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("Could not move the JPEG into ${destination.path}.")
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }
}
