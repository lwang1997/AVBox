package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodFollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodFollow): Long

    @Query("select * from vodFollow order by addedTime desc")
    fun observeAll(): Flow<List<VodFollow>>

    @Query("select * from vodFollow where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodFollow(cid: String?, sourceKey: String?, vodId: String?): VodFollow?

    @Query("delete from vodFollow where `id` in (:ids)")
    suspend fun deleteSelected(ids: List<Int>)

    @Query("select * from vodFollow")
    suspend fun getAllRaw(): List<VodFollow>

    @Query("DELETE FROM vodFollow where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun deleteByKey(cid: String?, sourceKey: String?, vodId: String?)

    @Query("DELETE FROM vodFollow where `cid`=:cid")
    suspend fun deleteByCid(cid: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<VodFollow>)
}
