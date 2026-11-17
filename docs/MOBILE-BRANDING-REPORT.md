# PeerIche Mobile: замена фирменной графики

Дата: 17 ноября 2026 года.

## Исходные материалы

Архив `PeerIche_brand_assets.zip` доступен и распакован в `release-local/brand-assets/` внутри проекта. Использованы только четыре файла Mobile:

- `PeerIche_icon_light.png`, `PeerIche_icon_dark.png` — 512 × 512, прозрачность PNG.
- `PeerIche_logo_light.png` — 826 × 170.
- `PeerIche_logo_dark.png` — 790 × 172.

Оригинальные четыре изображения перенесены в ресурсы без изменения байтов; совпадение проверено. Ресурсы `PeerIcheTV_*` не используются и не попали в APK. Новые логотипы не рисовались и не генерировались.

Для запуска подготовлены производные размеры исходных иконок. Foreground адаптивной иконки размещён с безопасными полями без растяжения пропорций. Поскольку отдельного monochrome-ресурса в архиве нет, одноцветная маска получена из контура предоставленного знака; Android самостоятельно задаёт ей цвет при использовании тематических иконок.

## Изменённые и добавленные файлы

| Файлы | Назначение |
| --- | --- |
| `app/src/main/java/com/arcticfoxapps/peeriche/BrandLogo.kt` | Общий вывод горизонтального логотипа по активной палитре приложения. |
| `MainActivity.kt` в том же каталоге | В шапке прежние знак и текст заменены одним горизонтальным логотипом; область шапки и навигация сохранены. |
| `SettingsScreen.kt` в том же каталоге | Горизонтальный логотип в разделе «О приложении». |
| `BroadcastPreferences.kt` в том же каталоге | Новый логотип вместо старой текстовой подписи в стандартной заставке эфира; пользовательские изображения и аватары не заменяются. |
| `app/src/main/res/drawable-nodpi/peeriche_logo_light.png`, `peeriche_logo_dark.png`, `peeriche_icon.png` | Оригинальные изображения Mobile. |
| `app/src/main/res/drawable-night-nodpi/peeriche_icon.png` | Оригинальный тёмный значок Mobile. |
| `drawable-nodpi/peeriche_launcher_foreground.png`, `drawable-night-nodpi/peeriche_launcher_foreground.png` | Адаптивный foreground для двух системных тем. |
| `drawable-nodpi/peeriche_launcher_monochrome.png` | Альфа-маска тематической иконки. |
| `drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` | Подключение новой графики к существующему Adaptive Icon. |
| `drawable/ic_peeriche.xml`, `ic_peeriche_mark.xml` | Прежние фирменные векторы заменены ссылками на новый значок. |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | Обычные иконки 48, 72, 96, 144 и 192 пикселя для Android до Adaptive Icon. |
| `mipmap-night-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | Тёмные варианты обычной иконки. |
| `mipmap-night-anydpi-v26/ic_launcher.xml` | Адаптивная иконка в тёмной теме; исключает выбор обычного night-PNG вместо Adaptive Icon. |
| `values/brand.xml`, `values-night/brand.xml` | Цвет фона иконки для светлой и тёмной системных тем. |
| `values-v31/styles.xml` | Фон Android Splash Screen связан с цветом новой иконки. |
| `tools/prepare_mobile_brand_assets.ps1` | Воспроизводимая подготовка размеров и маски из четырёх исходных Mobile-файлов. |
| `app/src/androidTest/java/com/arcticfoxapps/peeriche/BrandAssetsSmoke.kt` | Нативный предпросмотр Adaptive Icon, monochrome, логотипов и стандартной заставки. |
| `AuthSmokeInstrumentation.kt` в том же тестовом каталоге | Подключение проверки через параметр `brand-assets`. |

Все сокращённые пути ресурсов в таблице относятся к `app/src/main/res/`.

## Темы и границы изменений

В интерфейсе изображение выбирается по яркости активного `MaterialTheme.colorScheme.background`. Поэтому существующие режимы «Системная», «Светлая», «Графит» и «Аврора» продолжают работать; ручная тема приложения не зависит от текущей системной темы. Иконки запуска и стартовый экран используют обычные Android night-квалификаторы.

В этой задаче не изменялись навигация, настройки, аккаунты, пользовательские данные, схема базы, applicationId, версия или механизм переключения тем. Изменения Kotlin-кода относятся только к отрисовке графики. Подключённый физический телефон не использовался; проверки выполнялись только в отдельном эмуляторе.

До начала задачи в рабочем дереве уже находилось ранее запрошенное исправление фильтра чувствительного контента. Оно сохранено без изменений. Debug APK собран из текущего рабочего дерева, включая это предыдущее исправление.

## Проверки

- `assembleDebug` — успешно; `lintDebug` — без ошибок.
- Нативная проверка на Android API 37 — PASS: светлый и тёмный ресурсы являются Adaptive Icon, monochrome-слои доступны, логотипы и стандартная заставка отрисованы.
- Иконка проверена в системной панели приложений Android.
- В приложении проверены шапка и «О приложении» в светлом и тёмном режимах; отдельно проверена «Аврора» на планшетной компоновке.
- Пропорции логотипов сохранены; заголовок не перекрывает выбор сервера.
- В APK отсутствуют ресурсы с обозначением PeerIcheTV; исходные Mobile PNG совпадают с файлами архива.
- Обычные иконки всех пяти плотностей присутствуют. Отдельное физическое устройство с Android до 8.0 не использовалось.

Предпросмотры:

- [Adaptive Icon и monochrome в двух темах](previews/mobile-brand-native.png)
- [Новая иконка в системной панели](previews/mobile-brand-launcher.png)
- [Светлая шапка](previews/mobile-brand-home-initial.png)
- [Тёмный экран «О приложении»](previews/mobile-brand-about-dark.png)
- [Светлый экран «О приложении»](previews/mobile-brand-about-light.png)
- [Тема «Аврора», планшет](previews/mobile-brand-aurora-tablet.png)
- [Стандартная заставка эфира](previews/mobile-brand-slate.png)

## Debug APK

Файл: `release-local/PeerIche-Mobile-brand-debug.apk`.

Размер: 96 608 157 байт. Подпись: Android Debug, проверка APK успешна. Это тестовая подпись, отличная от подписи опубликованных релизов.

SHA-256: `25cd455226df850098e31b96685008bde73aa8bddcc8dc910287856695ad3a91`.

Логи: `release-local/mobile-brand-verified.log`, `release-local/mobile-brand-test-build.log`.
