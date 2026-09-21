package com.clinicalphotoarchive.migrator;

import android.os.ParcelFileDescriptor;

interface IArchiveService {
    void destroy() = 16777114;
    String probe() = 1;
    String writeArchive(in ParcelFileDescriptor output) = 2;
}
