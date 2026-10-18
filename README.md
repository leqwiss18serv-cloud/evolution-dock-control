# Evolution Dock Control

Скрытие системного дока (Hotseat) на **Evolution X / Android 16** для **Samsung Galaxy A71 (SM-A715F)**.
Два независимых компонента, один общий флаг состояния:

| Компонент | Что делает | Артефакт |
|---|---|---|
| **LSPosed-модуль** (`lsposed/`) | Хукает `Hotseat` внутри процесса лаунчера и ставит `View.GONE`. Ноль фоновой работы. | `EvolutionDockControl.apk` |
| **Magisk-модуль** (`magisk/`) | Переключатель: кнопка **Action**, `dockctl`, меню в терминале. Пишет флаг в `Settings.Secure`. | `evolution-dock-control-magisk-vX.zip` |

```
        ┌─────────────────────────────┐        ┌──────────────────────────────┐
        │  Magisk: action.sh / dockctl│        │  LSPosed: DockControlModule  │
        │  (root, one-shot)           │        │  (в процессе лаунчера)       │
        └──────────────┬──────────────┘        └───────────────┬──────────────┘
                       │  settings put secure                  │ читает 1 раз
                       │  evolution_dock_hidden=1|0            │ за процесс
                       ▼                                       ▼
              ┌────────────────────────────────────────────────────────┐
              │  Settings.Secure  (world-readable, root-writable)      │
              └────────────────────────────────────────────────────────┘
                       │  am force-stop <launcher> + KEYCODE_HOME
                       ▼
              ┌────────────────────────────────────────────────────────┐
              │  Launcher3 / Pixel Launcher: Hotseat.setVisibility(GONE)│
              └────────────────────────────────────────────────────────┘
```

---

## 1. Как это работает

### Почему именно хук, а не «настройка»

В AOSP Launcher3 (и в Pixel Launcher, который использует тот же код) **нет** настройки
`launcher_dock_height` / `hotseat_height`. Док — это обычный `View` из `res/layout/hotseat.xml`:

```xml
<com.android.launcher3.Hotseat
    android:id="@+id/hotseat"
    android:layout_width="match_parent"
    android:layout_height="match_parent"   <!-- это отдельный полноэкранный слой -->
    launcher:containerType="hotseat" />
```

`Launcher.setupViews()` делает `mHotseat = findViewById(R.id.hotseat)`.
Слой дока лежит **поверх** `Workspace`, а сам `Workspace` тоже `match_parent`.
Отсюда ключевой вывод: достаточно убрать слой дока (`GONE`) — рабочая область сама
занимает освободившееся место, никакая арифметика `DeviceProfile` не ломается.

### Точки перехвата

| Метод | Зачем |
|---|---|
| `Hotseat.onMeasure(int,int)` | Основной хук. Перед измерением ставит `GONE`; самовосстанавливается, если лаунчер снова сделает док видимым. |
| `Hotseat.setInsets(Rect)` | Ранний хук во время раскладки — док скрывается до первого кадра. |
| `Launcher.setupViews()` | Страховка: находит вьюху по имени ресурса `hotseat` и скрывает её (на случай собственного подкласса `Hotseat` в прошивке). |

Имена классов подставляются по списку (`com.android.launcher3.Hotseat` для Android 14–16 и
`com.android.launcher3.hotseat.Hotseat` для более старых сборок); отсутствующие просто пропускаются.

### Почему нет бутлупа

* Хуки устанавливаются **только** в процессах `com.google.android.apps.nexuslauncher` и
  `com.android.launcher3`. `system_server`, `zygote` и остальные приложения не затрагиваются.
* Каждый хук — в режиме `ExceptionMode.PROTECTIVE` (задан и в коде, и в `META-INF/xposed/module.prop`):
  исключение логируется, оригинальный метод выполняется как обычно.
* Внутри хуков нет ничего, кроме `if (visibility != GONE) visibility = GONE`.
* Нет потоков, таймеров, `Handler.postDelayed`, `ContentObserver` и опросов.

### Почему нет влияния на производительность

* Чтение флага — **один** запрос к `Settings.Secure` на процесс лаунчера, результат кэширован
  в `@Volatile` поле. Никаких обращений к настройкам на кадр.
* В `onMeasure` добавлено одно сравнение `Int` и, в худшем случае, один вызов `setVisibility`.
* В `onLayout`/`onDraw`/`dispatchTouchEvent` не добавлено ничего.

---

## 2. Установка

### 2.1 LSPosed-модуль (основной, именно он скрывает док)

1. Установить `EvolutionDockControl.apk` (подписан, `targetSdk 36`, `minSdk 29`).
2. LSPosed → **Модули** → *Dock Control* → включить.
3. Область действия (Scope) уже прописана в APK (`META-INF/xposed/scope.list`):
   `com.google.android.apps.nexuslauncher`, `com.android.launcher3`.
   Проверить, что ваш лаунчер отмечен.
4. Перезапустить лаунчер: `su -c 'am force-stop com.google.android.apps.nexuslauncher'`
   либо просто перезагрузить телефон.

После активации док скрыт по умолчанию (`evolution_dock_hidden` отсутствует → считается `1`).

> Вместе с доком скрывается и QSB (строка поиска): в Launcher3 она является **дочерней вьюхой**
> внутри `Hotseat`, отдельного хука для неё нет.

### 2.2 Magisk-модуль (переключатель + терминал)

1. Magisk → **Модули** → *Установить из хранилища* → выбрать ZIP.
2. Перезагрузить.
3. **Action** (кнопка «Действие» у модуля) — переключает состояние: скрыт ⇄ показан.
   Скрипт завершается сразу после выбора, ничего не висит в фоне.
4. Из терминала:

```sh
su -c dockctl status     # состояние
su -c dockctl on         # скрыть док
su -c dockctl off        # показать док
su -c dockctl toggle     # переключить
su -c dockctl menu       # интерактивное меню
```

`action.sh` поддерживает и аргументы (`on|off|toggle|status`) — удобно для Tasker/ADB:

```sh
adb shell su -c 'sh /data/adb/modules/evolution_dock_control/action.sh on'
```

### 2.3 Приложение (опционально)

Иконка *Dock Control* в лотке: статус + кнопки «Скрыть док» / «Показать док» /
«Перезапустить лаунчер». Кнопки вызывают те же root-команды через `su`.

---

## 3. Полная реверсивность

| Действие | Результат |
|---|---|
| `dockctl off` | док возвращается сразу после перезапуска лаунчера |
| Отключить модуль в LSPosed | лаунчер работает штатно (хуки не устанавливаются) |
| Удалить Magisk-модуль | `uninstall.sh` удаляет `evolution_dock_hidden` и `/data/adb/evolution_dock_control` |
| Удалить APK | ничего не остаётся: модуль не пишет файлов и не меняет системные разделы |

Ни один компонент не трогает `/system`, `/product`, `boot.img` и не подменяет APK лаунчера.

---

## 4. Сборка

### CI (GitHub Actions)

* `.github/workflows/build_apk.yml` — `gradle assembleRelease` (JDK 21, Android SDK 36, Gradle 8.14.3),
  APK уходит в артефакты.
* `.github/workflows/build_magisk.yml` — проверка структуры (`sh -n` всех скриптов) и упаковка в ZIP.

### Локально

```bash
cd lsposed
gradle assembleRelease          # или ./gradlew, если сгенерируете wrapper
# APK: app/build/outputs/apk/release/app-release.apk
```

Ключ подписи: `lsposed/keystore/dockcontrol.jks`, пароль и alias — `dockcontrol`.
Ключ лежит в репозитории намеренно: это публичный хобби-модуль, а стабильная подпись нужна,
чтобы обновление ставилось поверх предыдущей версии.

---

## 5. Ограничения и предупреждения

* **Magisk-модуль сам по себе док не скрывает.** Он управляет флагом и перезапускает лаунчер;
  скрывает док именно LSPosed-хук. Так сделано потому, что настройки, которая отключает Hotseat,
  в Launcher3 не существует (см. `docs/RESEARCH.md`, раздел «Отклонённые подходы»).
* Скрывается весь слой `Hotseat`: иконки дока **и** QSB. Если нужна только строка поиска без
  дока — это другой модуль (хук на `mQsb`), здесь не реализовано.
* После смены флага нужен перезапуск лаунчера — значение кэшируется на процесс.
* На планшетах/в десктопном режиме Android 16 есть `Taskbar` — он не затрагивается.
* Прошивка может переименовать классы лаунчера. Тогда в логах LSPosed будет
  `cannot hook ...`, а док останется на месте — это безопасный отказ, а не бутлуп.

## 6. Диагностика

```sh
# логи модуля
su -c 'logcat -s EvoDockControl LSPosed'

# состояние флага
su -c 'settings get secure evolution_dock_hidden'

# какой лаунчер установлен
su -c 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
```

| Симптом | Причина / решение |
|---|---|
| Док не исчез | модуль не включён в LSPosed, лаунчер не в scope, или лаунчер не перезапущен |
| Док исчез, но остался пустой отступ | лаунчер не из семейства Launcher3 — проверьте `resolve-activity` |
| Action в Magisk ничего не меняет | нет LSPosed-модуля (флаг пишется, но его никто не читает) |
| После обновления APK «приложение не установлено» | подпись сменилась (сборка без `keystore/dockcontrol.jks`) — удалите старую версию |

---

## 7. Структура репозитория

```
evolution-dock-control/
├── lsposed/                     # Gradle-проект (Kotlin, AGP 8.13, libxposed API 101)
│   ├── app/src/main/java/com/leqwiss/dockcontrol/
│   │   ├── DockControlModule.kt # точка входа XposedModule
│   │   ├── HotseatHooks.kt      # все перехваты
│   │   ├── DockState.kt         # чтение флага (кэш на процесс)
│   │   └── MainActivity.kt      # мини-UI без AndroidX
│   ├── app/src/main/resources/META-INF/xposed/   # module.prop / java_init.list / scope.list
│   └── keystore/dockcontrol.jks
├── magisk/                      # Magisk-модуль
│   ├── module.prop  customize.sh  action.sh  service.sh  uninstall.sh  common.sh
│   ├── system/bin/dockctl
│   └── META-INF/com/google/android/{update-binary,updater-script}
├── docs/RESEARCH.md             # разбор прошивки, Xposed/LSPosed, Hotseat, Magisk
└── .github/workflows/           # сборка APK и ZIP
```

## 8. Лицензия

MIT. Используете на свой риск: разблокированный загрузчик, root и модификация лаунчера — зона,
где ответственность за последствия лежит на владельце устройства.
