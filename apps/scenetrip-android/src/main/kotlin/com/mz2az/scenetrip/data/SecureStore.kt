package com.mz2az.scenetrip.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 의 열쇠로 잠근 문자열 저장소 (MZ2AZ-335). iOS `Models/Keychain.swift` 의 짝이다.
 *
 * 설치 식별자가 먼저 쓰고, 로그인 토큰(MZ2AZ-336)이 같은 자리를 쓴다.
 *
 * **왜 SharedPreferences 에 그대로 두지 않는가** — 앱 설정은 백업·복원으로 다른 기기에
 * 따라간다. 여기서는 값을 AES-GCM 으로 잠가 따로 둔 파일에 넣고, 열쇠는 Keystore 안에만
 * 둔다(꺼낼 수 없다). 파일이 다른 기기로 옮겨 가도 열쇠가 없어 풀리지 않는다 — 그때는
 * 값이 없는 것으로 본다.
 *
 * 새 의존성(androidx.security) 없이 플랫폼 API 만 쓴다.
 */
class SecureStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 없거나 풀 수 없으면 `null`. */
    fun read(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 쓰지 못하면 `false` — 부르는 쪽이 옛 값을 지우지 않게 한다. */
    fun write(
        name: String,
        value: String,
    ): Boolean =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            // commit — 설치 식별자는 쓰자마자 옛 자리를 지우므로 디스크에 닿은 것을 확인한다.
            prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        }.getOrDefault(false)

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val FILE = "scenetrip.secure"
        const val ALIAS = "scenetrip.secure.v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
