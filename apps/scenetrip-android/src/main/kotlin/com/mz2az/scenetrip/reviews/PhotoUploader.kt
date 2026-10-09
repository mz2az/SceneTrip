package com.mz2az.scenetrip.reviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.sceneapi.client.api.UploadsApi
import com.mz2az.scenetrip.sceneapi.client.model.UploadCreate
import com.mz2az.scenetrip.sceneapi.client.model.UploadPurpose
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 새 비트맵에 다시 그려 EXIF(위치·기기)를 제거한 JPEG만 올린다. 저장소에는 인증 토큰을 보내지 않는다. */
object PhotoUploader {
    const val EDGE = 2048
    const val MAX_BYTES = 10 * 1024 * 1024
    private val storage =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

    fun upload(
        context: Context,
        uri: Uri,
    ): String {
        val bytes = shrink(context, uri)
        val ticket =
            UploadsApi(
                API_BASE,
            ).createUpload(UploadCreate(purpose = UploadPurpose.review, contentType = "image/jpeg", bytes = bytes.size.toLong()))
        val request = Request.Builder().url(ticket.uploadUrl.toString()).put(bytes.toRequestBody("image/jpeg".toMediaType()))
        ticket.requiredHeaders.forEach { (name, value) ->
            require(!name.equals("Authorization", true))
            request.header(name, value)
        }
        storage.newCall(request.build()).execute().use { response -> check(response.isSuccessful) { "사진 저장소에 연결하지 못했어요" } }
        return ticket.key
    }

    fun shrink(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use {
            requireNotNull(it)
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "사진을 읽을 수 없어요" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= EDGE) sample *= 2
        val decoded =
            resolver
                .openInputStream(
                    uri,
                ).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("사진을 읽을 수 없어요")
        val orientation =
            resolver.openInputStream(uri).use { input ->
                input?.let { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) } ?: 1
            }
        val matrix = orientationMatrix(orientation)
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (turned !== decoded) decoded.recycle()
        val scale = minOf(1.0, EDGE.toDouble() / maxOf(turned.width, turned.height))
        val scaled =
            Bitmap.createScaledBitmap(
                turned,
                maxOf(1, (turned.width * scale).toInt()),
                maxOf(1, (turned.height * scale).toInt()),
                true,
            )
        if (scaled !== turned) turned.recycle()
        val clean = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(clean).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        scaled.recycle()
        try {
            for (quality in listOf(82, 60, 40)) {
                val out = ByteArrayOutputStream()
                check(clean.compress(Bitmap.CompressFormat.JPEG, quality, out))
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_BYTES) return bytes
            }
            error("사진이 너무 커요")
        } finally {
            clean.recycle()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix =
        Matrix().apply {
            when (orientation) {
                2 -> {
                    setScale(-1f, 1f)
                }

                3 -> {
                    setRotate(180f)
                }

                4 -> {
                    setScale(1f, -1f)
                }

                5 -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }

                6 -> {
                    setRotate(90f)
                }

                7 -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }

                8 -> {
                    setRotate(-90f)
                }
            }
        }
}
