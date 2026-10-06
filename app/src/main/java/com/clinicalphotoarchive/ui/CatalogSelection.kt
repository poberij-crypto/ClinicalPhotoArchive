package com.clinicalphotoarchive.ui

sealed interface CatalogSelection {
    data object Root: CatalogSelection
    data object Uncategorized: CatalogSelection
    data class Category(val id: Long): CatalogSelection
}
