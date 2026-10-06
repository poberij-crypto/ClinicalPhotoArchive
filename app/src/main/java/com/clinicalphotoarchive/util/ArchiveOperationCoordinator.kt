package com.clinicalphotoarchive.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned lock protects both database metadata and its files. */
class ArchiveOperationCoordinator(private val initialize: suspend () -> Unit = {}) {
    private val lock = Mutex()
    private var initialized = false
    private val busy = MutableStateFlow(false)
    val archiveBusy = busy.asStateFlow()
    suspend fun <T> withMutation(block: suspend () -> T): T = lock.withLock {
        ensureReady()
        block()
    }
    suspend fun <T> withArchive(block: suspend () -> T): T = lock.withLock {
        busy.value = true
        try { ensureReady(); block() } finally { busy.value = false }
    }
    private suspend fun ensureReady() {
        if (!initialized) { initialize(); initialized = true }
    }
}
