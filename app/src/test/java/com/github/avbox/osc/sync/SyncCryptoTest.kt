package com.github.avbox.osc.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncCrypto 往返 / 降级路径的纯 JVM 测试（不依赖 Android）。 */
class SyncCryptoTest {

    private val json = """{"schema":1,"data":{"history":[{"vodId":"42"}]}}"""
    private val password = "correct horse battery staple"

    @Test
    fun sealThenOpen_roundTrips() {
        val sealed = SyncCrypto.seal(json, password)
        assertTrue("应加密为带魔数的载荷", sealed.startsWith("AVBX1:"))
        assertFalse("密文不应含明文 JSON", sealed.contains("vodId"))
        assertEquals(json, SyncCrypto.open(sealed, password))
    }

    @Test
    fun emptyPassword_stillCompresses() {
        val sealed = SyncCrypto.seal(json, "")
        assertTrue("未设密码也应压缩（gzip 魔数）", sealed.startsWith("AVBXG1:"))
        assertFalse("压缩后不应含明文", sealed.contains("vodId"))
        assertEquals(json, SyncCrypto.open(sealed, ""))
    }

    @Test
    fun largePayload_actuallyShrinks() {
        val big = """{"history":[${(1..500).joinToString(",") { """{"vodId":"$it","name":"some long title here"}""" }}]}"""
        val sealed = SyncCrypto.seal(big, "")
        assertTrue("500 条历史压缩后应明显小于原文（${big.length} -> ${sealed.length}）",
            sealed.length < big.length / 2)
    }

    @Test
    fun legacyPlaintextFile_stillReadableAfterPasswordSet() {
        // 服务器上已有的旧明文文件：设了密码也要能读，下次上传才升级为密文
        assertEquals(json, SyncCrypto.open(json, password))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPassword_throws() {
        SyncCrypto.open(SyncCrypto.seal(json, password), "not the password")
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptedWithoutLocalPassword_throwsInsteadOfTreatingAsEmpty() {
        // 关键防线：解不开必须报错中止，绝不能当成"远端为空"去覆盖
        SyncCrypto.open(SyncCrypto.seal(json, password), "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedPayload_throws() {
        SyncCrypto.open("AVBX1:AAAA", password)
    }

    @Test
    fun ciphertextDiffersEachSeal_randomSaltAndIv() {
        val a = SyncCrypto.seal(json, password)
        val b = SyncCrypto.seal(json, password)
        assertTrue("相同明文两次加密应不同（随机 salt/IV）", a != b)
    }
}
