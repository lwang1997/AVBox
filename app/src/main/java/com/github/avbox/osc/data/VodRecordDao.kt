package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodRecord): Long

    @Query("select * from vodRecord where `cid`=:cid order by updateTime desc, id desc limit :size")
    suspend fun getAll(cid: String?, size: Int): List<VodRecord>

    @Query("select * from vodRecord where `cid`=:cid order by updateTime desc, id desc")
    fun observeAll(cid: String?): Flow<List<VodRecord>>

    @Query("select * from vodRecord where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodRecord(cid: String?, sourceKey: String?, vodId: String?): VodRecord?

    @Query("update vodRecord set updateTime=:updateTime where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun touch(cid: String?, sourceKey: String?, vodId: String?, updateTime: Long): Int

    @Delete
    suspend fun delete(record: VodRecord): Int

    @Query("select count(*) from vodRecord where `cid`=:cid")
    suspend fun getCount(cid: String?): Int

    @Query("DELETE FROM vodRecord where `cid`=:cid")
    suspend fun deleteAll(cid: String?)

    /** 本地备份恢复用：整表清空（无 cid 过滤） */
    @Query("DELETE FROM vodRecord")
    suspend fun clearAll()

    @Query("DELETE FROM vodRecord where `cid`=:cid and id NOT IN (SELECT id FROM vodRecord WHERE `cid`=:cid ORDER BY updateTime desc, id desc LIMIT :size)")
    suspend fun reserver(cid: String?, size: Int): Int

    @Query("select * from vodRecord")
    suspend fun getAllRaw(): List<VodRecord>

    @Query("DELETE FROM vodRecord where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun deleteByKey(cid: String?, sourceKey: String?, vodId: String?)

    @Query("DELETE FROM vodRecord where `cid`=:cid")
    suspend fun deleteByCid(cid: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<VodRecord>)
}
