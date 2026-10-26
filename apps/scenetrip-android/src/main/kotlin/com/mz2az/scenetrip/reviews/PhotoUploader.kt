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
import com.mz2az.scenetrip.data.NetworkFailure
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

    /**
     * 줄여 둔 JPEG 를 올리고 `key` 를 돌려준다. 실패는 [PhotoUploadException] 으로 이유를 달고 올라간다 — 칸이 그 이유로 문구와
     * 다시 시도 가능 여부를 가른다 (iOS `PhotoUploader.upload` 의 `PhotoUploadFailure`).
     */
    fun upload(
        bytes: ByteArray,
        purpose: UploadPurpose = UploadPurpose.review,
    ): String {
        val ticket =
            try {
                UploadsApi(API_BASE).createUpload(UploadCreate(purpose = purpose, contentType = "image/jpeg", bytes = bytes.size.toLong()))
            } catch (error: java.io.IOException) {
                throw PhotoUploadException(PhotoUploadFailure.NETWORK, error)
            } catch (error: RuntimeException) {
                val failure = NetworkFailure.of(error)
                throw PhotoUploadException(PhotoUploadRules.failure(failure.status, failure.code), error)
            }
        val request = Request.Builder().url(ticket.uploadUrl.toString()).put(bytes.toRequestBody("image/jpeg".toMediaType()))
        ticket.requiredHeaders.forEach { (name, value) ->
            // 저장소는 우리 서버가 아니다 — 인증 토큰을 보내지 않는다.
            if (name.equals("Authorization", true)) throw PhotoUploadException(PhotoUploadFailure.STORAGE)
            request.header(name, value)
        }
        try {
            storage.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw PhotoUploadException(PhotoUploadFailure.STORAGE)
            }
        } catch (error: java.io.IOException) {
            throw PhotoUploadException(PhotoUploadFailure.NETWORK, error)
        }
        return ticket.key
    }

    /** 올릴 JPEG 를 고른 파일에서 만든다. 그림이 아니면 UNREADABLE, 품질을 낮춰도 10 MB 를 넘으면 TOO_LARGE. */
    fun shrink(
        context: Context,
        uri: Uri,
    ): ByteArray =
        try {
            shrinkUnchecked(context, uri)
        } catch (error: PhotoUploadException) {
            throw error
        } catch (error: Exception) {
            throw PhotoUploadException(PhotoUploadFailure.UNREADABLE, error)
        }

    private fun shrinkUnchecked(
        context: Context,
        uri: Uri,
    ): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use {
            requireNotNull(it)
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoUploadException(PhotoUploadFailure.UNREADABLE)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= EDGE) sample *= 2
        val decoded =
            resolver
                .openInputStream(
                    uri,
                ).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw PhotoUploadException(PhotoUploadFailure.UNREADABLE)
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
            throw PhotoUploadException(PhotoUploadFailure.TOO_LARGE)
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
