package com.github.avbox.osc.sync

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 上传快照的压缩 + 加密（WebDAV 上的静态数据保护，传输本身已走 HTTPS）。
 * 观看数据与设置两个文件都走这里（SyncManager.seal/open）。
 *
 * gzip 一律先做（密文不可压缩）：
 *  - 未设密码：GZIP_MAGIC + base64( gzip(json) )，只压缩不加密
 *  - 设了密码：SEAL_MAGIC + base64( salt(16) || iv(12) || AES-256-GCM(gzip(json)) )
 *  - 都不匹配（服务器上的旧明文文件）：原样返回，下次上传自动升级为新格式
 *
 * 密码错误 / 缺失时抛 IllegalArgumentException，调用方必须中止同步 ——
 * 绝不能把解不开的远端当成"空"再上传本地覆盖。
 *
 * 密码只存本机 KV，不进白名单、不同步：它就是密钥本身。
 */
object SyncCrypto {

    private const val SEAL_MAGIC = "AVBX1:"
    private const val GZIP_MAGIC = "AVBXG1:"
    private const val CIPHER = "AES/GCM/NoPadding"
    /** API 26 起才有 PBKDF2WithHmacSHA256；minSdk 24 需回退 SHA1。 */
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KDF_FALLBACK = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16

    private val random = SecureRandom()

    /** 明文 JSON -> 传输文本：始终 gzip；password 非空时再 AES-GCM 加密。 */
    fun seal(plain: String, password: String): String {
        val compressed = gzip(plain.toByteArray(Charsets.UTF_8))
        if (password.isEmpty()) return GZIP_MAGIC + compressed.toByteString().base64()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val payload = salt + iv + cipher.doFinal(compressed)
        return SEAL_MAGIC + payload.toByteString().base64()
    }

    /** 传输文本 -> 明文 JSON。旧明文文件原样返回；压缩/加密格式按魔数识别。 */
    fun open(raw: String, password: String): String = when {
        raw.startsWith(SEAL_MAGIC) -> openSealed(raw, password)
        raw.startsWith(GZIP_MAGIC) -> openGzip(raw)
        else -> raw // 服务器上的旧明文文件
    }

    private fun openSealed(raw: String, password: String): String {
        if (password.isEmpty()) throw IllegalArgumentException("sync password required")
        val payload = raw.substring(SEAL_MAGIC.length).decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("broken sync payload")
        if (payload.size <= SALT_BYTES + IV_BYTES + TAG_BYTES) {
            throw IllegalArgumentException("broken sync payload")
        }
        val salt = payload.copyOfRange(0, SALT_BYTES)
        val iv = payload.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val body = payload.copyOfRange(SALT_BYTES + IV_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            String(gunzip(cipher.doFinal(body)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // AEADBadTag = 密码错误；其余多为截断/损坏，对用户同一句话即可
            throw IllegalArgumentException("wrong sync password")
        } catch (e: IOException) {
            throw IllegalArgumentException("broken sync payload")
        }
    }

    private fun openGzip(raw: String): String {
        val bytes = raw.substring(GZIP_MAGIC.length).decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("broken sync payload")
        return try {
            String(gunzip(bytes), Charsets.UTF_8)
        } catch (e: IOException) {
            throw IllegalArgumentException("broken sync payload")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = try {
            SecretKeyFactory.getInstance(KDF)
        } catch (e: NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance(KDF_FALLBACK)
        }
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
