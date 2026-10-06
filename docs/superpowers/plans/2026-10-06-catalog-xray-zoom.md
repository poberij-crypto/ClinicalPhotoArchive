# Каталог, рентген и просмотр — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать согласованные разделы каталога, рентген и масштабирование без потери локальных данных.

**Architecture:** Room v2 с nullable categoryId и отдельными категориями; сохранение существующей модели фото. Каталог управляется Repository и ViewModel, архивные операции координируются отдельно от UI; viewer использует чистую геометрию и Compose.

**Tech Stack:** Kotlin, Android SDK 36/minSdk 28, Compose Material 3/Adaptive, Room 2.8.4, Gradle Kotlin DSL.

**Spec:** `../../architecture/2026-10-06-catalog-xray-zoom.md`.

## Global Constraints

- Пациент имеет один основной раздел либо categoryId=null («Без категории»).
- Удаление категории сохраняет пациентов и фото, FK ON DELETE SET NULL.
- Значения before/operation/after неизменны; XRAY="xray"/«Рентген» — четвёртый раздел.
- Масштаб 1x–5x; двойное касание 2.5x/1x; при 1x смещение равно нулю.
- Новые ZIP version=2, reader принимает версии 1 и 2; формат clinical-photo-archive.
- Restore — полная замена, legacy TAR v1 — объединение; TAR v2 отклоняется.
- Медицинские данные локальны; INTERNET permission не добавлять; Figma только с синтетическими данными.
- Production-зависимости не обновлять ради функции; тестовые библиотеки согласовать с текущими версиями.
- Не менять незавершённые изменения в исходной рабочей копии и synced sources.

## Review Focus

- Миграция с photos FK CASCADE: перестройка patients не удаляет фото и сохраняет следующий AUTOINCREMENT ID.
- Результат камеры после смены вкладки/пациента: используется цель запуска, удалённый пациент не оставляет файл.
- Restore прерывается после копирования, до commit или после commit: при запуске восстанавливается консистентное состояние.
- Legacy merge на пациента с категорией: сохраняет его категорию; повторный импорт не назначает null.
- Fold/поворот/крупный шрифт: навигация сохраняется, жесты ограничиваются новой областью, кнопка закрытия доступна.

## Подготовка

- [x] Получить отдельную чистую копию main (`7c57357`) и ветку `feature/catalog-xray-zoom`.
- [x] Сверить отличие локального проекта от main; включить LegacyArchiveImporter в план.
- [x] Подготовить editable Figma-макеты compact/expanded, каталога, форм и viewer: https://www.figma.com/design/dAAuQ5Vjw75AodIhEVDcLq (wireframes с Material 3; синтетические данные). Проверены скриншоты каталога, подтверждения удаления и expanded layout.
- [ ] Подготовить локальные Gradle/SDK в workspace, зафиксировать результат baseline сборки.
- [ ] Получить подтверждение плана и выбрать способ исполнения до изменения продуктового кода.

## Task 1: Модель каталога и миграция

**Files:** `app/src/main/java/com/clinicalphotoarchive/data/{Entities,Daos,ClinicalDatabase}.kt`; новые `CategoryDao.kt`, `CategoryNames.kt`, `CatalogRepository.kt`, `DatabaseMigrations.kt`; `app/schemas/com.clinicalphotoarchive.data.ClinicalDatabase/{1,2}.json`; `app/build.gradle.kts`.

**Interfaces:** CategoryNames.normalize(raw:String): CategoryName(name:String,key:String); CategoryDao.observeWithCounts():Flow<List<CategoryWithCount>>; PatientDao.observeCategory(categoryId:Long?,query:String):Flow<List<PatientEntity>>; CatalogRepository.createCategory(name:String):Long, renameCategory(id:Long,name:String), deleteCategory(id:Long), assignPatient(patientId:Long,categoryId:Long?). Запись patient.categoryId nullable; CategoryEntity поля из spec, nameKey unique. Все команды suspend, ошибка выражается исключением с пользовательским сообщением.

**Tests:** `app/src/test/java/com/clinicalphotoarchive/data/CategoryNamesTest.kt`; `app/src/androidTest/java/com/clinicalphotoarchive/data/CatalogMigrationTest.kt` и `CatalogRepositoryTest.kt`.

- [ ] Сгенерировать schema v1 из неизменённого приложения и подключить её к instrumentation assets.
- [ ] Написать тесты пустого имени, кириллического дубликата, NFC/Unicode whitespace и зарезервированного имени. Assert normalize("  Остеомиелит  ").name=="Остеомиелит"; key=="остеомиелит".
- [ ] Запустить `gradle :app:testVerificationUnitTest`, проверить ожидаемый провал отсутствующей реализации.
- [ ] Реализовать нормализацию 1–100 code points, CategoryEntity/DAO/Repository, XRAY, FK и фильтр категории.
- [ ] Написать migration test с пациентом id=50, фото id=80 в каждой старой секции; assert все поля/counts/path сохранены, categoryId=null, новая вставка ID>50/80, FK check пустой.
- [ ] Запустить instrumentation test до миграции; проверить ожидаемый провал отсутствия пути 1→2.
- [ ] Реализовать транзакционную перестройку зависимых таблиц; сохранить прежний sqlite_sequence high-water mark, включая ранее удалённые максимальные IDs; зарегистрировать MIGRATION_1_2, schema v2.
- [ ] Проверить Room migration validation и удаление заполненной категории: categoryId=null, updatedAt изменён, photo rows и файлы сохранены; rollback при ошибке.
- [ ] Запустить unit/instrumentation checks; сделать отдельный commit модели и миграции.

## Task 2: Архивы и сохранность файлов

**Files:** `util/BackupArchive.kt`, `util/LegacyArchiveImporter.kt`; новые `util/ArchiveOperationCoordinator.kt`, `util/ArchiveRecoveryJournal.kt`, `util/BackupManifest.kt`; `ClinicalArchiveApplication.kt`; `ui/AppViewModel.kt` только для использования координатора.

**Interfaces:** application.archiveOperations — один общий координатор; suspend withMutation<T>(block:suspend()->T):T и withArchive<T>(block:suspend()->T):T сериализуют операции. BackupManifest.parse(json:JSONObject):BackupData(categories,patients,photoSpecs) валидирует весь manifest без записи. ArchiveRecoveryJournal.recover(database:ClinicalDatabase) сверяет зарегистрированные файлы с БД; никакого удаления по одному префиксу имени.

**Tests:** `app/src/androidTest/java/com/clinicalphotoarchive/util/BackupArchiveTest.kt`, `ArchiveRecoveryTest.kt`, `LegacyArchiveImporterTest.kt`; fixtures содержат только синтетические данные.

- [ ] Написать round-trip v2 с пустой и заполненной категорией, categoryId, xray и checksums; v1-restore заменяет существующие категории пустым каталогом. Запустить до изменения reader/exporter и проверить отказ v2.
- [ ] Реализовать writer v2/reader v1-v2 с категориями, пересчётом searchKey и строгими ссылками/IDs/sections; v1 разрешает только три старые секции.
- [ ] Написать тесты unknown version, dangling category, duplicate ZIP name/archiveFile, traversal и limits; assert прежняя БД и hashes не меняются при отказе.
- [ ] Реализовать потоковые пределы: manifest 16 MiB, image 512 MiB, total 20 GiB, entries 100000; свободное место и cleanup при ошибках.
- [ ] Написать fault tests для копирования/transaction rollback/process death до и после commit, а также блокировки мутаций во время export/restore. Проверить провалы на исходном коде.
- [ ] Реализовать staging/journal, атомарную замену БД и последующую очистку; snapshot metadata в транзакции, файлы защищены координатором.
- [ ] Написать legacy v1 merge тест на пациента с категорией и тест отказа TAR v2. Реализовать проверку user_version/схемы, новые пациенты null; при merge сохранять categoryId.
- [ ] Запустить unit/instrumentation; отдельный commit архивной совместимости.

## Task 3: Состояние каталога и цель импорта

**Files:** `ui/AppViewModel.kt`; новые `ui/CatalogSelection.kt`, `ui/MediaCaptureTarget.kt`; `util/ImageFiles.kt` только cleanup/IO при необходимости.

**Interfaces:** CatalogSelection.Root, Uncategorized, Category(id:Long); vm.catalogSelection:StateFlow<CatalogSelection>; selectCategory(id:Long?), openCatalogRoot(); createCategory/renameCategory/deleteCategory/assignPatient. MediaCaptureTarget(patientId:Long,section:PhotoSection,path:String?) фиксируется перед launcher; commitCamera(target), importPhotos(target,uris) проверяют существование пациента.

**Tests:** `app/src/test/java/com/clinicalphotoarchive/ui/MediaCaptureTargetTest.kt`; `app/src/androidTest/java/com/clinicalphotoarchive/ui/CatalogStateTest.kt`.

- [ ] Написать тест сохранения patientId/section при изменении выбранных значений и cleanup при удалении пациента/отказе DAO; проверить исходный провал.
- [ ] Реализовать сохранённую цель камеры/галереи и координацию всех мутаций, сброс после restore, сообщения ошибок без падения приложения.
- [ ] Написать тесты Root/null разграничения, фильтра поиска и удаления/перемещения выбранного пациента; реализовать StateFlow и SavedStateHandle для IDs/поиска/вкладки.
- [ ] После recreation сверять сохранённые IDs с БД; bitmap не сериализовать. Проверить формы без потери ввода.
- [ ] Запустить проверки; отдельный commit состояния и media target.

## Task 4: Каталог, формы и адаптивная навигация

**Files:** `ui/ClinicalArchiveApp.kt`, `ui/BackupControls.kt`; новые `ui/CategoryCatalog.kt`, `ui/CategoryDialogs.kt`, `ui/CategoryPicker.kt`; адаптировать `MainActivity.kt` только если нужна конфигурация state.

**Interfaces:** UI использует команды/StateFlow Task 3. Каталог отображает Uncategorized первым и categories по sortOrder/id; forms возвращают categoryId вместе с прежними полями пациента. Управление категориями выполняется через Repository Task 1.

**Tests:** `app/src/androidTest/java/com/clinicalphotoarchive/ui/CatalogNavigationTest.kt`.

- [ ] Написать UI tests маршрута каталог→раздел→пациент→Back, удаления раздела без пациента, создания/выбора категории и наличия вкладки «Рентген»; проверить исходные провалы.
- [ ] Реализовать формы с rememberSaveable, confirmation count, disabled actions при archiveBusy и readable errors.
- [ ] Внедрить Material 3 Adaptive list/detail с компактной последовательной навигацией и hinge-aware layout; не вводить обязательную третью панель.
- [ ] Реализовать четыре вкладки с прокруткой при недостатке ширины и category picker в add/edit patient.
- [ ] Проверить landscape, resize, folded/unfolded/tabletop, font scale, системный Back; синтетические скриншоты сравнить с Figma.
- [ ] Запустить UI checks; отдельный commit интерфейса.

## Task 5: Viewer и геометрия

**Files:** новые `ui/ZoomGeometry.kt`, `ui/ZoomablePhotoViewer.kt`; `ui/ClinicalArchiveApp.kt`, `util/ImageFiles.kt`.

**Interfaces:** ZoomGeometry.transform(state:ZoomState,zoom:Float,panX:Float,panY:Float,centroidX:Float,centroidY:Float,bounds:ZoomBounds):ZoomState; ZoomState(scale:Float,offsetX:Float,offsetY:Float); ZoomBounds(viewportW,viewportH,fittedW,fittedH). Viewer(photo:PhotoEntity,onDismiss:()->Unit) owns cancellable IO decoding and ephemeral geometry.

**Tests:** `app/src/test/java/com/clinicalphotoarchive/ui/ZoomGeometryTest.kt`; `app/src/androidTest/java/com/clinicalphotoarchive/ui/PhotoViewerTest.kt`.

- [ ] Написать тесты clamp [1,5], bounds portrait/landscape/square, сохранения centroid, scale=1 offsets=0, double tap 2.5/1 и resized viewport reset. Запустить до реализации и проверить ожидаемый провал.
- [ ] Реализовать чистую геометрию, Compose gestures без rotation; отдельная область изображения с clipping, управление не масштабируется.
- [ ] Перенести decode в cancellable IO, сохранять EXIF и оригиналы; ошибка файла оставляет доступное закрытие. Не загружать новый bitmap на каждом жесте.
- [ ] Добавить доступные zoom +/-/reset, системный Back; сброс при смене снимка/размера и сохранение photoId при recreation.
- [ ] Запустить unit/UI tests; отдельный commit viewer.

## Task 6: Проверка интеграции и PR

**Files:** `app/build.gradle.kts` (версия только при подготовке релиза), `.github/workflows/build-apk.yml`, новая `.github/workflows/test-android.yml`, `README.md`, документация Figma.

- [ ] Запустить `gradle :app:testVerificationUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`; release проверить с существующими secrets в CI, не публиковать keystore.
- [ ] Запустить `gradle :app:connectedVerificationAndroidTest` на API 28 и актуальном API; CI emulator job покрывает Room/restore/UI.
- [ ] Регрессионно проверить старые три вкладки, импорт камеры/галереи, удаление пациента/файлов, v1 backup/legacy TAR, offline и permissions.
- [ ] Зафиксировать ограничения фактической проверки: устройство/Fold/SAF/подпись нельзя считать проверенными только по unit tests.
- [ ] Обновить README и ссылки Figma, `git diff --check`, проверить отсутствие секретов и медицинских данных.
- [ ] Открыть отдельный draft PR реализации к main, прикрепить к чату; не сливать автоматически.

## Исполнение

Рекомендуемый способ — последовательное исполнение в этом чате: задачи связаны
общими инвариантами и требуют последовательных проверок миграции/архивов.
План подтверждён пользователем; реализация выполнена последовательно. Итоговое независимое ревью выполнено одним read-only reviewer согласно executing-plans. Все замечания исправлены. Фактические результаты и оставшиеся ограничения: [verification](../../architecture/2026-10-06-catalog-xray-zoom-verification.md). Неотмеченные комбинированные пункты плана не означают отсутствие реализации: инструментальные проверки запускаются в CI, физический Fold требует отдельной проверки.
