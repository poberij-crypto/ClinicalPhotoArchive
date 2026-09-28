from __future__ import annotations

import os
import subprocess
import sys
import tarfile
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

APP_TITLE = "ClinicalPhotoArchive Migrator"
VERSION = "1.0.1"
TARGET_PACKAGE = "com.clinicalphotoarchive"
DATABASE_NAME = "clinical_photo_archive.db"

@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    details: str


@dataclass(frozen=True)
class ProbeResult:
    photo_count: int
    database_bytes: int


@dataclass(frozen=True)
class ArchiveCheck:
    photo_count: int
    database_bytes: int
    file_count: int


def app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def resource_dir() -> Path:
    bundle = getattr(sys, "_MEIPASS", None)
    return Path(bundle) if bundle else app_dir()


def adb_path() -> Path:
    bundled = resource_dir() / "platform-tools" / "adb.exe"
    if bundled.exists():
        return bundled
    adjacent = app_dir() / "platform-tools" / "adb.exe"
    if adjacent.exists():
        return adjacent
    raise FileNotFoundError(
        "adb.exe не найден. Переустановите или заново распакуйте Windows Migrator."
    )


def creation_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0)


def run_adb(args: list[str], serial: str | None = None, timeout: int = 20) -> subprocess.CompletedProcess[str]:
    cmd = [str(adb_path())]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(
        cmd,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        creationflags=creation_flags(),
        check=False,
    )


def list_devices() -> list[Device]:
    result = run_adb(["devices", "-l"], timeout=15)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "ADB не отвечает.")
    devices: list[Device] = []
    for raw in result.stdout.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append(Device(parts[0], parts[1], " ".join(parts[2:])))
    return devices


def probe_device(serial: str) -> ProbeResult:
    package = run_adb(["shell", "pm", "path", TARGET_PACKAGE], serial=serial)
    if package.returncode != 0 or "package:" not in package.stdout:
        raise RuntimeError("Старая версия ClinicalPhotoArchive на устройстве не найдена.")

    result = run_private(serial, ["stat", "-c", "%s", f"databases/{DATABASE_NAME}"])
    try:
        db_bytes = int(result.stdout.strip())
    except ValueError as exc:
        raise RuntimeError("Не удалось прочитать размер базы.") from exc
    if db_bytes <= 0:
        raise RuntimeError("Файл базы найден, но имеет нулевой размер.")
    photos = 0
    if has_image_directory(serial):
        result = run_private(serial, ["find", "files/clinical_images", "-type", "f", "-print0"])
        photos = result.stdout.count("\0")
    return ProbeResult(photo_count=photos, database_bytes=db_bytes)


def run_private(serial: str, args: list[str]) -> subprocess.CompletedProcess[str]:
    result = run_adb(["shell", "run-as", TARGET_PACKAGE, "toybox", *args], serial=serial, timeout=25)
    if result.returncode != 0:
        output = (result.stdout + "\n" + result.stderr).strip()
        if "not debuggable" in output.lower():
            raise RuntimeError("Установленная версия не допускает run-as. Этот Migrator предназначен для старых debug-сборок.")
        raise RuntimeError("Не удалось получить доступ к данным старой версии: " + (output[:500] or f"код {result.returncode}"))
    return result


def has_image_directory(serial: str) -> bool:
    # Inspect parents so a missing optional directory is distinct from an ADB/access error.
    root = run_private(serial, ["ls", "-a", "."]).stdout.splitlines()
    if "files" not in root:
        return False
    entries = run_private(serial, ["ls", "-a", "files"]).stdout.splitlines()
    if "clinical_images" not in entries:
        return False
    run_private(serial, ["test", "-d", "files/clinical_images"])
    return True


def create_archive(serial: str, destination: Path) -> ArchiveCheck:
    probe_device(serial)
    stopped = run_adb(["shell", "am", "force-stop", TARGET_PACKAGE], serial=serial, timeout=15)
    if stopped.returncode != 0:
        raise RuntimeError(
            "Не удалось остановить старое приложение перед копированием: "
            + (stopped.stderr.strip() or stopped.stdout.strip())
        )

    include_images = has_image_directory(serial)

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".partial")
    if partial.exists():
        partial.unlink()

    cmd = [
        str(adb_path()), "-s", serial, "exec-out",
        "run-as", TARGET_PACKAGE, "toybox", "tar", "-cf", "-", "databases",
    ]
    if include_images:
        cmd.append("files/clinical_images")
    try:
        with partial.open("wb") as output:
            process = subprocess.Popen(
                cmd,
                stdout=output,
                stderr=subprocess.PIPE,
                creationflags=creation_flags(),
            )
            _, stderr = process.communicate(timeout=300)
    except subprocess.TimeoutExpired as exc:
        process.kill()
        process.communicate()
        if partial.exists():
            partial.unlink()
        raise RuntimeError("Создание архива превысило допустимое время.") from exc
    except Exception:
        if partial.exists():
            partial.unlink()
        raise

    if process.returncode != 0:
        if partial.exists():
            partial.unlink()
        error = (stderr or b"").decode("utf-8", errors="replace").strip()
        raise RuntimeError(
            "ADB не смог создать архив: " + (error[:500] if error else f"код {process.returncode}")
        )
    if not partial.exists() or partial.stat().st_size < 1024:
        if partial.exists():
            partial.unlink()
        raise RuntimeError("Полученный архив слишком мал или пуст.")

    try:
        checked = verify_archive(partial)
        os.replace(partial, destination)
        return checked
    finally:
        if partial.exists():
            partial.unlink()


def verify_archive(path: Path) -> ArchiveCheck:
    database_suffix = f"databases/{DATABASE_NAME}"
    try:
        with tarfile.open(path, mode="r:*") as archive:
            members = archive.getmembers()
    except (tarfile.TarError, OSError) as exc:
        raise RuntimeError("Созданный TAR-архив повреждён и не прошёл проверку.") from exc

    db_members = [
        m for m in members
        if m.isfile() and m.name.rstrip("/").endswith(database_suffix)
    ]
    if not db_members:
        raise RuntimeError("Проверка архива не пройдена: файл clinical_photo_archive.db не найден.")
    db_bytes = max(m.size for m in db_members)
    if db_bytes <= 0:
        raise RuntimeError("Проверка архива не пройдена: база имеет нулевой размер.")

    photos = [
        m for m in members
        if m.isfile() and "/clinical_images/" in ("/" + m.name)
    ]
    files = [m for m in members if m.isfile()]
    return ArchiveCheck(len(photos), db_bytes, len(files))


def format_bytes(value: int) -> str:
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.1f} МБ"
    return f"{value / 1024:.1f} КБ"


class MigratorApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(f"{APP_TITLE} {VERSION}")
        self.geometry("760x610")
        self.minsize(700, 560)
        self.devices: dict[str, Device] = {}
        self.current_probe: ProbeResult | None = None
        self.device_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Подключите телефон и нажмите «Обновить устройства».")
        self.probe_var = tk.StringVar(value="Данные старой версии ещё не проверены.")
        self.progress_var = tk.StringVar(value="")
        self._build_ui()
        self.after(250, self.refresh_devices)

    def _build_ui(self) -> None:
        main = ttk.Frame(self, padding=18)
        main.pack(fill=tk.BOTH, expand=True)
        ttk.Label(main, text="ClinicalPhotoArchive Migrator", font=("Segoe UI", 18, "bold")).pack(anchor=tk.W)
        ttk.Label(
            main,
            text="Безопасно извлекает базу и фотографии из старой debug-версии. Исходные данные на телефоне не удаляются.",
            wraplength=700,
        ).pack(anchor=tk.W, pady=(4, 16))

        setup = ttk.LabelFrame(main, text="1. Подключение устройства", padding=12)
        setup.pack(fill=tk.X)
        ttk.Label(
            setup,
            text="На телефоне включите «Для разработчиков» → «Отладка по USB», подключите кабель и подтвердите RSA-разрешение для этого компьютера.",
            wraplength=680,
        ).pack(anchor=tk.W)
        row = ttk.Frame(setup)
        row.pack(fill=tk.X, pady=(10, 0))
        self.device_combo = ttk.Combobox(row, textvariable=self.device_var, state="readonly", width=55)
        self.device_combo.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.device_combo.bind("<<ComboboxSelected>>", lambda _event: self._device_changed())
        ttk.Button(row, text="Обновить устройства", command=self.refresh_devices).pack(side=tk.LEFT, padx=(8, 0))
        ttk.Label(setup, textvariable=self.status_var, wraplength=680).pack(anchor=tk.W, pady=(10, 0))

        check = ttk.LabelFrame(main, text="2. Проверка старого фотоархива", padding=12)
        check.pack(fill=tk.X, pady=(14, 0))
        self.check_button = ttk.Button(check, text="Проверить данные", command=self.check_archive, state=tk.DISABLED)
        self.check_button.pack(anchor=tk.W)
        ttk.Label(check, textvariable=self.probe_var, wraplength=680).pack(anchor=tk.W, pady=(10, 0))

        export = ttk.LabelFrame(main, text="3. Создание резервного архива", padding=12)
        export.pack(fill=tk.X, pady=(14, 0))
        ttk.Label(
            export,
            text="Перед копированием старое приложение будет только принудительно остановлено. В TAR войдут databases/ и files/clinical_images/. Полученный файл автоматически проверяется.",
            wraplength=680,
        ).pack(anchor=tk.W)
        self.export_button = ttk.Button(export, text="Создать архив…", command=self.choose_destination, state=tk.DISABLED)
        self.export_button.pack(anchor=tk.W, pady=(10, 0))
        self.progress = ttk.Progressbar(export, mode="indeterminate")
        self.progress.pack(fill=tk.X, pady=(12, 0))
        ttk.Label(export, textvariable=self.progress_var).pack(anchor=tk.W, pady=(4, 0))

        info = ttk.LabelFrame(main, text="Важно", padding=12)
        info.pack(fill=tk.BOTH, expand=True, pady=(14, 0))
        ttk.Label(
            info,
            text="Не удаляйте старую debug-версию ClinicalPhotoArchive, пока архив не импортирован в release-версию и карточки/фотографии не проверены. Утилита работает локально и не отправляет клинические данные в интернет.",
            wraplength=680,
        ).pack(anchor=tk.W)

    def set_busy(self, busy: bool, text: str = "") -> None:
        self.progress_var.set(text)
        if busy:
            self.progress.start(12)
            self.check_button.configure(state=tk.DISABLED)
            self.export_button.configure(state=tk.DISABLED)
            self.device_combo.configure(state=tk.DISABLED)
        else:
            self.progress.stop()
            self.device_combo.configure(state="readonly")
            selected = self.selected_device()
            self.check_button.configure(state=tk.NORMAL if selected and selected.state == "device" else tk.DISABLED)
            self.export_button.configure(state=tk.NORMAL if self.current_probe is not None else tk.DISABLED)

    def selected_device(self) -> Device | None:
        value = self.device_var.get()
        if not value:
            return None
        serial = value.split("  —  ", 1)[0]
        return self.devices.get(serial)

    def _device_changed(self) -> None:
        self.current_probe = None
        self.probe_var.set("Данные старой версии ещё не проверены.")
        self.export_button.configure(state=tk.DISABLED)
        device = self.selected_device()
        self.check_button.configure(state=tk.NORMAL if device and device.state == "device" else tk.DISABLED)
        if device:
            self._show_device_status(device)

    def refresh_devices(self) -> None:
        self.set_busy(True, "Поиск устройств через ADB…")
        def work() -> None:
            try:
                devices = list_devices()
                self.after(0, lambda: self._apply_devices(devices))
            except Exception as exc:
                self.after(0, lambda error=str(exc): self._show_error(error))
        threading.Thread(target=work, daemon=True).start()

    def _apply_devices(self, devices: list[Device]) -> None:
        self.devices = {d.serial: d for d in devices}
        self.device_combo["values"] = [f"{d.serial}  —  {d.state}" for d in devices]
        ready = [d for d in devices if d.state == "device"]
        unauthorized = [d for d in devices if d.state == "unauthorized"]
        if len(ready) == 1:
            self.device_var.set(f"{ready[0].serial}  —  {ready[0].state}")
            self._show_device_status(ready[0])
        elif len(ready) > 1:
            self.device_var.set("")
            self.status_var.set("Найдено несколько устройств. Выберите нужное в списке.")
        elif unauthorized:
            self.device_var.set(f"{unauthorized[0].serial}  —  {unauthorized[0].state}")
            self.status_var.set("Телефон найден, но не авторизован. Разблокируйте его и подтвердите «Разрешить отладку по USB».")
        elif devices:
            first = devices[0]
            self.device_var.set(f"{first.serial}  —  {first.state}")
            self._show_device_status(first)
        else:
            self.device_var.set("")
            self.status_var.set("Устройства не найдены. Проверьте кабель, USB-отладку и подтверждение RSA.")
        self.current_probe = None
        self.probe_var.set("Данные старой версии ещё не проверены.")
        self.set_busy(False)

    def _show_device_status(self, device: Device) -> None:
        if device.state == "device":
            self.status_var.set(f"Устройство готово: {device.serial}")
        elif device.state == "unauthorized":
            self.status_var.set("Устройство ожидает подтверждения RSA. Разблокируйте телефон и разрешите отладку.")
        elif device.state == "offline":
            self.status_var.set("ADB видит устройство как offline. Переподключите кабель.")
        else:
            self.status_var.set(f"Состояние устройства: {device.state}")

    def check_archive(self) -> None:
        device = self.selected_device()
        if not device or device.state != "device":
            messagebox.showwarning(APP_TITLE, "Сначала выберите авторизованное устройство.")
            return
        self.current_probe = None
        self.set_busy(True, "Проверка старой debug-версии…")
        def work() -> None:
            try:
                result = probe_device(device.serial)
                self.after(0, lambda: self._probe_ok(result))
            except Exception as exc:
                self.after(0, lambda error=str(exc): self._show_error(error))
        threading.Thread(target=work, daemon=True).start()

    def _probe_ok(self, result: ProbeResult) -> None:
        self.current_probe = result
        self.probe_var.set(f"Доступ подтверждён. Фотографий: {result.photo_count}. Размер основной базы: {format_bytes(result.database_bytes)}.")
        self.set_busy(False)

    def choose_destination(self) -> None:
        device = self.selected_device()
        if not device or not self.current_probe:
            messagebox.showwarning(APP_TITLE, "Сначала выполните проверку данных.")
            return
        stamp = datetime.now().strftime("%Y%m%d_%H%M")
        selected = filedialog.asksaveasfilename(
            title="Сохранить архив ClinicalPhotoArchive",
            defaultextension=".tar",
            initialfile=f"ClinicalPhotoArchive_legacy_{stamp}.tar",
            filetypes=[("TAR archive", "*.tar"), ("All files", "*.*")],
        )
        if not selected:
            return
        destination = Path(selected)
        self.set_busy(True, "Копирование базы и фотографий. Не отключайте телефон…")
        def work() -> None:
            try:
                checked = create_archive(device.serial, destination)
                self.after(0, lambda: self._archive_ok(destination, checked))
            except Exception as exc:
                self.after(0, lambda error=str(exc): self._show_error(error))
        threading.Thread(target=work, daemon=True).start()

    def _archive_ok(self, path: Path, checked: ArchiveCheck) -> None:
        self.set_busy(False)
        self.progress_var.set("Архив успешно создан и проверен.")
        messagebox.showinfo(
            APP_TITLE,
            "Архив создан успешно.\n\n"
            f"Файл: {path}\n"
            f"Фотографий в архиве: {checked.photo_count}\n"
            f"Размер базы: {format_bytes(checked.database_bytes)}\n"
            f"Файлов всего: {checked.file_count}\n\n"
            "Старое приложение и его данные не удалены. Не удаляйте debug-версию до успешного импорта и проверки.",
        )

    def _show_error(self, text: str) -> None:
        self.current_probe = None
        self.set_busy(False)
        self.progress_var.set("")
        messagebox.showerror(APP_TITLE, text)


def self_test() -> int:
    try:
        path = adb_path()
        if not path.exists():
            return 2
        result = run_adb(["version"], timeout=10)
        return 0 if result.returncode == 0 else 3
    except Exception:
        return 4


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    try:
        adb_path()
    except Exception as exc:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror(APP_TITLE, str(exc))
        root.destroy()
        return 1
    app = MigratorApp()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
