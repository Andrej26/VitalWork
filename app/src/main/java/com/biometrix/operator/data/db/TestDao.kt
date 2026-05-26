package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TestDao {

    @Query("SELECT * FROM tests ORDER BY createdAt DESC")
    fun getAllTests(): Flow<List<TestEntity>>

    @Query("SELECT * FROM tests WHERE status = :status ORDER BY createdAt DESC")
    fun getTestsByStatus(status: TestStatus): Flow<List<TestEntity>>

    @Query("SELECT * FROM tests WHERE id = :id")
    suspend fun getTestById(id: Long): TestEntity?

    @Query("SELECT * FROM tests WHERE status = 'ACTIVE' LIMIT 1")
    fun getActiveTest(): Flow<TestEntity?>

    @Insert
    suspend fun insert(test: TestEntity): Long

    @Update
    suspend fun update(test: TestEntity)

    @Delete
    suspend fun delete(test: TestEntity)

}
