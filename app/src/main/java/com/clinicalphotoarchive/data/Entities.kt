package com.clinicalphotoarchive.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PhotoSection(val dbValue: String, val title: String) {
    BEFORE("before", "До"),
    OPERATION("operation", "Операция"),
    AFTER("after", "После")
}

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surname: String,
    val firstName: String = "",
    val middleName: String = "",
    val chartNumber: String = "",
    val diagnosis: String = "",
    val note: String = "",
    val searchKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = listOf(surname, firstName, middleName).filter { it.isNotBlank() }.joinToString(" ")
}

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = PatientEntity::class,
        parentColumns = ["id"],
        childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patientId"), Index(value = ["patientId", "section"])]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val section: String,
    val localPath: String,
    val description: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis()
)
