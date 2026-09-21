# ClinicalPhotoArchive Windows Migrator

Portable Windows utility for extracting the database and clinical images from
the old debug build of com.clinicalphotoarchive.

User does not need to install ADB separately. The release package contains the
official Android SDK Platform-Tools binaries for Windows.

Phone setup:
1. Enable Developer options.
2. Enable USB debugging.
3. Connect the phone by USB.
4. Unlock it and approve the RSA prompt for this computer.
5. Run ClinicalPhotoArchive-Migrator.exe.
6. Refresh devices, check data, then create the archive.

The utility does not delete or modify the old database or images. It performs
am force-stop before copying, streams databases/ and files/clinical_images/
through adb exec-out to a temporary .partial TAR, validates the TAR and the
presence of clinical_photo_archive.db, and only then renames it to the final
archive.

The old app must be debuggable so Android run-as can access its private data.

Clinical data are copied only from the phone into the local file selected by
the user. The utility does not upload the archive to GitHub or cloud services.

The Windows executable is not currently Authenticode-signed, so Microsoft
SmartScreen may display a warning on first launch.
