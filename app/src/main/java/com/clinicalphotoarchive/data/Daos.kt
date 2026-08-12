package com.clinicalphotoarchive.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients WHERE :query = '' OR searchKey LIKE '%' || :query || '%' ORDER BY searchKey")
    fun observeAll(query: String): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<PatientEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(patient: PatientEntity): Long

    @Update
    suspend fun update(patient: PatientEntity)

    @Delete
    suspend fun delete(patient: PatientEntity)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE patientId = :patientId AND section = :section ORDER BY capturedAt ASC, id ASC")
    fun observeSection(patientId: Long, section: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE patientId = :patientId ORDER BY addedAt ASC")
    suspend fun getForPatient(patientId: Long): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Delete
    suspend fun delete(photo: PhotoEntity)
}
