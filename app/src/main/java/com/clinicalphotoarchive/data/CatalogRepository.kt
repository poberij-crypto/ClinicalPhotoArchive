package com.clinicalphotoarchive.data

import androidx.room.withTransaction

class CatalogRepository(private val database: ClinicalDatabase) {
    private val dao = database.categoryDao()
    suspend fun createCategory(name: String): Long = database.withTransaction {
        val normalized = CategoryNames.normalize(name)
        require(dao.getByKey(normalized.key)==null) { "Раздел с таким названием уже существует" }
        val order=dao.maxSortOrder()
        require(order<Long.MAX_VALUE) { "Не удалось добавить раздел: слишком большой порядок сортировки" }
        dao.insert(CategoryEntity(name=normalized.name,nameKey=normalized.key,sortOrder=order+1))
    }
    suspend fun renameCategory(id: Long,name: String) = database.withTransaction {
        requireNotNull(dao.getById(id)) { "Раздел уже удалён" }
        val normalized = CategoryNames.normalize(name)
        require(dao.getByKey(normalized.key)?.id.let { it==null || it==id }) { "Раздел с таким названием уже существует" }
        dao.rename(id,normalized.name,normalized.key)
    }
    suspend fun deleteCategory(id: Long) = database.withTransaction {
        requireNotNull(dao.getById(id)) { "Раздел уже удалён" }
        database.patientDao().clearCategory(id,System.currentTimeMillis())
        dao.deleteById(id)
    }
    suspend fun assignPatient(patientId: Long,categoryId: Long?) = database.withTransaction {
        if (categoryId!=null) requireNotNull(dao.getById(categoryId)) { "Раздел уже удалён" }
        require(database.patientDao().assignCategory(patientId,categoryId,System.currentTimeMillis())==1) { "Пациент уже удалён" }
    }
}
