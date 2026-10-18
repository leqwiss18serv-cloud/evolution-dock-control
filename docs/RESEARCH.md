# Research notes — Evolution X / Android 16 / LSPosed / Magisk

Документ фиксирует, **на чём основаны решения** в этом репозитории: что именно проверено по
исходникам AOSP и по метаданным библиотек, а какие подходы отклонены и почему.

---

## 1. Устройство и прошивка

| Параметр | Значение |
|---|---|
| Модель | Samsung Galaxy A71, **SM-A715F** (4G, Exynos-версии нет — здесь Snapdragon 730/SM7150) |
| Официальная поддержка Samsung | закончилась на Android 13 (One UI 5.x) |
| Прошивка | Evolution X 11.x, база — **AOSP Android 16 (SDK 36)**, без One UI |
| Root | Magisk 31.x (Zygisk), модули в `/data/adb/modules` |
| Хуки | LSPosed (в т.ч. форки вида *Vector*), современный `libxposed` API |

Практические следствия:

* Поскольку база — AOSP, лаунчер ведёт себя как **Launcher3** (Quickstep) либо как
  **Pixel Launcher** (`com.google.android.apps.nexuslauncher`), который использует тот же
  код `com.android.launcher3.*`. Поэтому один и тот же хук работает в обоих случаях.
* Samsung-специфичных «доков» (Edge panel и т.п.) в AOSP-сборке нет — скрывать нужно именно
  `Hotseat` из Launcher3.
* Требование `targetSdk 36` означает: приложение обязано собираться с `compileSdk 36`,
  иначе современные AGP просто не пропустят зависимость `libxposed` (см. п. 3.2).

---

## 2. Как работает Xposed / LSPosed (кратко и по делу)

### 2.1 Классическая схема

1. **Инъекция.** Менеджер (LSPosed) через Zygisk/Riru подгружает свою библиотеку в `zygote`.
   Все процессы Android — форки зиготы, поэтому библиотека оказывается в каждом из них.
2. **Загрузка модулей.** В процессе приложения фреймворк читает список модулей, у которых это
   приложение в *scope*, и загружает их APK отдельным `ClassLoader`.
3. **Хук.** Перехват методов ART: либо замена entry point метода (старый подход,
   `XposedBridge.hookMethod`), либо инструментация через **LSPlant** (используется в LSPosed) —
   подмена `ArtMethod::entry_point_from_quick_compiled_code` с сохранением оригинала,
   чтобы можно было вызвать «исходное» поведение.
4. **Callback.** В момент вызова перехваченного метода управление уходит в код модуля,
   который может изменить аргументы, результат или вообще не вызывать оригинал.

### 2.2 Старый и новый API

| | legacy (`de.robv.android.xposed`) | modern (`io.github.libxposed`) |
|---|---|---|
| Точка входа | `IXposedHookLoadPackage` | класс, наследующий `XposedModule` |
| Манифест модуля | `assets/xposed_init` + meta-data в `AndroidManifest.xml` | `META-INF/xposed/*` |
| Хук | `XposedHelpers.findAndHookMethod(...)` | `hook(executable).intercept { chain -> ... }` |
| Ошибки хука | вручную | `ExceptionMode.PROTECTIVE` на уровне фреймворка |

В этом репозитории используется **modern API 101** — на момент сборки это первая стабильная
версия, у которой `minCompileSdk = 36` (то есть совместимая с Android 16).
Версия 102.0.0 требует `minCompileSdk = 37` и добавила hot-reload — для нашей задачи не нужна.

### 2.3 Что обязано быть в APK современного модуля

Проверено по `package-info.java` библиотеки `io.github.libxposed:api:101.0.1`:

```
src/main/resources/META-INF/xposed/module.prop      # minApiVersion, targetApiVersion, ...
src/main/resources/META-INF/xposed/java_init.list   # FQCN точки входа, по одному в строке
src/main/resources/META-INF/xposed/scope.list       # пакеты, в которые внедряется модуль
```

`module.prop` — формат `java.util.Properties`. Обязательные ключи: `minApiVersion`,
`targetApiVersion`. Дополнительно: `staticScope`, `exceptionMode` (`protective|passthrough`),
`autoHotReload` (только API 102+).

Название и описание модуля берутся из `android:label` / `android:description` приложения —
поэтому в манифесте они заданы явно, а legacy-meta-data (`xposedmodule`, `xposedminversion`)
**не** добавлялись, чтобы фреймворк не пытался грузить модуль по старому пути
(там ожидается `assets/xposed_init`, которого у нас нет).

### 2.4 Жизненный цикл и почему это безопасно

`onModuleLoaded` → `onPackageLoaded` (classloader готов, `Application` ещё нет) →
`onPackageReady`. Хуки ставятся в `onPackageLoaded`, потому что классы лаунчера уже загружаемы,
а UI ещё не создан — идеальный момент, чтобы повлиять на первый же layout.

`ExceptionMode.PROTECTIVE` — единственный режим, который гарантирует: исключение внутри хука
не всплывёт в лаунчер. Именно это (плюс отсутствие хуков в `system_server`) делает бутлуп
практически невозможным: даже полностью сломанный хук даст «стандартный лаунчер».

---

## 3. Внутренности Launcher3: где живёт док

Проверено по исходникам AOSP `platform/packages/apps/Launcher3` (ветка `main`, актуальная для Android 16).

### 3.1 Класс

* Android 14–16: `com.android.launcher3.Hotseat` (файл `src/com/android/launcher3/Hotseat.java`).
* Более старые сборки: `com.android.launcher3.hotseat.Hotseat`.
* Иерархия: `Hotseat extends CellLayout extends ViewGroup` — то есть это контейнер иконок,
  а не «панель» в терминах Material.

Публичные/защищённые методы, которые нам важны:

```java
public Hotseat(Context context, AttributeSet attrs, int defStyle)
public void setInsets(Rect insets)                  // вызывается при каждой смене инсетов
protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
protected void onLayout(boolean changed, int l, int t, int r, int b)
public void setWorkspace(Workspace<?> w)
```

`onMeasure` внутри себя измеряет только QSB (`mQsb.measure(...)` с размерами из `DeviceProfile`),
остальное — стандартный `CellLayout`.

### 3.2 Разметка

`res/layout/launcher.xml`:

```xml
<include android:id="@+id/hotseat" layout="@layout/hotseat" />
```

`res/layout/hotseat.xml`:

```xml
<com.android.launcher3.Hotseat
    android:id="@+id/hotseat"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    launcher:containerType="hotseat" />
```

Ключевой момент: **`match_parent`**. Док — это отдельный полноэкранный слой над `Workspace`,
а не полоска высотой N dp внизу. Отсюда:

* `View.GONE` не оставляет «дырки» — соседний слой `Workspace` и так растянут на весь экран;
* не нужно пересчитывать `DeviceProfile` (а значит, невозможно сломать сетку, папки, drag&drop);
* `setMeasuredDimension(w, 0)` здесь был бы **вреден**: слой не «полоска», его высота не
  участвует в раскладке рабочей области.

`Launcher.setupViews()`:

```java
protected void setupViews() {
    inflateRootView(R.layout.launcher);
    ...
    mHotseat = findViewById(R.id.hotseat);
    mHotseat.setWorkspace(mWorkspace);
}
```

### 3.3 `DeviceProfile` — почему мы его не трогаем

Поля, связанные с доком (`src/com/android/launcher3/DeviceProfile.java`):

```java
public int hotseatCellHeightPx;
public int hotseatBarSizePx;
public int hotseatBarBottomSpacePx;
public int hotseatBarEndOffset;
public final int hotseatQsbHeight;
```

Соблазн «обнулить `hotseatBarSizePx`» есть, но: часть полей `final`, имена меняются между
версиями, а `availableHeightPx` вычисляется в конструкторе **до** того, как мы можем вмешаться.
Модификация `DeviceProfile` — это правка арифметики раскладки с непредсказуемым результатом
(съехавшие иконки, «прыгающая» сетка). Поэтому подход отклонён: `GONE` на вьюхе даёт тот же
визуальный результат без вмешательства в геометрию.

---

## 4. Magisk-модуль: что и когда выполняется

| Файл | Когда | Особенности |
|---|---|---|
| `META-INF/com/google/android/update-binary` | установка | канонический шаблон: подключает `/data/adb/magisk/util_functions.sh`, вызывает `install_module` |
| `customize.sh` | установка, после распаковки | `ui_print`, `set_perm*`, определение лаунчера |
| `service.sh` | `late_start service` при загрузке | у нас — **однократная** сверка флага и `exit 0` |
| `action.sh` | по кнопке **Action** | запускается **без TTY**: `read` из stdin в приложении Magisk не работает |
| `uninstall.sh` | удаление модуля | чистит `Settings.Secure` и `/data/adb/evolution_dock_control` |
| `system/bin/dockctl` | всегда | монтируется в `/system/bin`, доступен как обычная команда |

Из-за отсутствия TTY в `action.sh` реализована трёхрежимная логика:

```
без аргументов + терминал  -> меню (один read, сразу exit)
без аргументов + Magisk    -> один toggle, сразу exit
с аргументом               -> on|off|toggle|status
```

Ни `while true`, ни `sleep`-циклов, ни фоновых процессов: скрипт всегда завершается.

---

## 5. Отклонённые подходы

### 5.1 `settings put secure launcher_dock_height 0`

Такого ключа не существует. В AOSP `Settings.Secure` для лаунчера есть, например,
`navigation_mode`, `assistant`, но геометрия дока настройками не управляется — она целиком
в ресурсах и `DeviceProfile`. Запись произвольного ключа лаунчер просто проигнорирует.
Поэтому в проекте этот ключ используется **не как настройка лаунчера**, а как собственный
флаг модуля (`evolution_dock_hidden`), который читает наш хук. Это честная IPC-точка:
`Settings.Secure` доступен на чтение любому приложению без разрешений, а на запись — только root.

### 5.2 RRO / Overlay APK с переопределением `dimen`

Технически возможно собрать overlay, таргетящий `com.android.launcher3`, и переопределить
`hotseat_*` размерности. Минусы: имена ресурсов приватные и меняются между версиями;
overlay нужно подписывать и включать через `cmd overlay enable`; при обновлении лаунчера
overlay «отваливается». Плюс это меняет ресурсы глобально, а не только видимость.
Для задачи «скрыть слой» это избыточно и хрупко.

### 5.3 Подмена/патч APK лаунчера через Magisk

Монтирование модифицированного `NexusLauncher.apk` или правка `resources.arsc`:
ломает подпись системы, конфликтует с обновлениями, при неудаче — лаунчер не стартует
(и это уже реальный бутлуп). Отклонено по требованию «никаких бутлупов».

### 5.4 AccessibilityService / оверлей поверх дока

Рисует поверх, но не освобождает место, ест батарею и требует постоянного сервиса.
Прямо противоречит требованию «zero performance impact».

### 5.5 `wm overscan` / `wm size`

Устарело и удалено в современных Android; глобально меняет размер экрана.

---

## 6. Риски и их mitigation

| Риск | Вероятность | Mitigation |
|---|---|---|
| Лаунчер переименовал класс/метод | средняя | список кандидатов + страховка через `findViewById("hotseat")`; при неудаче — просто ничего не делаем |
| Хук падает | низкая | `PROTECTIVE` + `try/catch` вокруг всей логики, `chain.proceed()` вызывается всегда |
| Бутлуп | очень низкая | хуки только в процессе лаунчера; `system_server`/`zygote` не затрагиваются; при сбое лаунчер стартует штатно |
| Флаг не применился | низкая | `action.sh` проверяет `settings get` после записи и перезапускает лаунчер |
| Смена подписи APK при обновлении | низкая | ключ в репозитории, подпись стабильна |
| «Док вернулся» после смены темы/конфигурации | низкая | `onMeasure`-хук самовосстанавливает `GONE` |

---

## 7. Источники

* AOSP Launcher3 (Hotseat, launcher.xml, DeviceProfile, Launcher.setupViews):
  `https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/`
* libxposed API (исходники и документация пакета, `package-info.java`):
  `https://github.com/libxposed/api` · Maven Central `io.github.libxposed:api`
* LSPosed (механизм инъекции, LSPlant, менеджер модулей): `https://github.com/LSPosed/LSPosed`
* Magisk (формат модулей, `customize.sh`/`service.sh`/`action.sh`):
  `https://topjohnwu.github.io/Magisk/guides.html`
* Evolution X: `https://github.com/Evolution-X`
* Android 16 / API 36 (поведенческие изменения, `targetSdk 36`):
  `https://developer.android.com/about/versions/16`
