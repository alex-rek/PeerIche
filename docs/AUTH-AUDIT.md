# Аудит и реализация авторизации PeerIche

## До изменений

- Kotlin 2.0.21, minSdk 23, compile/target SDK 35, Compose 1.7.6, Material3 1.3.1, Media3 1.5.1.
- `PeerTubeClients` создаёт публичные Retrofit-клиенты по адресам серверов; заголовков авторизации нет. Поиск, видео и федеративные ссылки могут обращаться к разным доменам.
- `YouPage` содержит подготовленный блок входа; аккаунты и сессии не сохранялись.
- Подписки, избранное и история находятся в локальной Room-базе. `AppPreferences` хранит обычные настройки в SharedPreferences, список серверов — DataStore.
- Действующие плеер, TTS и перевод не требуют изменения для входа в аккаунт.

## Точки интеграции

- Отдельный `PeerTubeAuthTransport` использует стандартные `GET /api/v1/oauth-clients/local`, `POST /api/v1/users/token`, `GET /api/v1/users/me`, `POST /api/v1/users/revoke-token`.
- Форма password grant, обновление refresh grant, заголовок `x-peertube-otp`, распознавание кодов ошибок и старого сообщения об отсутствии OTP. Профиль проверяется до сохранения аккаунта.
- Публичные клиенты не получают токены. Приватный клиент не следует HTTP-перенаправлениям и принимает только HTTPS origin без данных входа, пути, query и fragment. Токен передаётся в заголовке, не в URL.
- `AccountSessionManager`: несколько аккаунтов, активный аккаунт, сериализация изменений, обновление перед истечением и один повтор при 401, сохранение вращающегося refresh-токена до следующего запроса, повторный вход после отзыва. Выход сначала удаляет локальный вход, затем ограниченно по времени пытается отозвать серверную сессию.
- `EncryptedAccountStore`: AES-256-GCM, случайный IV, ключ Android Keystore, атомарный файл в `noBackupFilesDir`. Пароль и OTP не записываются. UI получает только сведения профиля, без токенов. Повреждённое хранилище не перезаписывается молча.
- `AccountScreen`: ввод сервера/имени/пароля/OTP, добавление и переключение аккаунтов, проверка сессии, выход и восстановление после ошибки хранилища. Окно с паролем использует `FLAG_SECURE`; пароль и OTP не входят в saved instance state.
- После явного выбора аккаунта его сервер добавляется к списку и становится текущим. Ручное переключение источника видео не переносит токен на другой сервер.

## Границы этапа

Серверные подписки, синхронизация локальной библиотеки, публикация комментариев/оценок/видео, регистрация и внешние SSO-плагины пока не подключены. Нет новых зависимостей и изменений package/applicationId. Локальная база, плеер и перевод сохранены.

Документация: [PeerTube OAuth quick start](https://docs.joinpeertube.org/api/rest-getting-started), [API Login/Logout](https://docs.joinpeertube.org/api-rest-reference.html#tag/Session).

## Изменённые файлы

- `app/src/main/java/com/arcticfoxapps/peeriche/PeerTubeAuth.kt` — модели, HTTPS origin, HTTP OAuth-клиент и разбор ошибок.
- `app/src/main/java/com/arcticfoxapps/peeriche/AccountSessionManager.kt` — жизненный цикл сессий и несколько аккаунтов.
- `app/src/main/java/com/arcticfoxapps/peeriche/EncryptedAccountStore.kt` — Keystore, AES-GCM и AtomicFile.
- `app/src/main/java/com/arcticfoxapps/peeriche/AccountScreen.kt` — интерфейс входа и управления аккаунтами.
- `app/src/main/java/com/arcticfoxapps/peeriche/YouPage.kt`, `MainActivity.kt` — профиль и выбор сервера аккаунта.
- `app/src/test/java/com/arcticfoxapps/peeriche/AccountSessionManagerTest.kt`, `PeerTubeAuthTest.kt` — модульные проверки.
- `app/src/androidTest/java/com/arcticfoxapps/peeriche/AuthSmokeInstrumentation.kt` — изолированная проверка Android Keystore и файлового хранения.
- `app/build.gradle.kts`, `README.md`, `docs/AUTH-AUDIT.md`, `docs/RELEASE-v0.6.0.md` — версия, тестовый runner и документация.

## Результаты проверок

`assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintRelease` завершены успешно. 79 модульных тестов, 0 ошибок lint (72 предупреждения).

В отдельном эмуляторе API 37 выполнено:

```text
adb -s emulator-5556 shell am instrument -w com.arcticfoxapps.peeriche.test/com.arcticfoxapps.peeriche.AuthSmokeInstrumentation
PASS: Android Keystore AES-GCM, encrypted persistence, restore, refresh rotation,
tamper rejection, AtomicFile backup recovery, logout.
```

Тест использует вымышленные аккаунт/токены, уникальные временный файл и alias ключа, удаляет их после завершения и не изменяет основное хранилище входов. Проверены русскоязычные формы на телефоне и планшете, поле OTP, `FLAG_SECURE`, очистка пароля при повторном открытии и смене темы, отклонение HTTP-адреса до сетевого запроса. AndroidRuntime без падений. Публичный OAuth endpoint `peertube.tv` возвращает необходимые параметры клиента.

Сценарии успешного входа/обновления/отзыва протокола проверены контролируемыми ответами в автоматических тестах. Реальная учётная запись не использовалась: окончательная проверка входа на сервер пользователя и его политики 2FA/плагинов требует ввода данных в самом приложении. На физических устройствах и Android 6–15 отдельно не проверялось.
