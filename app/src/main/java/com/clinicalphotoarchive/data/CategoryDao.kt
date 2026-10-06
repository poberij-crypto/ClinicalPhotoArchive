package com.clinicalphotoarchive.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class CategoryWithCount(@Embedded val category: CategoryEntity, val patientCount: Int)

@Dao
interface CategoryDao {
    @Query("SELECT c.*, COUNT(p.id) AS patientCount FROM categories c LEFT JOIN patients p ON p.categoryId=c.id GROUP BY c.id ORDER BY c.sortOrder,c.id")
    fun observeWithCounts(): Flow<List<CategoryWithCount>>
    @Query("SELECT * FROM categories ORDER BY sortOrder,id")
    suspend fun getAll(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id=:id")
    suspend fun getById(id: Long): CategoryEntity?
    @Query("SELECT * FROM categories WHERE nameKey=:key LIMIT 1")
    suspend fun getByKey(key: String): CategoryEntity?
    @Query("SELECT COALESCE(MAX(sortOrder),-1) FROM categories")
    suspend fun maxSortOrder(): Long
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long
    @Query("UPDATE categories SET name=:name, nameKey=:key WHERE id=:id")
    suspend fun rename(id: Long,name: String,key: String)
    @Query("DELETE FROM categories WHERE id=:id")
    suspend fun deleteById(id: Long)
    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
