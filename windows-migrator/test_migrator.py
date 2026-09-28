import io
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import clinical_photo_archive_migrator as app


def archive_bytes(images=True):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w') as tar:
        files = {'databases/' + app.DATABASE_NAME: b'x' * 36864,
                 'databases/' + app.DATABASE_NAME + '-wal': b'wal',
                 'databases/' + app.DATABASE_NAME + '-shm': b'shm'}
        if images:
            files['files/clinical_images/photo with space.jpg'] = b'photo'
        for name, data in files.items():
            item = tarfile.TarInfo(name)
            item.size = len(data)
            tar.addfile(item, io.BytesIO(data))
    return stream.getvalue()


class MigratorTests(unittest.TestCase):
    def fake_adb(self, args, serial=None, timeout=20):
        self.assertEqual(serial, 'test-device')
        self.assertNotIn('sh', args)
        self.assertNotIn('-c', args) if 'stat' not in args else None
        if args[:3] == ['shell', 'pm', 'path']:
            output = 'package:/data/app/base.apk'
        elif args[:3] == ['shell', 'am', 'force-stop']:
            output = ''
        else:
            self.assertEqual(args[:4], ['shell', 'run-as', app.TARGET_PACKAGE, 'toybox'])
            command = args[4:]
            if command[0] == 'stat':
                output = '36864\n'
            elif command == ['ls', '-a', '.']:
                output = 'databases\nfiles\n' if self.files else 'databases\n'
            elif command == ['ls', '-a', 'files']:
                output = 'clinical_images\n' if self.images else ''
            elif command == ['test', '-d', 'files/clinical_images']:
                output = ''
            elif command[0] == 'find':
                output = ''.join('files/clinical_images/photo %s\0' % i for i in range(16))
            else:
                self.fail('Unexpected command: ' + repr(args))
        return subprocess.CompletedProcess(args, 0, output, '')

    def setUp(self):
        self.files = True
        self.images = True

    def test_reported_numbers_are_parsed_locally(self):
        with patch.object(app, 'run_adb', side_effect=self.fake_adb):
            self.assertEqual(app.probe_device('test-device'), app.ProbeResult(16, 36864))

    def test_missing_optional_directories(self):
        for self.files, self.images in [(False, False), (True, False)]:
            with self.subTest(files=self.files), patch.object(app, 'run_adb', side_effect=self.fake_adb):
                self.assertEqual(app.probe_device('test-device').photo_count, 0)

    def test_access_errors_are_not_zero_photos(self):
        for error in ['package not debuggable', 'Permission denied', 'device offline', 'No such file']:
            with self.subTest(error=error), patch.object(app, 'run_adb', return_value=subprocess.CompletedProcess([], 1, '', error)):
                with self.assertRaises(RuntimeError):
                    app.has_image_directory('test-device')

    def test_invalid_database_size(self):
        for value in ['0', '-1', 'garbage']:
            with self.subTest(value=value), patch.object(app, 'run_adb', side_effect=self.fake_adb), patch.object(app, 'run_private', return_value=subprocess.CompletedProcess([], 0, value, '')):
                with self.assertRaises(RuntimeError):
                    app.probe_device('test-device')

    def test_archive_stream_and_failure_preserve_destination(self):
        for images, failure in [(True, None), (False, None), (True, 'exit'), (True, 'invalid'), (True, 'timeout')]:
            self.images = images
            with self.subTest(images=images, failure=failure), tempfile.TemporaryDirectory() as folder:
                destination = Path(folder) / 'backup.tar'
                destination.write_bytes(b'previous backup')
                def popen(cmd, stdout, **kwargs):
                    self.assertEqual(cmd, ['adb.exe', '-s', 'test-device', 'exec-out', 'run-as', app.TARGET_PACKAGE, 'toybox', 'tar', '-cf', '-', 'databases'] + (['files/clinical_images'] if images else []))
                    stdout.write(b'bad archive' * 200 if failure == 'invalid' else archive_bytes(images))
                    class Process:
                        returncode = 1 if failure == 'exit' else 0
                        killed = False
                        def communicate(self, timeout=None):
                            if failure == 'timeout' and not self.killed:
                                raise subprocess.TimeoutExpired(cmd, timeout)
                            return None, b'copy failed' if failure == 'exit' else b''
                        def kill(self):
                            self.killed = True
                    return Process()
                with patch.object(app, 'run_adb', side_effect=self.fake_adb), patch.object(app, 'adb_path', return_value=Path('adb.exe')), patch.object(app.subprocess, 'Popen', side_effect=popen):
                    if failure:
                        with self.assertRaises(RuntimeError):
                            app.create_archive('test-device', destination)
                        self.assertEqual(destination.read_bytes(), b'previous backup')
                    else:
                        checked = app.create_archive('test-device', destination)
                        self.assertEqual(checked, app.ArchiveCheck(int(images), 36864, 3 + int(images)))
                self.assertFalse(destination.with_name('backup.tar.partial').exists())


if __name__ == '__main__':
    unittest.main()
