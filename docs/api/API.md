# Asteroid Outpost — Server API v1

Спецификация REST API для сервера, с которым взаимодействует Android-клиент Asteroid Outpost. Целевая аудитория — backend-разработчик (или его агент), реализующий сервер.

**Базовый URL:** `https://api.g4.raftforge.art/api/v1`

**Контракт стабильный** — клиент опирается на форму запросов/ответов описанную в этом документе и в сопроводительных `openapi.yaml` + `mission-schema.json`. Любые breaking changes требуют bump'а версии URL (`/v2`).

---

## 1. Общие правила

### 1.1. Транспорт
- **HTTPS только.** TLS 1.2+. Self-signed недопустим (клиент откажется).
- **HTTP/2** желателен (telemetry batches шлются часто, multiplexing полезен), но не обязателен.
- **Content-Type:** `application/json; charset=utf-8` для всех body-несущих запросов и ответов.

### 1.2. Аутентификация

После первого запуска приложение генерирует **device-UUID** (UUID v4, хранится в SharedPreferences) и обменивает его на **device-token** через `POST /auth/device`.

Дальше каждый запрос несёт заголовок:
```
Authorization: Bearer <device-token>
```

Token — opaque строка (server-side это JWT, opaque hash, или DB-row id — клиенту всё равно). TTL рекомендуется ≥30 дней; refresh не реализован в v1 (клиент при 401 заново вызовет `/auth/device` с тем же device-UUID).

Все endpoints кроме `/auth/device` требуют auth. При отсутствии или невалидном токене — `401 Unauthorized`.

### 1.3. Версионирование

- **URL-prefix** `/api/v1`. Несовместимые изменения — `/v2`, оба endpoint'а живут параллельно во время миграции (минимум 90 дней).
- **Schema-versioning у миссий**: поле `schemaVersion: int` в каждом mission JSON. Клиент v1 проверяет — если version > supported, пропускает миссию с warning, не падает.
- **Forward-compat:** клиент игнорирует **unknown fields** в JSON. Сервер может добавлять необязательные поля без bump'а URL-версии. Удаление полей или изменение типа — breaking change.

### 1.4. Формат ошибок

Все 4xx/5xx ответы:
```json
{
  "error": {
    "code": "INVALID_DEVICE_ID",
    "message": "deviceId must be a valid UUID v4 string"
  }
}
```

Стандартные `code` (расширяемый список):
| Code | HTTP | Когда |
|------|------|-------|
| `INVALID_REQUEST` | 400 | Malformed JSON или missing required field |
| `INVALID_DEVICE_ID` | 400 | Неверный формат `deviceId` |
| `INVALID_SCHEMA_VERSION` | 400 | Mission upload с unsupported schemaVersion |
| `UNAUTHORIZED` | 401 | Отсутствующий или невалидный Bearer token |
| `NOT_FOUND` | 404 | Mission/session не существует или не принадлежит этому device-token'у |
| `CONFLICT` | 409 | Concurrent progress modification (см. §4.2) |
| `RATE_LIMITED` | 429 | Слишком частые запросы (header `Retry-After: <sec>`) |
| `INTERNAL_ERROR` | 500 | Server-side unexpected failure |

### 1.5. Заголовки
- `Authorization: Bearer <token>` — все authenticated endpoints
- `X-Client-Version: <appVersion>` — версия Android-клиента (e.g. `1.4.0`), для debug / гейтинга
- `X-Client-Platform: android` — постоянно
- `X-Request-Id: <uuid>` — клиент генерирует, сервер логирует, помогает в support
- Ответы должны включать `X-Request-Id` обратно (echo)

### 1.6. Rate limiting
- **`/telemetry/*`**: до 20 req/sec на token (batches каждые ~250 мс)
- **`/progress`**: до 10 req/sec на token
- **`/missions`, `/missions/{id}`**: до 60 req/min на token (агрессивно кэшируются клиентом)
- Превышение — `429`, `Retry-After` обязателен

---

## 2. Авторизация

### `POST /auth/device`

Регистрирует device-UUID или возвращает токен существующего. **Не требует Authorization header'а.**

**Request:**
```json
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "platform": "android",
  "appVersion": "1.4.0"
}
```

**Response 200:**
```json
{
  "token": "<opaque-token-string>",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "isNewDevice": true,
  "tokenExpiresAt": "2026-06-10T12:00:00Z"
}
```

`isNewDevice` = true если deviceId ранее не видели → сервер создал новую запись и инициализировал пустой progress. False = существующий, токен/прогресс возвращены.

`tokenExpiresAt` — ISO-8601 UTC. Клиент проактивно refresh'ит за ≥1 день до истечения через тот же endpoint.

**Errors:** `400 INVALID_DEVICE_ID`, `500 INTERNAL_ERROR`.

---

## 3. Каталог миссий

### `GET /missions`

Список доступных миссий — короткие записи без полной спецификации.

**Response 200:**
```json
{
  "missions": [
    {
      "id": "campaign-01",
      "schemaVersion": 1,
      "displayName": "Учебная тревога",
      "description": "Две короткие волны...",
      "difficulty": "easy",
      "kind": "wave",
      "category": "campaign",
      "order": 1,
      "updatedAt": "2026-05-01T12:00:00Z"
    },
    {
      "id": "combat-single-interceptor",
      "schemaVersion": 1,
      "displayName": "Бой: одиночный перехватчик",
      "description": "...",
      "difficulty": "medium",
      "kind": "combat",
      "category": "random-event",
      "isRepeatable": true,
      "updatedAt": "2026-05-11T18:00:00Z"
    }
  ],
  "serverTime": "2026-05-11T19:30:00Z"
}
```

**Поля:**
- `id` — стабильный slug, не number. URL-safe `[a-z0-9-]+`. Не меняется после публикации миссии.
- `schemaVersion` — версия mission-schema (см. `mission-schema.json`). Текущая v1.
- `displayName` — UI-имя, не уникально (могут быть локализации в будущем).
- `description` — длинное описание для mission-detail экрана.
- `difficulty` — enum: `"easy" | "medium" | "hard"`.
- `kind` — enum: `"wave" | "route" | "combat"`. Определяет какой game-mode runner запускает.
- `category` — enum: `"campaign" | "random-event"`. Определяет UI-экран: Кампания vs Случайные.
- `order` (optional, для campaign) — порядок в графе кампании (1, 2, 3...).
- `isRepeatable` (optional, default true для random-event, false для campaign) — может ли игрок перепроходить.
- `updatedAt` — ISO-8601, для cache-invalidation.

**Errors:** `401 UNAUTHORIZED`.

### `GET /missions/{id}`

Полная спецификация миссии — то что нужно для запуска runner'а. См. `mission-schema.json` для строгой схемы.

**Response 200:** один MissionConfig объект (см. §6.1).

**Errors:** `401 UNAUTHORIZED`, `404 NOT_FOUND`.

---

## 4. Прогресс

### `GET /progress`

Текущее состояние GameProgress на сервере для этого device-token'а.

**Response 200:**
```json
{
  "metal": 245,
  "mainWeaponDamageLevel": 2,
  "sideTurretDamageLevel": 1,
  "baseHpLevel": 0,
  "highestMissionUnlocked": 3,
  "selectedWeaponId": "machinegun",
  "updatedAt": "2026-05-11T18:32:00Z",
  "revision": 14
}
```

**Поля:**
- Все upgrade-уровни — int, 0..3 (см. `game/UpgradeCatalog.kt` в клиенте).
- `selectedWeaponId` — enum: `"machinegun" | "railgun"`.
- `revision` — монотонно растущий счётчик. См. §4.2 ниже.

### `PUT /progress`

Полная замена прогресса. **Idempotent** — повторный PUT с тем же содержимым → тот же ответ.

**Request:**
```json
{
  "metal": 260,
  "mainWeaponDamageLevel": 2,
  "sideTurretDamageLevel": 1,
  "baseHpLevel": 0,
  "highestMissionUnlocked": 3,
  "selectedWeaponId": "machinegun",
  "revision": 14
}
```

`revision` — это revision, который был у клиента когда он принял решение менять state (e.g., если последний GET вернул revision=14, и пользователь нажал «купить апгрейд» → клиент шлёт revision=14, expected новое = 15).

**Response 200:**
```json
{
  "metal": 260,
  "...": "...",
  "revision": 15,
  "updatedAt": "2026-05-11T19:00:00Z"
}
```

### 4.2. Conflict resolution

Если `request.revision != serverRevision` (например играл на втором устройстве и там progress опередил) → `409 CONFLICT`:

```json
{
  "error": {
    "code": "CONFLICT",
    "message": "Server progress is at revision 16, client sent 14"
  },
  "currentServerState": { /* полный progress на сервере */ }
}
```

Клиент должен показать пользователю диалог «Прогресс на сервере новее — что использовать?» и заново PUT с правильным revision.

**Errors:** `400 INVALID_REQUEST`, `401`, `409 CONFLICT`.

---

## 5. Telemetry

Поток состояния матча — для аналитики и live-monitoring playtesting'а. Состоит из трёх стадий: открытие сессии, batch'и фреймов, закрытие.

### `POST /telemetry/sessions`

Открыть сессию telemetry. Клиент шлёт сразу после `startMission` (если есть network).

**Request:**
```json
{
  "missionId": "combat-single-interceptor",
  "weaponId": "machinegun",
  "startedAt": "2026-05-11T19:00:00Z",
  "appVersion": "1.4.0",
  "missionSchemaVersion": 1
}
```

**Response 200:**
```json
{
  "sessionId": "9b2c-...-uuid",
  "frameBatchMaxSize": 256,
  "frameIntervalMs": 100
}
```

`frameBatchMaxSize` — сколько frames клиенту резрешено слать в одном POST (default 256, server может уменьшить).
`frameIntervalMs` — рекомендованный интервал отправки batch'ей (default 1000 мс, т.е. 1 batch / сек).

### `POST /telemetry/sessions/{sessionId}/frames`

Batch фреймов. Клиент копит локально и шлёт раз в `frameIntervalMs` (или когда буфер достиг `frameBatchMaxSize`).

**Request:**
```json
{
  "frames": [
    {
      "ts": 1715451600234,
      "shipPosY": 42.3,
      "shieldHp": 480,
      "platformHp": 130,
      "energy": 75,
      "score": 240,
      "asteroids": [
        {"id": 17, "type": "FAST", "x": 1.2, "y": 50.4, "z": 5.1, "hp": 80, "maxHp": 100},
        {"id": 19, "type": "HEAVY", "x": -0.5, "y": 48.0, "z": 3.0, "hp": 300, "maxHp": 300}
      ],
      "enemies": [
        {"id": 21, "x": 0.0, "y": 62.3, "z": 3.5, "hp": 700, "maxHp": 800, "shieldHp": 200, "shieldHpMax": 400}
      ],
      "abilityCooldowns": [0.0, 4.5, 12.0],
      "activeBuffSecLeft": 0.0,
      "playerPriorityAsteroidId": 21
    }
  ]
}
```

**Поля frame:**
- `ts` — Unix epoch milliseconds (client clock, не гарантирует sync с сервером — server должен это понимать).
- `shipPosY` — float, мировая позиция корабля.
- `shieldHp`, `platformHp`, `energy`, `score` — целые числа состояния.
- `asteroids[]` — все live астероиды (`hp > 0`). Включая ENEMY_SHIP-типы.
- `enemies[]` — отдельный список с дополнительными полями shield (для удобства viewer'а). Дублирует ENEMY_SHIP записи из asteroids — оба представления валидны, server использует по вкусу.
- `abilityCooldowns[]` — массив remaining cooldown в секундах для каждого ability slot'а. Длина соответствует `AbilitySlot` count (сейчас 3).
- `activeBuffSecLeft` — оставшееся время ENERGY-buff в секундах.
- `playerPriorityAsteroidId` (nullable) — id priority-locked астероида.

**Response 200:**
```json
{
  "accepted": 10,
  "rejected": 0
}
```

**Errors:** `400 INVALID_REQUEST`, `401`, `404 NOT_FOUND` (sessionId не существует), `429 RATE_LIMITED`.

### `POST /telemetry/sessions/{sessionId}/close`

Завершить сессию с итогом.

**Request:**
```json
{
  "endedAt": "2026-05-11T19:02:30Z",
  "outcome": "win",
  "score": 1240,
  "metalEarned": 35,
  "asteroidsDestroyed": 18,
  "wavesCompleted": 2,
  "reason": "enemy_killed"
}
```

`outcome` enum: `"win" | "lose" | "abort"`.
`reason` (optional, free-form string) — для аналитики: `"enemy_killed"`, `"base_destroyed"`, `"player_abort"`, etc.

**Response 200:**
```json
{
  "sessionId": "9b2c-...",
  "framesReceived": 1530,
  "durationSec": 153
}
```

**Errors:** `401`, `404 NOT_FOUND`.

### 5.1. Telemetry — нефункциональные требования

- Frame payload ~2-5 KB JSON. С 30 asteroids — до 8 KB. Сервер должен принимать batch'ами до 256 frames = ~2 MB. Compression (gzip) опционален но желателен.
- Storage: append-only, не нужны транзакции. JSONB column в Postgres или просто bulk-insert в любой store.
- Retention: 30 дней default, сервер может настроить.
- **Игра НЕ ждёт ответа** — это fire-and-forget. Failure (500) → клиент логирует и дропает batch. Не должно прерывать gameplay.

---

## 6. Schema reference

### 6.1. Mission JSON

Полная схема — `mission-schema.json` (JSON Schema draft-07). Краткое описание полей:

```json
{
  "id": "combat-three-interceptors",
  "schemaVersion": 1,
  "displayName": "Бой: три перехватчика",
  "description": "...",
  "difficulty": "hard",
  "kind": "combat",
  "category": "random-event",
  "order": null,
  "isRepeatable": true,
  
  "baseHp": 160,
  "weaponsDisabled": false,
  
  "asteroidBaseline": {
    "hp": 150,
    "speed": 0.0
  },
  
  "waves": [],
  "route": null,
  "enemyShipSpawns": [
    {"delaySec": 10.0, "xOffset": -2.0},
    {"delaySec": 13.0, "xOffset":  0.0},
    {"delaySec": 16.0, "xOffset":  2.0}
  ],
  
  "updatedAt": "2026-05-11T18:00:00Z"
}
```

**Wave** (когда `kind == "wave"`):
```json
{
  "asteroidCount": 7,
  "spawnIntervalSec": 1.2,
  "typeWeights": {
    "NORMAL": 0.7,
    "FAST": 0.2,
    "HEAVY": 0.1
  }
}
```

**Route** (когда `kind == "route"`):
```json
{
  "startY": 70.0,
  "endY": 215.0,
  "asteroids": [
    {
      "absY": 75.0, "x": -1.5, "z": 2.0,
      "type": "NORMAL",
      "hpOverride": null
    }
  ]
}
```

**EnemyShipSpawn** (когда `kind == "combat"`):
```json
{"delaySec": 10.0, "xOffset": 0.0}
```

Все три массива могут сосуществовать. Например combat-миссия может иметь и `waves` и `enemyShipSpawns` — runner отработает оба independently.

### 6.2. AsteroidType enum

```
"NORMAL" | "FAST" | "HEAVY" | "EXPLOSIVE" | "ENERGY" | "ENEMY_SHIP"
```

ENEMY_SHIP — special-type, не используется в `waves`/`route`, только клиент-side через enemyShipSpawns.

### 6.3. Mission category routing на UI

- `"campaign"` — попадает в Campaign graph экран. Order определяет позицию.
- `"random-event"` — попадает в Random Missions tab.

---

## 7. Implementation notes для server team

### 7.1. Хранилище
- Любая БД: SQLite (single-instance), Postgres (production), DynamoDB — на выбор. Контракт API не зависит.
- Telemetry frames рекомендуется в отдельную таблицу/коллекцию от GameProgress — разные паттерны доступа.
- Mission JSON может храниться как BLOB или в специализированной structured storage. Для редактирования через будущий editor — структурированно лучше.

### 7.2. Initial seed
- На первом запуске сервера каталог миссий пустой. Добавляйте миссии через DB-fixture или admin-tool.
- Чтобы dev-loop работал — рекомендую первым делом загрузить 8 миссий что сейчас захардкожены в клиенте (`Missions.ALL` в `app/src/main/java/com/example/asteroidoutpost/game/Missions.kt`). Клиент пришлёт их форму после wire-up.

### 7.3. CORS
- Mobile-клиент CORS не требует, но если будет web-editor — поддержите `Access-Control-Allow-Origin` для editor-домена.

### 7.4. Healthcheck
- `GET /api/v1/health` — публичный (без auth), возвращает `{"status": "ok", "serverTime": "..."}`. Используется клиентом для проверки connectivity перед первым sync.

### 7.5. Логирование
- Логируйте `X-Request-Id` в каждом lookup'е — позволяет cross-reference debugging между клиентом и сервером.
- Чувствительные данные (token, deviceId) — не в plain text логах, hash'ируйте.

---

## 8. Что НЕ в v1 (deferred)

- **Email/password account.** Сейчас только device-token. Привязка к аккаунту — отдельный flow позже.
- **Mission upload через API.** Сейчас миссии загружаются server-side (через DB или editor). API для upload — позже.
- **Push-notifications.** Нет server→client инициированных событий.
- **WebSocket / SSE для telemetry.** HTTP POST batches достаточно. Если нужен realtime live-view — отдельная итерация.
- **Multi-player.** Игра single-player. Серверы могут разнести по shards для масштаба, но без cross-player интеракции.
- **Friend list / social.** Out of scope.
- **In-app purchase.** Out of scope.

---

## 9. Open questions для обсуждения с client team

Список вопросов которые могут уточниться по ходу реализации:

1. **Кэширование на CDN?** Если миссии редко меняются — `GET /missions` можно отдавать с `Cache-Control: max-age=300`. Аналогично `GET /missions/{id}` (если миссия не редактируется — `ETag` + `If-None-Match`).
2. **Прогресс на гостевом аккаунте.** Сейчас device-token — единственный identity. Если пользователь сбрасывает приложение → теряет progress. Future: migrate progress к email-account.
3. **Telemetry — opt-in?** GDPR-friendly mode: пользователь может отключить через настройки. Клиент тогда просто не вызывает `/telemetry/*`. Сервер ничего не делает специально.
4. **Schema migration tooling.** Когда mission-schema-v2 выходит — как мигрируем существующие missions v1 → v2? Server-side скрипт + bump schemaVersion полей? Или хранить как есть и читать legacy на лету?

Эти вопросы не блокируют v1 implementation — отвечаем по ходу.
