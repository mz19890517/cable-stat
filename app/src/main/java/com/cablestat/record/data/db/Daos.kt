package com.cablestat.record.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): LiveData<ProjectEntity?>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun listAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM records WHERE projectId = :id")
    suspend fun deleteRecordsByProject(id: String)
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM records WHERE projectId = :projectId")
    fun observeByProject(projectId: String): LiveData<List<RecordEntity>>

    @Query("SELECT * FROM records")
    fun observeAllLive(): LiveData<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE projectId = :projectId AND kind = :kind AND spec = :spec AND color = :color ORDER BY createdAt DESC")
    suspend fun listCombo(projectId: String, kind: String, spec: String, color: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun allByProject(projectId: String): List<RecordEntity>

    @Query("SELECT * FROM records ORDER BY createdAt DESC")
    suspend fun all(): List<RecordEntity>
}

@Dao
interface SpecDao {
    @Query("SELECT * FROM specs WHERE kind = :kind ORDER BY sortKey, label")
    fun observeByKind(kind: String): LiveData<List<SpecEntity>>

    @Query("SELECT * FROM specs WHERE kind = :kind ORDER BY sortKey, label")
    suspend fun listByKind(kind: String): List<SpecEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SpecEntity)

    @Query("DELETE FROM specs WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ColorDao {
    @Query("SELECT * FROM colors ORDER BY sortKey, label")
    fun observeAll(): LiveData<List<ColorEntity>>

    @Query("SELECT * FROM colors ORDER BY sortKey, label")
    suspend fun listAll(): List<ColorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: ColorEntity)

    @Query("DELETE FROM colors WHERE id = :id")
    suspend fun deleteById(id: String)
}