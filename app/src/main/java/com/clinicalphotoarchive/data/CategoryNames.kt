package com.clinicalphotoarchive.data

import java.text.Normalizer
import java.util.Locale

data class CategoryName(val name: String, val key: String)

object CategoryNames {
    fun normalize(raw: String): CategoryName {
        val name = Normalizer.normalize(raw.replace(Regex("[\\s\\p{Z}]+"), " ").trim(), Normalizer.Form.NFC)
        require(name.codePointCount(0,name.length) in 1..100) { "Название раздела должно содержать от 1 до 100 символов" }
        val key = name.lowercase(Locale.ROOT)
        require(key != "без категории") { "Название «Без категории» зарезервировано" }
        return CategoryName(name,key)
    }
}
