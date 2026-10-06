package com.clinicalphotoarchive.util

import com.clinicalphotoarchive.data.*
import org.json.*
import java.util.Locale

data class PhotoRestoreSpec(val entity: PhotoEntity,val archiveFile: String)
data class BackupData(val categories: List<CategoryEntity>,val patients: List<PatientEntity>,val photos: List<PhotoRestoreSpec>)

object BackupManifest {
    const val FORMAT="clinical-photo-archive"
    fun parse(json: JSONObject): BackupData {
        require(json.optString("format")==FORMAT) { "Неверный формат резервной копии" }
        val version=json.opt("version")
        require(version is Int && version in 1..2) { "Эта версия резервной копии пока не поддерживается" }
        val categories=if (version==2) categories(json.getJSONArray("categories")) else emptyList()
        val categoryIds=categories.map { it.id }.toSet()
        val patientIds=HashSet<Long>()
        val patientArray=json.getJSONArray("patients")
        val patients=(0 until patientArray.length()).map { i ->
            val j=patientArray.getJSONObject(i)
            val id=positiveId(j,"id");require(patientIds.add(id)) { "Повторяющийся ID пациента" }
            val surname=j.getString("surname");require(surname.isNotBlank()) { "Пациент без фамилии" }
            val categoryId=if(version==1) null else {
                require(j.has("categoryId")) { "Не указан раздел пациента" }
                if(j.isNull("categoryId")) null else positiveId(j,"categoryId").also {
                    require(it in categoryIds) { "Пациент ссылается на отсутствующий раздел" }
                }
            }
            PatientEntity(id=id,surname=surname,firstName=j.optString("firstName"),middleName=j.optString("middleName"),
                chartNumber=j.optString("chartNumber"),diagnosis=j.optString("diagnosis"),note=j.optString("note"),
                createdAt=j.optLong("createdAt",System.currentTimeMillis()),updatedAt=j.optLong("updatedAt",System.currentTimeMillis()),categoryId=categoryId)
                .let { it.copy(searchKey=listOf(it.surname,it.firstName,it.middleName,it.chartNumber).joinToString(" ").trim().lowercase(Locale.ROOT)) }
        }
        val photoIds=HashSet<Long>();val paths=HashSet<String>()
        val validSections=if(version==1) setOf("before","operation","after") else PhotoSection.entries.map { it.dbValue }.toSet()
        val photoArray=json.getJSONArray("photos")
        val photos=(0 until photoArray.length()).map { i ->
            val j=photoArray.getJSONObject(i);val id=positiveId(j,"id");val patientId=positiveId(j,"patientId")
            require(photoIds.add(id)) { "Повторяющийся ID фотографии" }
            require(patientId in patientIds) { "Фотография ссылается на отсутствующего пациента" }
            val section=j.getString("section");require(section in validSections) { "Неизвестный раздел фотографии" }
            val path=j.getString("archiveFile")
            require(path.startsWith("images/") && !path.contains('\\') && path.split('/').none { it==".." || it=="." || it.isEmpty() }) { "Некорректный путь фотографии в архиве" }
            require(paths.add(path)) { "Один файл фотографии используется несколькими записями" }
            PhotoRestoreSpec(PhotoEntity(id=id,patientId=patientId,section=section,localPath="",description=j.optString("description"),
                capturedAt=j.optLong("capturedAt",System.currentTimeMillis()),addedAt=j.optLong("addedAt",System.currentTimeMillis())),path)
        }
        return BackupData(categories,patients,photos)
    }
    private fun positiveId(json: JSONObject,key: String): Long {
        val raw=json.get(key)
        require(raw is Int || raw is Long) { "Некорректный ID" }
        return (raw as Number).toLong().also { require(it>0) { "Некорректный ID" } }
    }
    private fun categories(array: JSONArray): List<CategoryEntity> {
        val ids=HashSet<Long>();val names=HashSet<String>()
        return (0 until array.length()).map { i ->
            val j=array.getJSONObject(i);val id=positiveId(j,"id")
            require(ids.add(id)) { "Повторяющийся ID раздела" }
            val name=CategoryNames.normalize(j.getString("name"))
            require(j.getString("nameKey")==name.key && names.add(name.key)) { "Повторяющееся или некорректное имя раздела" }
            val order=j.getLong("sortOrder");require(order>=0) { "Некорректный порядок разделов" }
            CategoryEntity(id,name.name,name.key,order,j.getLong("createdAt"))
        }
    }
    fun create(categories: List<CategoryEntity>,patients: List<PatientEntity>,photos: List<Pair<PhotoEntity,String>>): JSONObject = JSONObject().apply {
        put("format",FORMAT);put("version",2);put("createdAt",System.currentTimeMillis())
        put("categories",JSONArray().apply { categories.forEach { c -> put(JSONObject().put("id",c.id).put("name",c.name).put("nameKey",c.nameKey).put("sortOrder",c.sortOrder).put("createdAt",c.createdAt)) } })
        put("patients",JSONArray().apply { patients.forEach { p -> put(JSONObject().put("id",p.id).put("surname",p.surname).put("firstName",p.firstName).put("middleName",p.middleName)
            .put("chartNumber",p.chartNumber).put("diagnosis",p.diagnosis).put("note",p.note).put("searchKey",p.searchKey).put("createdAt",p.createdAt).put("updatedAt",p.updatedAt).put("categoryId",p.categoryId ?: JSONObject.NULL)) } })
        put("photos",JSONArray().apply { photos.forEach { (p,path) -> put(JSONObject().put("id",p.id).put("patientId",p.patientId).put("section",p.section).put("archiveFile",path).put("description",p.description).put("capturedAt",p.capturedAt).put("addedAt",p.addedAt)) } })
    }
}
