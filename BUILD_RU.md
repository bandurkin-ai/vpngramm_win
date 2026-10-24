# Как самому собрать APK в Android Studio

Что понадобится: компьютер (Windows/macOS/Linux), интернет, ~10 ГБ свободного места, установленная Android Studio.

## Шаг 1. Распакуйте проект
1. Скачайте `Hy2Setup-source.zip`.
2. Распакуйте в папку с простым путём **без русских букв и пробелов**, например `C:\Projects\Hy2Setup` или `~/Projects/Hy2Setup`.
3. Внутри должны лежать `app`, `gradle`, `build.gradle.kts`, `settings.gradle.kts`.
   Проверьте, что вы не открыли вложенную папку `Hy2Setup/Hy2Setup`.

## Шаг 2. Откройте проект
1. Запустите Android Studio → **File → Open** (на стартовом экране: **Open**).
2. Выберите папку `Hy2Setup` (ту, где `settings.gradle.kts`) и нажмите OK.
3. Если спросит «Trust project» — нажмите **Trust Project**.

## Шаг 3. Дождитесь Gradle Sync
- Внизу справа появится полоса прогресса «Gradle sync». Первый раз это 3–15 минут: скачиваются Gradle и библиотеки.
- Если Studio просит установить недостающие компоненты (**Android SDK Platform 35**, **Build-Tools**) — нажимайте **Install / Accept** и дождитесь окончания.
- Если предлагает обновить Android Gradle Plugin или Gradle — **откажитесь** («Remind me later / Don't ask»). Проект уже настроен рабочим набором версий.
- Успех: внизу в окне **Build** написано «BUILD SUCCESSFUL» / sync без красных ошибок.

## Шаг 4. Проверьте JDK (если sync падает)
**File → Settings** (macOS: Android Studio → Settings) → **Build, Execution, Deployment → Build Tools → Gradle** → **Gradle JDK**: выберите **jbr-17** или **jbr-21** (встроенный JetBrains Runtime). Нажмите OK и повторите sync: **File → Sync Project with Gradle Files**.

## Шаг 5. Соберите APK
1. Меню **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
2. Подождите 1–3 минуты. Справа внизу появится уведомление **«APK(s) generated successfully»**.
3. Нажмите в уведомлении **locate** — откроется папка с файлом.
   Путь на диске: `Hy2Setup/app/build/outputs/apk/debug/app-debug.apk`

Это отладочная (debug) сборка. Она подписана автоматически и прекрасно устанавливается на телефон.

## Шаг 6. Установите на телефон
**Способ А — файлом**
1. Передайте `app-debug.apk` на телефон (кабель, Telegram «Избранное», Google Drive и т.п.).
2. Откройте файл на телефоне, разрешите установку из этого источника.
3. Если Play Protect предупреждает — «Всё равно установить».

**Способ Б — напрямую кабелем (удобно при разработке)**
1. На телефоне: *Настройки → О телефоне* → 7 раз нажмите «Номер сборки».
2. *Настройки → Для разработчиков* → включите **Отладку по USB**.
3. Подключите телефон кабелем, подтвердите «Разрешить отладку по USB?».
4. В Android Studio в выпадающем списке устройств (вверху) выберите свой телефон и нажмите зелёную кнопку **▶ Run**. Приложение установится и запустится само.

## Запуск в эмуляторе (вместо телефона)
1. **Tools → Device Manager → + → Create Virtual Device** → Pixel 7 → образ **API 34/35** (скачайте) → Finish.
2. Выберите эмулятор в списке устройств и нажмите **▶ Run**.
Нужна включённая виртуализация (VT-x / AMD-V) в BIOS.

## Если что-то не получилось
| Проблема | Что делать |
|---|---|
| `SDK location not found` | **File → Project Structure → SDK Location** — укажите папку Android SDK; либо создайте файл `local.properties` рядом с `settings.gradle.kts` со строкой `sdk.dir=C\:\\Users\\ИМЯ\\AppData\\Local\\Android\\Sdk` (путь у вас может отличаться; в Studio он виден в **Settings → Languages & Frameworks → Android SDK**) |
| `Could not resolve ...` / `Connection refused` / таймаут | Нет доступа к интернету или нужные сайты блокируются. Включите нормальный интернет/VPN и нажмите **File → Sync Project with Gradle Files** ещё раз |
| `429 Too Many Requests` | Подождите несколько минут и повторите sync |
| `Unsupported class file major version` / `Java version` | Шаг 4: выберите Gradle JDK 17 или 21 |
| `Failed to find Build Tools` / `platform android-35` | **Tools → SDK Manager** → вкладка *SDK Platforms* отметьте **Android 15 (API 35)**, вкладка *SDK Tools* — **Android SDK Build-Tools**; Apply |
| Чистая пересборка не помогла | **Build → Clean Project**, затем **Build → Rebuild Project**; в крайнем случае **File → Invalidate Caches → Invalidate and Restart** |
| Любая другая красная ошибка | Откройте внизу вкладку **Build**, скопируйте текст ошибки целиком и пришлите мне |

## Подпись релизной версии (по желанию)
Для установки себе debug-APK достаточно. Для публикации в Google Play нужен подписанный релиз:
**Build → Generate Signed App Bundle / APK → APK → Create new keystore…** Сохраните файл ключа и пароли в надёжном месте — без них приложение нельзя будет обновлять.

---

# Версия для Windows 11 (x64)

Готовый архив `vpngramm-<версия>-windows-x64.zip`: распакуйте целиком и запустите `vpngramm.exe` (Java уже внутри, папка `runtime`).
Данные программы — в `%APPDATA%\vpngramm`, пароли и ключи зашифрованы Windows DPAPI.

## Как устроено
- Модуль `desktop` собирает **тот же код**, что и Android-приложение (`app/src/main/java`), без Android-файлов
  (их список — `androidOnly` в `desktop/build.gradle.kts`), плюс своя реализация платформенного слоя:
  `desktop/src/main/kotlin` (настройки и секреты в файлах, выбор файлов, буфер обмена, окно).
- Интерфейс — Compose Multiplatform 1.6.2 (последняя версия, целиком лежащая в Maven Central).
- `vpngramm.exe` — маленький запускатор на Go (`tools/windows-launcher`), он стартует Java из папки `runtime`.

## Собрать архив самому (Linux или macOS; нужны JDK 17+, Go 1.22+ и интернет)
```
./gradlew :desktop:windowsZip
```
Архив появится в `out/`. Java для Windows (Eclipse Temurin JRE 21) скачивается один раз в `.cache/jre` и проверяется по SHA-256.

## Запустить на своём компьютере для проверки
```
./gradlew :desktop:run -Pskia=linux-x64      # Linux
./gradlew :desktop:test -Pskia=linux-x64     # тесты и снимки экранов (desktop/build/screens)
```
