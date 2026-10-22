# vpngramm 2.0.1 для Windows 11

Настольная версия приложения vpngramm (порт Android-версии 2.0.1): ставит и обслуживает свой VPN-сервер
(Hysteria2, VLESS Reality, AmneziaWG, MTProto) по SSH, выдаёт ссылки и QR-коды, держит постоянный профиль «Главной».
Логика (скрипты установки, SSH, DNS name.com, OneProvider, копии, ссылки) — та же самая, что в Android-версии.

## Как получить программу

**Готовая сборка.** Во вкладке **Actions** этого репозитория запустите (или откройте последний) workflow **Windows build** —
в артефакте `vpngramm-windows` лежат:
- `vpngramm-2.0.1.msi` / `vpngramm-2.0.1.exe` — установщик (ставится для текущего пользователя, ярлык в меню «Пуск»);
- `vpngramm-2.0.1-windows-portable.zip` — без установки: распакуйте и запустите `vpngramm\vpngramm.exe`.

Java ставить не нужно — она встроена в сборку.

**Собрать самому** (Windows 11, JDK 17 или новее, для MSI/EXE ещё [WiX Toolset 3.x](https://wixtoolset.org/)):

```powershell
.\gradlew run                 # запустить из исходников
.\gradlew test                # тесты логики
.\gradlew packageMsi          # установщик MSI  -> build\compose\binaries\main\msi
.\gradlew packageExe          # установщик EXE  -> build\compose\binaries\main\exe
.\gradlew createDistributable # портативная папка -> build\compose\binaries\main\app\vpngramm
```

Если Maven Central отвечает `429 Too Many Requests`, подождите и повторите: в `settings.gradle.kts` уже подключено зеркало.

## Чем отличается от Android-версии

| Android | Windows |
|---|---|
| Пароли и ключи в Android Keystore | Шифруются Windows DPAPI (привязаны к вашей учётной записи Windows) |
| Фоновый контроль серверов (WorkManager) | Проверки идут, пока программа запущена; при включённом контроле закрытие окна сворачивает её в трей |
| Уведомления в шторке | Уведомления из значка в трее |
| Служба на время долгой работы | На время долгой работы Windows не засыпает |
| «Поделиться» | «В файл» (сохранение ссылок/отчёта в текстовый файл) |
| Выбор файла Android | Стандартные окна Windows «Открыть» / «Сохранить как» |
| Цвета из обоев (Material You) | Фирменная палитра, светлая/тёмная тема по настройке Windows |

Данные программы лежат в `%APPDATA%\vpngramm` (настройки, зашифрованные секреты, `known_hosts`, отчёт о падении).

## Проверка

Логика покрыта 119 тестами из Android-версии (все проходят). Сам графический интерфейс собран и скомпилирован,
но на Windows 11 вживую его ещё стоит прогнать: если что-то не так, пришлите текст ошибки (при падении программа
при следующем запуске покажет отчёт).

Шрифт логотипа — Grand Hotel, лицензия OFL (`FONT-LICENSE-OFL.txt`).
