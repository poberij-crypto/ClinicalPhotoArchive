package com.clinicalphotoarchive.ui

import com.clinicalphotoarchive.data.PhotoSection

data class MediaCaptureTarget(val patientId: Long,val section: PhotoSection,val patientCreatedAt: Long)
