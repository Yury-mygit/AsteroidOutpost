# Asteroid Outpost — Концепция и состояние

> Живой документ. Обновляй после каждой значимой сессии.
> Последнее обновление: **2026-05-09** (большой день. M10 UI/щит-полировка: иконки на ability-buttons (V-щит, ракета, лазер-режет-астероид), щит-кнопка с green/gray HP-fill вертикальной полосой, action-bar 32dp icon-only, форма щита-арки = superellipse n=4 (плоский верх, резкие плечи) с подъёмом 5%, новая иконка приложения (заменяет g3-зелёную). M11 процедурные платформенные меши: турели расщеплены на статичную базу + вращающуюся башню (`buildTurretBaseMesh` + `buildTurretBarrelMesh`), лазерный купол (ground-telescope-style) и ракетная шахта (open-hatch with launch tube) рядом с центральной турелью. M12 непрерывный лазер (5 сек, 50 dps, blocks-on-first-asteroid) заменяет старый drag-line strike; полностью выпилен legacy strike + armed-target framework. **E13** plasma billboard матрица rotation+non-uniform-scale fix — добавлен `Mat4::scale`, переписана композиция в `VulkanContext::renderFrame` plasma-секции на `billboard_uniform × Ry(rot) × S(scaleH,1,scaleV)`. **E14** dedicated `drawLaserBeam` API — новый Vulkan pipeline `m_beamPipeline` с собственным layout, `beam.vert/.frag` GLSL шейдеры, GPU-side view-aligned quad expansion, sharp endpoints, gentle pulse. Через C/JNI/Kotlin → `BeamDraw` data class + `EngineView.beams`. M13 WeaponEffect umbrella — refactor `bullets: List<Bullet>` → `effects: List<WeaponEffect>`, единый `tick(dt) → consumed`-loop. Конкретные классы: `Projectile` (с `behaviour: ProjectileBehavior` strategy), `Beam` (с closure source/aim для g3-портабельности). M14 ракетный overhaul: процедурный меш (`buildRocketMesh`: engine bell + body + 2 fins + nose + warning stripe), spring-launch sequence (queue в RocketSilo, ASCENDING фаза straight-up, ASCENT_HEIGHT=2×length, FLYING фаза = boost+steer), 3-фазный VFX (no-engine во время spring → яркий ignition flash на переходе → реактивный jet + smoke trail в FLYING). M15 per-weapon firing arcs (90/80/80/70/95/95) + priority-lock semantics (master target для центра+лазера, re-tap toggles release, центр клампит вращение в свою дугу но всё равно ведёт цель, лазер фолловит того же таргета через canEngage closure в Beam). Также: астероиды разбиваются о щит-арку через superellipse Z(x), не долетая до базы; -20% урона по щиту во время recharge + cyan tangential искры по дуге. M9 ↑ выше: M8 + M9 геймплейный pivot. Центральная турель больше не управляется вручную — auto-aim с sticky lock на самую "толстую" цель + tap-to-priority. HP-bars над повреждёнными астероидами. Энергия как ресурс под способности (100 max, +10/sec). Две способности: Ракетный залп (3 homing missiles, 30 энергии, cd 8с) + Лазерный удар (drag-line, 5 lightning bolts, 50 энергии, cd 18с). Ability framework — `game/Ability.kt` + `AbilityCatalog`, runtime в `AbilitySlot`. UI: ability bar рядом с щитом. Threading — все mutate-list ability activations marshalled на mission thread через `missionHandler.post`. Mission counts ×1.5–2 + интервалы спавна тактнее. M9 — щит переработан: убран ShieldState (READY/ACTIVE/COOLING), теперь permanent HP-based barrier (max 500). Урон астероидов идёт сначала в щит, overflow на платформу. Кнопка ЩИТ — hold-to-recharge: 50 энергии/сек → 200 HP/сек (4× ratio). Гео — `buildShieldArchMesh` строит wide flat ellipse arch с вершинами в мировых координатах (constant-thickness band, никакой anisotropy от scaleX≠scaleZ). Раньше: E12 railgun muzzle lightning + concept rename Тяжёлая пушка→Рельсотрон. E11 — drawPlasmaBillboard(rotation) + cone-trefoil muzzle blast + side turrets cannon-style. E10.4 motion blur post.frag.)

## Концепция

Аркадный 2D-шутер «снизу-вверх» в портретной ориентации.

В центре широкой **серой платформы** внизу экрана стоит **главная боевая турель** (красный высокий прямоугольник, ~3× выше боковых). С M8 ствол **наводится автоматически** на самую опасную цель (наибольший текущий HP, tiebreak по дистанции до пивота) и стреляет с интервалом своего оружия. Игрок управляет приоритетом тапом по астероиду — тогда турель добивает выбранную цель, после чего возвращается к auto-pick. Sticky lock: единожды захваченная цель не теряется на каждом кадре из-за колебания HP — турель добивает.

Игрок управляет **способностями и щитом** базы, не прицелом. Над повреждёнными астероидами висят зелёные HP-bar'ы — глянцевая обратная связь, как они ослаблены.

На платформе по бокам — **две синие квадратные турели поддержки**. Они стационарны, автоматически наводятся на ближайший астероид и стреляют под углом cannon-style (1 выстрел/сек, ×3 урона, AoE 0.5).

Сверху случайным образом спавнятся **астероиды разных типов** (NORMAL/FAST/HEAVY/EXPLOSIVE/ENERGY), медленно падают вниз. Пули наносят им урон, при HP ≤ 0 астероид уничтожается → +10 очков и +1 металла. Если астероид долетел до платформы — сначала бьёт по щиту, потом (если щит пробит или сломан) по платформе.

### Главное оружие игрока

Перед миссией игрок выбирает оружие центральной турели:

- **Автомат** — стиль «стабильный контроль потока»: частая стрельба, низкий урон за выстрел, тёплый cone-trefoil muzzle blast. Хорош против быстрых и обычных целей.
- **Рельсотрон** (electromagnetic launcher) — стиль «редкие сильные решения»: 1 выстрел/сек, ×3 урон, AoE-сплеш по соседним астероидам. **Визуально читается как electromagnetic launcher**: при выстреле — яркое cyan-white ядро в дуле + 5-7 случайных electric arc разрядов между «рельсами» (процедурные lightning bolts через E12 plasma sub-shader) + cyan искры. Хорош против групп и крупных целей.

С M8 оба оружия стреляют автоматически по выбранной цели — выбор оружия становится выбором «непрерывного DPS» vs «редкие тяжёлые удары».

### Энергия и активные способности (M8)

`energy ∈ [0, 100]`, регенерация **+10/сек**. HUD показывает `⚡ N/100` под HP. Расходуется на способности и подзарядку щита.

- **Ракетный залп** (30 энергии, cd 8с): 3 самонаводящихся снаряда из дула центральной турели по top-3 самым опасным астероидам. ×4 урон от main-weapon, AoE 0.4 / 60% сплеш. Если астероидов нет — рефанд (нет spend).
- **Лазерный удар** (50 энергии, cd 18с, targeted): тап по кнопке → arm. Drag по экрану → линия. На ACTION_UP: 80 урона всем астероидам, чьё bounding-circle пересекается с сегментом (point-line distance ≤ `a.half + LASER_HIT_PAD`). VFX — 5 параллельных lightning bolts через E12 sub-shader.

Каркас в `game/Ability.kt` (`Ability` data class + `AbilityCatalog` + `AbilityId`); runtime cd в `MainActivity.AbilitySlot`. Кнопки сидят в горизонтальном `abilityBar` рядом с щитом.

### Щит (M9)

Постоянный HP-барьер вместо on/off-режима. `shieldHp` стартует с **500**, поглощает урон астероидов до основания. Геометрия — широкая плоская дуга через всю платформу (`buildShieldArchMesh()`, вершины в мировых координатах для constant-thickness band).

Кнопка ЩИТ — **hold-to-recharge**: пока нажата, тратит 50 энергии/сек, восстанавливает 200 HP/сек (4× ratio). Из полной полоски энергии = +400 HP щита. Когда `shieldHp == 0`, щит «сломан» — урон астероидов идёт в платформу, пока игрок не подзарядит.

### Структура миссий

Каждая миссия — это набор **волн**. Волна спавнит N астероидов с заданным интервалом, заканчивается когда все астероиды уничтожены или коснулись базы. Между волнами 2 секунды паузы. Победа — все волны пройдены. Поражение — HP платформы ≤ 0.

Кампания из 5 миссий, каждая знакомит игрока с одной новой механикой. Числа подняты ×1.5–2 в M8 под новую огневую мощь способностей:

| № | Название           | Сложность | Волн × астер. | HP а. | Скор. | База | Что нового                           |
|---|--------------------|-----------|----------------|-------|-------|------|--------------------------------------|
| 1 | Учебная тревога    | Лёгкая    | 2 × 7          | 50    | 0.8   | 100  | auto-aim + tap-priority              |
| 2 | Быстрые цели       | Лёгкая    | 10/12/14       | 60    | 1.0   | 100  | FAST — мелкие быстрые                |
| 3 | Тяжёлая угроза     | Средняя   | 12/14/16       | 80    | 1.0   | 110  | HEAVY + первое полезное окно щита    |
| 4 | Взрывная цепочка   | Средняя   | 14/16/18       | 100   | 1.1   | 110  | EXPLOSIVE-комбо                      |
| 5 | Проверка базы      | Высокая   | 18/20/22/24    | 120   | 1.4   | 130  | все типы вместе, максимум давления   |

Балансные числа и распределения типов астероидов по волнам — в `app/src/main/java/com/example/asteroidoutpost/game/Missions.kt`.

### Мета-прогрессия

После каждого боя игрок получает **металл**: +1 за уничтоженный астероид, +20 бонус за победу. Металл сохраняется между запусками. Тратится на экране **«Улучшения»** на трёх ветках:

| Улучшение            | Уровень 1 → 2 → 3 значения | Цены (металл) |
|----------------------|------------------------------|---------------|
| Урон главного оружия | 10 → 15 → 22                 | 20 / 40 / 80  |
| Прочность базы       | +0 → +50 → +120 к HP миссии  | 25 / 50 / 100 |
| Урон боковых турелей | 5 → 8 → 12                   | 20 / 40 / 80  |

Числа — в `app/src/main/java/com/example/asteroidoutpost/game/UpgradeCatalog.kt`.

### Цикл экранов

```
Меню (Asteroid Outpost / Всего металла: N / [Играть] [Улучшения])
  │
  ├─ [Играть] → Выбор миссии (3 карточки + [Назад])
  │              │
  │              └─ [Старт] → Игра (HUD: Score / HP / Волна X/Y)
  │                            │
  │                            ├─ Победа → «Миссия выполнена»
  │                            │            (стата: убито астероидов / металл / бонус)
  │                            │            [Следующая миссия] [Повторить]
  │                            │            [Улучшения] [К выбору миссий]
  │                            │
  │                            └─ Поражение → «База разрушена»
  │                                          (стата: пройдено волн / металл)
  │                                          [Повторить миссию] [Улучшения]
  │                                          [К выбору миссий]
  │
  └─ [Улучшения] → 3 карточки (текущий уровень / эффект / цена / [Улучшить])
                   [Назад] возвращает на тот экран, откуда пришли
```

## Сделано

Прототип играбелен и закрыт по концепции «робот на платформе + волны астероидов + апгрейды». Реализовано:

**Гейплей и движок:**
- [x] Side-view камера (фиксированная, без управления)
- [x] Геометрия: единый quad-меш + 3D `Asteroid_1.glb` для астероидов с осевым вращением
- [x] **Центральная турель** — главное оружие игрока (M1)
- [x] **Ручное прицеливание** касанием: touch X+Z → угол ствола, плавное сглаживание (M1)
- [x] **Hold-to-fire** центральной турели в направлении прицела (M1)
- [x] **Абстракция оружия** (`Weapon` + `WeaponCatalog`) — Автомат и Рельсотрон (M2; «Тяжёлая пушка» концептуально переименована в Рельсотрон в E12 — electromagnetic launcher с разрядами тока на стволе)
- [x] **AoE при попадании** для рельсотрона + крупная вспышка-плейсхолдер (M2)
- [x] **Экран выбора оружия** перед стартом миссии (M2)
- [x] **Активная способность «Щит базы»** — state machine, кнопка снизу, поглощение урона; визуал — additive plasma-купол поверх базы с пульсацией и фейдом-схлопыванием (M3 placeholder → M7)
- [x] **Переименование меты под новый бой** — `UpgradeCatalog`/`GameProgress`/SharedPreferences под главное оружие + боковые турели; полный сброс через `outpost_progress_v2` (M4)
- [x] **Типы астероидов** (NORMAL/FAST/HEAVY/EXPLOSIVE/ENERGY) с per-type параметрами и тинтами (M5)
- [x] **Buff-система** (один слот, таймер) + ENERGY-астероид → ×2 урон главного оружия 5 сек + HUD-индикатор (M5)
- [x] **Скроллинг** в карточных overlay-экранах (выбор миссии / выбор оружия / улучшения)
- [x] **Кампания из 5 миссий** — каждая обучает одной механике (M6)
- [x] Две стационарные боковые турели с автоприцеливанием на ближайший астероид
- [x] Стрельба под углом, ориентация пуль по вектору скорости
- [x] Спавн астероидов сверху с разными параметрами по миссиям
- [x] Урон пуля → астероид (центральная турель vs боковые — разный damage), астероид → платформа

**Контент и прогрессия:**
- [x] Волновая структура миссий (3 миссии с разными числами)
- [x] Стартовое меню / экран выбора миссии / экран победы / экран поражения
- [x] Полный набор кнопок навигации между экранами после миссии
- [x] HUD: единая sci-fi панель сверху (миссия / волна / Score / HP)
- [x] Ресурс «Металл» с начислением и сохранением (`SharedPreferences`)
- [x] Экран улучшений с 3 ветками × 3 уровнями
- [x] Применение улучшений в следующей миссии

**Стиль и обратная связь:**
- [x] Единый sci-fi стиль UI (`UiTheme` + `UiHelpers`): тёмные панели, скруглённые углы, единая палитра, типографика
- [x] Карточки миссий с цветным pill сложности и наградой
- [x] Карточки апгрейдов с иконкой-плейсхолдером, переходом значений и подсветкой стоимости
- [x] Стат-таблица на экранах победы/поражения, цветовые акценты по результату
- [x] Анимация «Волна N» / «Финальная волна» при старте каждой волны
- [x] Желтая вспышка при уничтожении астероида
- [x] Красный пульс HP-счётчика при уроне базе

**Тех. долг:**
- [x] Ребрендинг пакета `com.example.g3` → `com.example.asteroidoutpost`

## План доработок (idea.txt → активные вехи)

Источник — `idea.txt`. Сдвиг от пассивного «робот стреляет вверх» к активному «игрок целится и стреляет центральной турелью + использует щит». 7 вех, 12 подзадач.

| M  | Веха                                  | Подзадачи (idea.txt) | Статус       |
|----|---------------------------------------|----------------------|--------------|
| M1 | Новое ядро управления                 | tasks 1, 2, 3, 4     | ✅ **Готово** (2026-05-04) |
| M2 | Оружейная система                     | tasks 5, 8, 9        | ✅ **Готово** (2026-05-04) |
| M3 | Активная способность «Щит базы»       | task 6               | ✅ **Готово** (2026-05-04) |
| M4 | Мета-прогресс под новые ветки         | task 7               | ✅ **Готово** (2026-05-04) |
| M5 | Типы астероидов + buff-система        | task 10              | ✅ **Готово** (2026-05-04) |
| M6 | Кампания: 5 новых миссий              | task 11              | ✅ **Готово** (2026-05-04) |
| M7 | Полировочный VFX-проход               | task 12              | 🟡 в работе (купол + турель/пушка готовы 2026-05-04; cooldown-индикатор — опционально) |
| M7.2 | Контентный апгрейд: модели астероидов и пуль | content swap     | ✅ **Готово** (2026-05-05) — 5 разных `.glb` астероидов per-type + `Bullet.glb`/`Bullet_Heavy.glb` вместо red-quad; aim-alignment fire gate; GltfLoader сливает multi-primitive меши |
| E1 | Движок: alpha + RGBA + процедурные меши | engine wave         | ✅ **Готово** (2026-05-04) — нéбулы вместо градиента, translucent pipeline, `load_mesh_raw` |
| E2.1 | Движок: радиальный soft-fade на plasma-биллбордах | engine wave    | ✅ **Готово** (2026-05-05) — vLocalXZ во vertex-шейдере, fade в fragment по флагу `pc.tint.x`, вспышки переведены на plasma |
| E2.2 | Купол щита через процедурную half-membrane    | engine wave        | ✅ **Готово** (2026-05-05) — заменили стэк plasma-биллбордов на одну annular half-membrane mesh через translucent pipeline (Fresnel-имитация, прозрачный интерьер) |
| E3.1 | Material plumbing для translucent draws       | engine wave        | ✅ **Готово** (2026-05-05) — `int material` параметр через C → JNI → Kotlin; флаги в `pc.tint.y/z` |
| E3.2 | FBM-нéбулы (procedural cloud noise)           | engine wave        | ✅ **Готово** (2026-05-05) — domain-warped 4-octave value-noise по `vWorldPos.xz`, нéбулы стали wispy облаками |
| E3.3 | Hex-щит (procedural hex grid)                 | engine wave        | ✅ **Готово** (2026-05-05) — мягкий hex pattern по `vLocalXZ` поверх filled half-disk dome, силовое поле вместо просто кольца |
| E4 | Plasma flash polish (огонь вместо квадратов)  | engine wave        | ✅ **Готово** (2026-05-05) — premultiply alpha (фиксит soft-fade no-op на ONE/ONE blend), heat-ramp по `vLocalXZ`, FBM-турбулентность по `vWorldPos.xz` |
| E5.1 | Per-billboard plasma tint (RGBA через push-constant) | engine wave   | ✅ **Готово** (2026-05-05) — `drawPlasmaBillboard(...,r,g,b,a)`, `pc.plasmaColor` в шейдере, 6 per-event тинтов: muzzle/trail/explosion/energy/death/shield |
| E5.2 | Non-uniform billboard scale + billboardMatrix fix | engine wave   | ✅ **Готово** (2026-05-05) — диагностировали баг матрицы (X-Z квад мапился в горизонтальную плоскость, не на экран); исправили col 1↔2 swap в `Camera::billboardMatrix`; добавили `scaleH, scaleV` в C API. Вспышки теперь true circles, не horizontal stripes — ретроспективно фиксит intended behaviour E2.1 soft-fade и E4 heat-ramp. |
| E6 | Time push-constant (animated FBM)             | engine wave        | ✅ **Готово** (2026-05-05) — `float time` в `PushConstantData` (100 байт), `m_renderStart` baseline в `VulkanContext`, elapsed seconds пишется в каждый push-constant. Plasma turbulence warpает по времени (огонь шевелится), нéбулы дрейфят медленным потоком. |
| E7 | Additive Mesh Pipeline (3D огненные шары / лазеры) | engine wave   | ✅ **Готово** (2026-05-06) — 7-й Vulkan pipeline (`m_additivePipeline`) с ONE/ONE blend для произвольных 3D мешей, depth-test on read-only / depth-write off. C API/JNI/Kotlin route + Scene `additiveObjects` параллельно `translucentObjects`. Plain-additive ветка во фрагменте под флаг `pc.tint.w`. Разблокирует настоящие 3D fireball'ы, плазменные лучи лазеров, электроразряды. |
| E7.1 | 3D Fireball (первый consumer E7)            | engine wave        | ✅ **Готово** (2026-05-06) — процедурная UV-сфера через `loadMeshRaw`, fire-material шейдер branch (`abs(vNormal.y)` Fresnel + heat-ramp + animated FBM), runtime `Fireball` data class заменил плоские плазма-биллборды у AoE-взрывов. Polish: ease-out quad scale, лерп цвета orange→red, sqrt brightness fade. |
| E8 | UV + textures                                 | engine wave        | ✅ **Готово** (2026-05-07) — vertex UV attribute (location 3) + descriptor set 1 (combined image sampler) + `Texture` C++ class + 4 API: `load_texture(png_bytes)`, `load_texture_raw(rgba8, w, h)`, `load_mesh_raw_uv` (12 floats/vertex), `draw_textured_mesh`. Текстурированный fragment branch под `pc.textureMode` флаг. Verified через два procedural smoke-test patches (rock noise + cyan icon disc), затем patches удалены. Разблокирует sprite-атласы, текстуры на астероидах, иконки в HUD, decals. |
| E9 | Native particle system                        | engine wave        | ✅ **Готово** (2026-05-07) — 2 pipelines (additive ONE/ONE для sparks/embers + alpha-textured SRC_ALPHA для smoke/debris); particle.vert/.frag с per-instance binding 1 (8 floats: pos3+size1+rgba4); 2 instance VkBuffer (4096 particles each, persistent-mapped); `drawParticles` API через C/JNI/Kotlin одним batched вызовом per pool; Kotlin Particle pool + tick + spawn helpers; 3 consumers: AoE sparks (50-70 на event), asteroid death debris+smoke (4-8 chunks с gravity + 3-5 puffs), muzzle micro-sparks (3-5 на каждый выстрел в 40°-конусе). |
| E10.1 | Motion blur: offscreen render + post pass  | engine wave        | ✅ **Готово** (2026-05-07) — render flow перестроен с direct-to-swapchain на scene→offscreen→post→swapchain. `RenderResources` расширен offscreen colour image + sampler + post pass + post framebuffers. Один shared scene framebuffer, post fbs per swapchain image. Post pipeline = fullscreen-triangle через `gl_VertexIndex`, samples offscreen, текущий fragment passthrough (motion blur — E10.4). Visually идентично pre-E10. |
| E10.2 | Motion blur: velocity attachment infrastructure | engine wave | ✅ **Готово** (2026-05-07) — второй color attachment `R16G16_SFLOAT` во scene pass + scene framebuffer (3 attachments). Все 9 scene-пайплайнов получили 2-й `VkPipelineColorBlendAttachmentState` (no-blend, write-mask R+G). `triangle.frag` + `particle.frag` объявили `layout(location=1) out vec2 outVelocity` (placeholder zero — реальный compute в E10.3). Post descriptor set расширен до 2 bindings (sceneColor + sceneVelocity), post.frag всё ещё passthrough. Visually идентично pre-E10.2. |
| E10.3 | Motion blur: prev-frame matrices + per-object velocity | engine wave | ✅ **Готово** (2026-05-07) — UBO расширен до 4 mat4 (view, proj, prev_view, prev_proj). Per-draw dynamic UBO (set 2) держит prev_model per draw call (4096 слотов, sentinel identity at slot 0 для billboard/particle/frame draws). `triangle.vert` считает `vVelocity = (currClip.xy/w - prevClip.xy/w) * 0.5` через все 3 матрицы; `triangle.frag` пишет в outVelocity (frame / plasma branches force zero). 5 mesh-style draw API получили optional `prevModelMatrix`. Asteroid/Bullet/Fireball трекают prevX/Z/rotation/Life в Kotlin. Visually идентично — реальная velocity видна только под RenderDoc до E10.4. |
| E10.4 | Motion blur: post.frag shader (dilation + weighted blur) | engine wave | ✅ **Готово** (2026-05-07) — `post.frag` теперь 5×5 velocity dilation + weighted 8-tap blur + length-clamp (kMaxBlur=0.05, kIntensity=1.5, kStaticWeight=0.2). Static fast path для пикселей с velocity ≈ 0. Overlay clobber fix: 8 non-opaque pipelines получили `cbAtts[1].colorWriteMask=0` чтобы plasma/translucent/particle/etc. не затирали velocity опаковых meshes под собой. Bullet trail VFX переработан: убраны периодические trail-плёшки, hit flash добавлен для non-AoE попаданий (AoE остался с fireball+sparks). |
| E11   | Rotated plasma billboards + cone muzzle blast | engine wave | ✅ **Готово** (2026-05-07) — `drawPlasmaBillboard(rotation: float)` параметр через C/JNI/Kotlin; render-loop композирует `multiply(billboard, Mat4::rotationY(rotation))` для локального Ry до camera-align. `buildMuzzleConeMesh` (12-segment fan, ±15° aperture, radius 1) + `spawnMuzzleBlast` спавнит 3 cone'а на 120° apart. FBM turbulence + heat-ramp + soft-fade работают на rotated cone из коробки. `MUZZLE_FLASH_HALF` × 3 (0.39). Side turrets перешли на cannon-style (FIRE_INTERVAL_SEC 1.0s, heavy bullet, AoE, ×3 damage). |
| E12   | Railgun muzzle: procedural lightning bolt shader | engine wave | ✅ **Готово** (2026-05-07) — фрагментный shader-sub-material под `pc.tint.x>=0.5 && pc.tint.y>=0.5` рисует процедурную электрическую дугу на quad'е: тонкое Gaussian-ядро вдоль FBM-displaced centerline, cyan halo, brightness-modulation вдоль длины, end-fade. Per-bolt seed в `pc.tint.z` различает дуги; `pc.time` анимирует wiggle. `drawPlasmaBillboard(..., lightningSeed: Float)` через C/JNI/Kotlin. `spawnRailgunMuzzle` для центрального HEAVY_CANNON: cyan-white core flash + 5-7 lightning bolts с rotation вокруг перпендикуляра ± random spread + cyan-tinted muzzle sparks. Side turrets и Автомат продолжают использовать `spawnMuzzleBlast` (warm cone trefoil). «Тяжёлая пушка» переименована в «Рельсотрон» в WeaponCatalog. nebulaAlphaMod / hexAlphaMod gated `pc.tint.x<0.5` — `tint.y/z` теперь имеют разный смысл в plasma vs translucent path. |
| E10.5-E10.6 | Particle prev-pos, verify | engine wave | 🟡 **Запланировано** — particle layout 8→14 floats с prevPos. Particles пока пишут vec2(0) в outVelocity, мelькают, но в плотных AoE/spark scenes flicker не критичен. |
| M8 | Геймплейный pivot: auto-aim + способности | gameplay | ✅ **Готово** (2026-05-08) — центральная турель auto-targeting (max-current-HP, sticky lock); HP-bars над астероидами; energy resource (100 max, +10/sec); 2 способности (Ракетный залп — 3 homing missiles, 30 энергии, 8с cd; Лазерный удар — drag-line, 5 lightning bolts, 50 энергии, 18с cd); ability framework в `game/Ability.kt`; mission counts ×1.5–2; UI thread → mission thread marshalling для ability activation (CME safety) |
| M9 | Щит: HP-based barrier + hold-to-recharge | gameplay | ✅ **Готово** (2026-05-08) — убран ShieldState (READY/ACTIVE/COOLING); permanent щит с `shieldHp` (max 500); damage routing через щит → overflow на платформу; кнопка hold-to-recharge (50 энергии/сек → 200 HP/сек, 4× ratio); новая гео `buildShieldArchMesh()` — wide flat ellipse arch с вершинами в мировых координатах, constant-thickness band |

Зависимости: M1 → {M2, M3, M5}; M2 → M4; {M2, M3, M5} → M6; всё → M7. E1 параллельно (затрагивает только нативку). E2.2 зависит от E1 (`load_mesh_raw` + translucent pipeline). E3.2/E3.3 зависят от E3.1 (material flags). E7 независим (переиспользует E4 шейдер-ветку). E8 независим. E9 → нужен E8 (sprite-атласы для частиц). E10 → нужны G-buffer attachment infra и render-to-texture pass; делать после E9 чтобы тестировать на плотных сценах.

### M1 — Новое ядро управления (завершено 2026-05-04)

Сделано:
- ✅ M1.1 Снести логику робота (удалены `robotX/Target`, drag-движение, `Draft.ROBOT_*`, `DraftCombat.ROBOT_TOP_Z`, `robotMeshHandle`).
- ✅ M1.2 Центральная турель в сцене (красный высокий прямоугольник, базо-привязанный поворот через `rotationY`).
- ✅ M1.3 Прицеливание касанием: touch X+Z → world (X, Z) → угол; экспоненциальное сглаживание ~16/сек; clamp dz≥0 (нельзя стрелять в платформу).
- ✅ M1.4 Hold-to-fire: пока палец на экране — стрельба с интервалом `FIRE_INTERVAL_SEC` в направлении прицела; первый выстрел мгновенный (timer prime на ACTION_DOWN).
- ✅ M1.5 Боковые турели остаются автоматическими, урон ~50% от центральной (соотношение через `UpgradeCatalog.turretDamageAt` vs `robotDamageAt`, оставлено как было).

Принятые решения по ходу:
- Только hold-to-fire, без отдельного «tap = одиночный».
- Турель крутится плавно (16/сек экспонента), а не мгновенно.
- Цвет центральной турели — пока красный (бывший цвет робота). Финальный цвет/модель — позже.
- В этом коммите не трогали `UpgradeCatalog` — рантайм-переменная переименована в `effectiveMainWeaponDamage`, источник всё ещё `robotDamageAt`. Полное переименование — M4.

### M2 — Оружейная система (завершено 2026-05-04)

Сделано:
- ✅ M2.1 Абстракция `Weapon` в `game/Weapon.kt` (fireInterval, damageMultiplier, projectileSpeed, projectileHalfW/H + поля `aoeRadius`/`aoeDamageMultiplier` под пушку). `currentWeapon` в `MainActivity`, тик читает все параметры из него. `Bullet` несёт собственный размер.
- ✅ M2.2 «Тяжёлая пушка» в `WeaponCatalog.HEAVY_CANNON`: fireInterval 1.0 сек, damage ×3, projectileSpeed 18, AoE радиус 0.5 / 60% урона. `Bullet` расширен `aoeRadius`/`aoeDamage`; коллизионный цикл применяет splash к соседним живым астероидам в радиусе. `Flash` расширен `halfMax` — при AoE-попадании спавнится крупная вспышка размером с радиус взрыва.
- ✅ M2.3 Экран выбора оружия (`OverlayFactory.buildWeaponSelect`). Flow: «Меню → Выбор миссии → **Выбор оружия** → Игра». Карточка содержит название, описание, статы (скорострельность, урон-множитель, AoE-радиус если есть), кнопку «Выбрать»; активное оружие помечено зелёным pill «Выбрано» и подсвеченной карточкой. Debug-кнопка в HUD удалена.

Принятые решения:
- Выбор оружия — runtime-only состояние (`@Volatile var currentWeapon`). Персистентность отложена до M4 (когда переименовываем `UpgradeCatalog` и реструктурируем `GameProgress`).
- Все оружия доступны без условий разблокировки. Lock-инфраструктура добавится только когда появится третье оружие (idea.txt task 9 сценарий 3).
- AoE — только для центральной турели. Боковые турели спавнят пули с дефолтными `aoeRadius=0`, поведение неизменно.

### M3 — Щит базы (завершено 2026-05-04)

Сделано:
- ✅ State machine `ShieldState` (READY / ACTIVE / COOLING) в `MainActivity`. Поля `shieldState`, `shieldTimer`, `shieldCooldown`. Параметры: `SHIELD_DURATION_SEC = 3.0f`, `SHIELD_COOLDOWN_SEC = 15.0f`.
- ✅ Tick: ACTIVE отсчитывает `shieldTimer` → COOLING; COOLING отсчитывает `shieldCooldown` → READY. UI обновляется на переходах и на каждом целом-секундном барьере (`shieldUiSecLast` дросселирует частоту).
- ✅ Поглощение урона: в коллизионной ветке «астероид касается платформы», если `shieldState == ACTIVE`, урон базе не наносится; астероид всё равно потребляется + спавнится мелкая вспышка на месте удара (визуальный feedback что щит сработал).
- ✅ Диегетическая кнопка `shieldButton` (TextView, программно собранная, 140×56dp) в нижней-центральной части экрана через FrameLayout gravity. Три визуальных состояния:
  - **READY:** синий фон (`COL_ACCENT_BLUE`), текст «ЩИТ», тапается.
  - **ACTIVE:** зелёный фон (`COL_ACCENT_GREEN`), текст «ЩИТ Nс», disabled.
  - **COOLING:** приглушённый фон (`COL_PANEL_BG_HI`), приглушённый текст «Готов Nс», disabled.
- ✅ Placeholder in-world VFX: пока щит активен, mesh платформы свапится с `quadGreyHandle` на `quadBlueHandle` — мгновенно читаемый сигнал «база в защите». Полноценный купол отложен до M7.
- ✅ Сброс щита и видимость кнопки при `startMission` (READY, кнопка видна), `goToMenu`/`showMissionSelect` (кнопка скрыта).

Принятые решения:
- Не реализую полупрозрачный купол на M3 — у движка нет alpha-blending pipeline для произвольных мешей. Свап тинта платформы — быстрый и читаемый плейсхолдер; M7 заменит на нормальный VFX (вероятно потребует отдельного эмиттера/пайплайна).
- Кнопка вне HUD-панели сверху — отдельный FrameLayout-ребёнок снизу. Так она «приклеивается» к платформе оптически, как просил пользователь.
- Cooldown 15 сек, длительность 3 сек — выбраны из предложенного диапазона (3–5 / 15–20). После тестов балансим.

### M4 — Мета-прогресс под новые ветки (завершено 2026-05-04)

Сделано:
- ✅ `UpgradeType`: `ROBOT_DAMAGE` → `MAIN_WEAPON_DAMAGE`, `TURRET_DAMAGE` → `SIDE_TURRET_DAMAGE` (`BASE_HP` без изменений).
- ✅ `UpgradeCatalog`: функции `robotDamageAt` → `mainWeaponDamageAt`, `turretDamageAt` → `sideTurretDamageAt`. Тексты «Урон робота» → «Урон главного оружия», «Урон турелей» → «Урон боковых турелей»; описания эффектов привязаны к центральной/боковым турелям.
- ✅ `GameProgress`: поля `robotDamageLevel` → `mainWeaponDamageLevel`, `turretDamageLevel` → `sideTurretDamageLevel`.
- ✅ `ProgressRepository`: ключи SharedPreferences переименованы (`lvl_main_weapon_dmg`, `lvl_side_turret_dmg`); **PREF_FILE поднят с `outpost_progress` на `outpost_progress_v2`** — старый файл изолирован, новый стартует с дефолтов (полный сброс прогресса для всех существующих установок, без миграции, как договаривались).
- ✅ `MainActivity` и `OverlayFactory.upgradeIconColour` обновлены под новые имена.

Принятые решения:
- Числа значений и цены оставлены как были — только переименование. Балансные правки оставлены под M5+M6.
- Старый pref-файл `outpost_progress` не удаляется явно — Android просто перестаёт его читать. Если в будущем понадобится, можно его подчистить отдельно.

### M5 — Типы астероидов + buff-система (завершено 2026-05-04)

Сделано:
- ✅ `game/AsteroidType.kt` — enum с per-type множителями (`hpMul`, `speedMul`, `halfMul`, `platformDmgMul`):
  - `NORMAL` 1.0/1.0/1.0/1.0 — baseline.
  - `FAST` 0.4/2.0/0.7/1.0 — мало HP, 2× скорость, мельче, обычный урон базе.
  - `HEAVY` 3.0/0.5/1.5/2.0 — много HP, 0.5× скорость, крупнее, 2× урон базе.
  - `EXPLOSIVE` 1.0/1.0/1.0/1.0 + AoE при смерти (радиус 0.5, 30 урона по соседям).
  - `ENERGY` 0.6/0.8/1.0/1.0 + бафф «×2 урон главного оружия 5 сек».
- ✅ `WaveConfig.typeWeights: Map<AsteroidType, Float>` — спавнер выбирает тип по весам (`pickAsteroidType`); пустой/нулевой fallback на NORMAL.
- ✅ `Asteroid` data class расширен полями `type`/`speed`/`half`/`platformDmg` — рассчитываются при спавне (mission baseline × type multipliers), читаются в коллизиях, физике падения и в обработке удара о платформу.
- ✅ Тинтованные меши `Asteroid_1.glb`: `asteroidMesh3D` (серый, NORMAL+FAST), `asteroidMeshHeavy` (тёмно-красный), `asteroidMeshExplosive` (оранжевый), `asteroidMeshEnergy` (циан). Fallback на серый, если тинт не загрузился.
- ✅ Buff-система: `activeBuffTimer`/`activeBuffDamageMul` в `MainActivity`, тик отсчитывает таймер, центральная турель умножает per-shot damage на множитель. Один слот, таймер 5 сек, множитель ×2.
- ✅ Обработка смерти: EXPLOSIVE → splash damage соседям + крупная вспышка размером с AoE; ENERGY → запуск баффа + средняя вспышка; остальные → стандартная мелкая вспышка.
- ✅ HUD-индикатор баффа (`buffIndicator`) — caption с молнией и обратным отсчётом, появляется на время баффа, потом скрыт. Полная иконка/анимация — M7.
- ✅ Миссии 2 и 3 получили type-микс для тестирования (миссия 1 остаётся onboarding со всем NORMAL до M6).

Принятые решения:
- EXPLOSIVE и ENERGY в enum только дата; on-death эффекты в тике — enum остаётся чистым описанием.
- ENERGY-астероид редкий (5% всех волн в M5-микс) — игрок ловит ощущение «приятная редкая цель».
- HUD-индикатор минимальный, текстовый. Иконка-молния (placeholder) уже даёт читаемость; анимация/glow в M7.

### M6 — Кампания: 5 новых миссий (завершено 2026-05-04)

Сделано:
- ✅ `Missions.ALL` пересобран: 3 → 5 миссий, каждая обучает одной механике (idea.txt task 11):
  1. **Учебная тревога** — прицеливание+стрельба, NORMAL only, 2×5 астероидов, медленно.
  2. **Быстрые цели** — постепенное введение FAST (30% → 50% → 70%).
  3. **Тяжёлая угроза** — постепенное введение HEAVY (30% → 50% → 60%) + ENERGY на финале для побуждения использовать бафф; повышенный baseHp под двойной урон HEAVY делает щит реально полезным.
  4. **Взрывная цепочка** — EXPLOSIVE до 50% последней волны, плотные интервалы спавна (1.4 сек) для AoE-комбо.
  5. **Проверка базы** — все 5 типов вместе, 4 волны нарастающего давления, baseHp 130, скорость 1.4.
- ✅ Описания миссий переписаны под обучающую интенцию («учитесь резко менять направление огня», «используйте щит, когда станет жарко», «ловите моменты для комбо»).
- ✅ Существующая инфраструктура (mission-select scrolling, win/lose flow, "Следующая миссия" по индексу) поддержала рост числа миссий без правок.

Принятые решения:
- Числа — разумные дефолты, не точная балансировка. Тонкая настройка кривой сложности отложена («далее придётся думать» — пользователь).
- Прогрессия не лочится: `highestMissionUnlocked` хранится, но mission-select показывает все миссии. Добавление gate'а — отдельной задачей если понадобится.
- Описания миссий стали инструктирующими, а не литературными — чтобы экран выбора миссии работал как мини-туториал для новых механик.

### M7 — Полировочный VFX-проход (в работе)

Сделано:
- ✅ **Купол щита** (2026-05-04) — заменили placeholder сине-тинт-свап платформы на нормальный VFX через additive plasma pipeline движка. Реализация целиком в Kotlin (`drawPlasmaBillboard` уже была прокинута через JNI ещё с g3, C++ не трогали). `buildShieldDomeBillboards()` в `MainActivity` собирает 2 стэк-биллборда (halo + ярче core) поверх базы, аддитивный блендинг даёт читаемое «энергопузырь»-свечение в центре. Пульсация ±6% по `sin(elapsed*5)` + линейный фейд за последние 0.6 сек длительности (купол «схлопывается» перед COOLING). Новый меш `quadDomeHandle` (тинт 0.18/0.45/0.85 — мягкий, чтобы additive-стек не блюовал в белое). Платформа всегда грей; сбор биллбордов в `engineView.plasmaBillboards` идёт в конце `buildScene()`.

Принятые решения:
- Использовали уже существующий plasma-пайплайн (`additive blend, depth-test read-only`), потому что он подходит точно — никакого нового пайплайна заводить не нужно.
- Силуэт «по-настоящему доменный» (полукруг) пока не делаем — потребовал бы либо custom mesh asset, либо custom фрагмент-шейдер. Двух квад-биллбордов с разной шириной достаточно, чтобы читаться как энергопузырь; если визуал не зайдёт — итерация на geometry/scale-параметры дешёвая.
- Таймер действия щита уже выводится текстом на кнопке («ЩИТ Nс» / «Готов Nс»), отдельный визуальный progress-bar на M7 не делаем (gold-plate).

Сделано:
- ✅ **Турель + пушка VFX (M7.1)** (2026-05-04) — целиком в Kotlin поверх существующего `Flash`-механизма:
  - **Muzzle flash** при каждом выстреле (центральная турель + боковые), мелкая жёлтая вспышка при спавне пули. Боковые турели — 70% размера, чтобы оставались визуально вторичными.
  - **Шлейф пули** — у каждой пули поле `trailTimer`, в move-loop через `TRAIL_INTERVAL_SEC = 0.04с` спавнится крошечный жёлтый flash на текущей позиции. На скрине видно как «комета» за каждым снарядом.
  - **AoE-кольцо** — заменил placeholder «один большой жёлтый квадрат на месте взрыва» на helper `spawnAoeRing(cx, cz, radius)`: одна яркая вспышка в центре + N=10 мелких частиц на окружности радиуса. Силуэт читается как кольцо радиуса AoE. Используется при попадании тяжёлой пушки и при смерти EXPLOSIVE-астероида.
  - Все константы вынесены в `DraftCombat` (MUZZLE_FLASH_LIFE/HALF, TRAIL_INTERVAL_SEC/LIFE/HALF, AOE_RING_PARTICLES/PARTICLE_HALF/LIFE).

Сделано (продолжение M7):
- ✅ **Фикс tap-spam обхода cooldown** (2026-05-04) — оригинальная схема прайми́ла `fireTimer = fireIntervalSec` на каждом `ACTION_DOWN`, поэтому быстрый тап-спам стрелял с частотой касаний, а не оружия. Заменили на `centralFireCooldown`, который тикает ВНИЗ независимо от `isTouching`; выстрел разрешается только когда cooldown ≤ 0, после выстрела cooldown = `fireIntervalSec`. Первый выстрел после паузы по-прежнему мгновенный (cooldown давно нулевой). Теперь тяжёлая пушка реально стреляет 1 раз/сек, сколько бы тапов игрок ни сделал.
- ✅ **Шкала перезарядки** (2026-05-04) — узкая горизонтальная полоса (бэкинг = grey-quad на полную ширину, fill = yellow-quad шириной = `1 - cooldown/interval`, anchored к левому краю). Сидит **над верхушкой ствола центральной турели** (z=-0.30), а не строго «под пушкой» как просил пользователь, потому что физически под турелью на платформе её перекрывает overlay-кнопка ЩИТ; визуально читается как индикатор готовности оружия. Для автомата (0.15 сек) полоса по сути всегда полная, для тяжёлой пушки (1 сек) видно как растёт.
  - Технический нюанс: regular `m_pipeline` использует `VK_COMPARE_OP_LESS`, поэтому два меша на одной Y-плоскости не комбинируются — при равной глубине второй фрагмент **отбрасывается**. Чтобы fill рендерился поверх backing, его SceneObject сдвинут на `y=-0.01` (чуть ближе к камере). Записал это в комментарии в коде.

Остаётся в M7 (опциональная полировка):
- **Финальный обход** — баланс яркостей и плотности VFX, чтобы не было визуального шума при плотных волнах.

### M7.2 — Контентный апгрейд: модели астероидов и пуль (завершено 2026-05-05)

Триггер: пользователь указал, что в `D:\AsteroidOutpost\art\` уже лежат сделанные в Blender ассеты — `Asteroid_2/3/4/9.glb` (5 разных силуэтов помимо `Asteroid_1`) и `Bullet.glb` + `Bullet_Heavy.glb`. До этого все астероиды были один и тот же `Asteroid_1.glb` с разными тинтами, а пули — простые красные quad-палки. Подзадача `idea.txt` task 5 («Улучшить различимость типов астероидов») получает базовое визуальное решение.

Сделано:
- ✅ **5 мешей астероидов per-type.** Скопированы `Asteroid_2/3/4/9.glb` в `app/src/main/assets/models/`. Маппинг типов: `Asteroid_1` + `Asteroid_2` (грей, рандомизируются 50/50 на спавне для NORMAL/FAST), `Asteroid_3` (тёмно-красный — HEAVY, чанковая форма), `Asteroid_4` (оранжевый — EXPLOSIVE), `Asteroid_9` (циан — ENERGY). `Asteroid` data class расширен `meshHandle: Long` — выбирается на спавне и хранится за астероидом, чтобы силуэт не «прыгал» между фреймами. `buildScene` рендерит `a.meshHandle` напрямую (с fallback на `asteroidMeshGrey1` если 0). У всех 5 моделей bbox примерно ±1, поэтому существующий `scale = a.half` работает без правок.
- ✅ **Реальные модели пуль вместо red-quad.** `Bullet.glb` (для автомата + боковых турелей) и `Bullet_Heavy.glb` (для тяжёлой пушки) скопированы в `assets/models/`. Тинты: тёплый латунно-медный (`1.00, 0.85, 0.55`) для обычной пули, чуть более холодный сталь-латунь (`0.90, 0.80, 0.60`) для тяжёлого снаряда. `Bullet` data class расширен `meshHandle` — устанавливается на спавне из `currentWeapon.aoeRadius > 0f` (heavy → тяжёлый снаряд, иначе → обычная пуля). Все три точки спавна (центральная турель × 1, боковые × 2) ставят `meshHandle` явно.
- ✅ **Yaw correction для пуль.** `Bullet*.glb` авторили в Blender так, что длинная ось — `+X` (видно по accessor min/max: `X∈[0.02, 0.72]`, `Y/Z=±0.18`). Существующий код ориентировал `+Z` через `rotationY = atan2(b.vx, b.vz)` (под старую quad-палку с длинной осью `+Z`). Добавлен `DraftCombat.BULLET_MODEL_YAW_OFFSET = -π/2` — добавляется к `atan2(vx, vz)`, разворачивает `+X-forward` модель в `+Z-forward` velocity vector. Без этого пули летели бы поперёк полёта.
- ✅ **Bullet scale ×2.** `DraftCombat.BULLET_MODEL_SCALE_MUL = 2.0f`. Голая `b.halfH = 0.18` давала пулю длиной ~0.13 ед. (модель ~0.7 ед. длиной × 0.18 scale) — тонула в muzzle/trail flash (которые `MUZZLE_FLASH_HALF=0.13`, `TRAIL_HALF=0.05` через аддитивный plasma pipeline). После ×2 пуля ~0.5 ед. длиной — читается рядом с эффектами, остаётся ощущение «снаряд несётся к цели».
- ✅ **Aim-alignment fire gate.** `DraftCombat.AIM_ALIGN_THRESHOLD_RAD = 0.10f` (~5.7°). Центральная турель теперь стреляет, только если `|targetAngle - centralTurretAngle|` меньше порога. Без этого первый кадр после `ACTION_DOWN` спавнил пулю в старом направлении ствола — игрок целился справа, нажимал, а первая пуля летела влево по последнему направлению turn-смущения. Гейт ждёт пока экспоненциальное вращение турели догонит touch direction.
- ✅ **GltfLoader: merge multi-primitive meshes.** Engine fix в `cpp/engine/GltfLoader.cpp::loadFromMemory` — раньше возвращал после первого попавшегося `TINYGLTF_MODE_TRIANGLES` примитива первого меша. Это было причиной невидимых пуль: `Bullet.glb` имеет **3 примитива** (латунный корпус, медный наконечник, донный ободок), `Bullet_Heavy.glb` — **6 примитивов** (длинный корпус, наконечник, базис, переходные кольца, ведущий поясок). Loader теперь итерирует по всем триангуляр-примитивам меша и сливает их в одну `MeshData` (offset индексов на текущий vertex count). Поскольку `load_mesh_colored` всё равно перекрашивает все вершины в один тинт, multi-material split на стороне loader не теряет визуальной информации после слияния. Астероиды одно-примитивные, для них поведение не меняется.

Принятые решения:
- Не делали multi-mesh-per-bullet рендер (один primitive = один draw call с собственным material baseColor) — это потребовало бы переделать ABI `load_mesh_*` под список меш-токенов и расширить `SceneObject` под draw-list. Слияние всех примитивов в один меш + uniform tint через `load_mesh_colored` — компромисс: пуля выглядит однотонной, но цельной. Если в будущем захотим многоцветную пулю с медным наконечником, это уже вопрос UV+textures (см. бэклог по движку, E4) или multi-material draw API.
- Astroid_4 / Asteroid_9 не имеют preview .png в `art/`, поэтому не знал точную форму до запуска на устройстве. Назначил по логике: HEAVY=Asteroid_3 (выглядит круглой и плотной на превью), EXPLOSIVE=Asteroid_4 (наугад), ENERGY=Asteroid_9 (наугад). Если на скрине какое-то соответствие читается плохо — поменять = одно строчное изменение в `MainActivity.onCreate` + spawn switch.
- Не подкрутили `LOG_TAG`-овый дебаг-вывод количества загруженных вершин в `loadMesh*`; в логе уже есть `LOGI("GltfLoader: mesh='%s' merged %zu prims → %zu verts, %zu indices")` — этого достаточно для диагностики, если в будущем какая-то .glb опять окажется multi-prim и не сможет влезть в `uint16_t` индексы (нужна будет проверка > 65535).

### E1 — Движок: alpha + RGBA + процедурные меши (завершено 2026-05-04)

Первая правка движка после форка из g3. Триггер: попытка собрать «красивый живой космический фон» из tinted-квадов упёрлась в видимые швы и баndинг (см. итерацию с gradient-strips → пользовательский фидбэк «Стоп. получается плохо»). Без alpha-blending мешей и per-vertex прозрачности эффекты остаются «коробчатыми». Решили перерабатывать движок маленькими шагами с визуальной проверкой каждого.

Сделано:
- ✅ **E1.1 — Vertex format → RGBA.** `Vertex.color` расширен с `float[3]` до `float[4]`. Атрибут вершины `VK_FORMAT_R32G32B32_SFLOAT` → `VK_FORMAT_R32G32B32A32_SFLOAT`. Шейдеры (`triangle.vert`/`frag`) переведены на `vec4` в location 1; `outColor.a` теперь = `vColor.a` (раньше было захардкожено `1.0`). `GltfLoader` читает COLOR_0 как VEC3 (alpha=1) или VEC4 (как есть). Все opaque code paths (`load_mesh_colored`, `ShipMesh`, frame-line-meshes, stars) пишут `A=1` явно — для `m_pipeline` (без блендинга) ничего не меняется. Скрин-проверка: меню/игра рендерятся идентично до E1.1.
- ✅ **E1.2 — Translucent pipeline + `draw_translucent_mesh`.** Шестой пайплайн в `VulkanContext`: SRC_ALPHA/ONE_MINUS_SRC_ALPHA, depth-test on, depth-write off, та же шейдер-пара что у `m_pipeline`. Создаётся в `createPipelineInfra` после plasma, разрушается симметрично. Новый `m_translucentDrawList` чистится в `beginScene`, рендерится в `renderFrame` между system-биллбордами и plasma. C API `station_engine_draw_translucent_mesh(engine, mesh, mat4)` → JNI `nativeDrawTranslucentMesh` → `EngineJni.drawTranslucentMesh`. `EngineView` получил `@Volatile var translucentObjects: List<SceneObject>`; `Scene.submitScene` пробрасывает оба списка. Скрин-проверка: пустой translucent-список → визуально без изменений.
- ✅ **E1.3 — Процедурные меши (`load_mesh_raw`).** Новый C API `station_engine_load_mesh_raw(verts, vlen, indices, ilen)`: каждая вершина = 10 float (`pos3 + rgba4 + normal3`), индексы uint16. JNI-обвязка с валидацией `vlen % 10 == 0`. Kotlin `EngineJni.loadMeshRaw(FloatArray, ShortArray): Long`. Это разблокирует генерируемые на лету меши без необходимости создавать .gltf-файл — в первую очередь soft-disk нéбулы, но также пригодится для будущих кастомных VFX-форм.
- ✅ **E1.4 — Soft-disk нéбулы в фоне Outpost.** `MainActivity.buildSoftDiskMesh(r, g, b, sectors=24)` собирает triangle-fan: центральная вершина (A=1) + 24 ободочных (A=0), `sectors*3` индексов. Рендерится через translucent pipeline → честная круглая мягкая клякса, никаких видимых граней квадрата. `setupBackgroundNebulae()` создаёт 5 тинтованных дисков (deep purple / cyan / dim crimson / twilight blue / warm dust) и расставляет их в `placements`-таблице по полю боя. Заменили predыдущий gradient-strip-фон полностью: `bgStripHandles[24]` → `nebulaHandles[5]`, блок генерации tinted-полос вырезан из `onCreate`, цикл сборки strips из `buildScene` тоже. Скрин-проверка: 5 кляксы видны, гэп-плэй (платформа/турели/астероиды) рендерится поверх корректно, звёзды просвечивают в тёмных промежутках.

Принятые решения:
- Правили текущий движок напрямую (бэкап в `D:\g3` есть). Каждый шаг компилировался + запускался на устройстве через ADB до перехода к следующему.
- Шейдер-пара одна на все шесть пайплайнов — пайплайны различаются только blend/depth state. Лоу-стоимостно поддерживать, не нужно дублировать lighting math.
- `load_mesh_raw` принимает плоский `float*` + `uint16*` (не структуру `Vertex`) — стабильнее ABI через JNI и проще генерировать из Kotlin.
- Нéбулы — статичные (без анимации). Если в будущем понадобится «дышать» — можно гонять `scale` или per-vertex alpha из тика, инфраструктура уже на месте.

Что E1 разблокировал на будущее:
- ✅ Софт-фейд по краям любого меша (через A в вершинах) — в т.ч. возможность переделать щит-купол на полупрозрачный полушар вместо additive-стэка из плазмы, если будем делать нормальный mesh-asset.
- ✅ Per-vertex alpha доступен везде (translucent pipeline обязан, plasma тоже теперь имеет канал, но рендерит additive).
- 🟡 **Не разблокировано:** UV-координаты + текстуры (нужен второй vertex-attribute UV + sampler binding), не-uniform scale у биллбордов (правка C API), procedural-shader варианты (отдельные fragment-шейдеры для нéбул-облаков — сейчас только tinted-disk). Эти задачи перенесены в E2-волну (если возьмёмся).

### E2.1 — Soft-fade на plasma-биллбордах (завершено 2026-05-05)

Триггер: после M7 каждая вспышка (muzzle, шлейф, AoE, попадание) рисовалась как жёлтый квадрат `quadFlashHandle` через системный пайплайн. На тёмном фоне выглядело как «жёлтые квадратики, налепленные на сцену» — хотелось мягкие круглые свечения. Plasma-пайплайн уже даёт аддитивный блендинг, но без alpha-fade у краёв квад остаётся видимым.

Сделано:
- ✅ **Шейдер.** `triangle.vert` экспортирует новый `vLocalXZ = inPosition.xz` (модель-спейс X-Z вершины). `triangle.frag` получает его в location 3, считает `r = length(vLocalXZ)`, применяет `1 - smoothstep(0.4, 1.0, r)` как множитель альфы. Plasma-биллборды используют `quad.gltf` (углы ±1), поэтому при `r=1` (середина ребра) альфа = 0, при `r=√2` (угол) — давно 0; видимый glow вписывается в квад и красиво уходит в углах. Фейд применяется во всех ветках фрагмента (frame / plasma-bolt / стандартное освещение), но активируется только если `pc.tint.x ≥ 0.5` — иначе ранний выход с `1.0`.
- ✅ **Гейтинг через `pc.tint.x`.** В `VulkanContext::renderFrame` plasma-loop выставляет `pc.tint[0] = 1.0f` перед `vkCmdPushConstants`. Все остальные пайплайны (system mesh, system billboards, translucent, frame, star) либо оставляют `tint` нулевым, либо передают свой `draw.tint` — у Outpost в нём `tint.x = 0` всегда (selection frames не используются, `highlightMeshes = HighlightMeshes()`; system billboards вообще не вызываются). Никаких ложных срабатываний.
- ✅ **Вспышки → plasma.** `MainActivity.buildScene` теперь вместо добавления `Flash`-объектов в opaque scene сегмент строит `BillboardDraw(quadFlashHandle, …)` и кладёт их в `engineView.plasmaBillboards = buildShieldDomeBillboards() + flashBillboards`. Купол щита и вспышки идут одним plasma-проходом — обе категории получают круглый soft-fade автоматически.
- ✅ Проверено: `where glslc` → `C:\VulkanSDK\1.4.341.1\Bin\glslc.exe`, `triangle.frag.spv` пересобран; `./gradlew assembleDebug` — зелёная сборка для arm64-v8a и x86_64.

Принятые решения:
- Сначала писали `r = length(vLocalXZ) * 2.0` под предположение «квад ±0.5», но `quad.gltf` accessor min/max = `[-1, 0, -1] / [1, 0, 1]`. Из-за `*2.0` фейд выходил вдвое мельче квада — видна центральная клякса размером 50% от `scale`. Поправили на `r = length(vLocalXZ)` без множителя — glow теперь занимает почти весь квад, уходя в углы.
- Не вводили отдельную битовую маску в push-constant под флаг — сэкономили на нём один уже неиспользуемый `tint.x` канал. Если в будущем понадобится вернуть тинт plasma-биллбордам, добавим отдельное `vec4 plasmaParams` или используем `tint.yzw` под цвет, `tint.x` останется флагом.
- Не двинулись на UV/текстуры/частицы из E2-бэклога — E2.1 закрыл текущий визуальный долг (квадратики на месте круглых вспышек), остальное в бэклоге пока не блокирует геймплей.

### E2.2 — Купол щита через процедурную half-membrane (завершено 2026-05-05)

Триггер: E2.1 убрал «квадратики» у plasma-биллбордов, но купол всё равно читался как **четыре дискретных кляксы** (3 «гребня» + апекс). Никакое количество биллбордов плавную полусферу не даст — нужна одна цельная геометрия купола. Воркфлоу: пользователь скриншотил, мы итерировали в один шаг — сначала filled half-disk (центр α=1, обод α=0) дал «синий wash» вместо «силового поля», переделали в **annular half-membrane** (три концентрических дуги, центральный α=0, peak α=0.85 на средней дуге, внешний α=0) — получился тонкий светящийся силуэт с прозрачным интерьером.

Сделано:
- ✅ **Меш `buildDomeMembraneMesh()`.** Triangle-strips между тремя полудугами (θ∈[0,π]) на радиусах 0.85 / 0.92 / 1.00 с альфами 0 / 0.85 / 0. Per-vertex alpha интерполируется линейно через strip → каждая полоса фейдит 0→peak→0 по ширине, итог — тонкая светящаяся плёнка по силуэту купола, интерьер полностью прозрачный (видно центральную турель и боковые турели сквозь). 48 секторов, 49×3=147 вершин, 96 треугольников. Параметры (radii, peakAlpha, sectors) — kwargs функции, легко тюнить.
- ✅ **Заменили stacked plasma-биллборды.** Удалены `quadDomeHandle` (не нужен — купол больше не плазма), `buildShieldDomeBillboards()` (4 BillboardDraw'а в plasma-список). Добавлен `domeMembraneHandle: Long` + `buildShieldDome(): List<SceneObject>` под единственный SceneObject через translucent pipeline.
- ✅ **Композиция translucent-сцены.** `MainActivity.buildScene` теперь делает `engineView.translucentObjects = nebulaeTranslucent + buildShieldDome()`. Кешированный `nebulaeTranslucent` (5 нéбул, статика) отделён от per-frame dome — раньше нéбулы писались напрямую в `engineView.translucentObjects`, теперь сидят в private field и переиспользуются. `setupBackgroundNebulae` строит membrane-меш в том же месте, где грузит нéбулы (логически — оба идут через `loadMeshRaw` + translucent pipeline).
- ✅ **Якорь и масштаб.** Купол центрирован на платформе (`x=0`, `z=PLATFORM_TOP_Z=-0.94`), `y=-0.05` чуть вперёд камеры → translucent depth-test (LESS) пропускает поверх y=0 геймплея. `scaleX=2.4, scaleZ=2.0` — занимает почти всю ширину видимой области (X∈±2.47) и поднимается над платформой на 2 единицы Z (~18% высоты экрана). Pulse ±4% и 0.6-сек коллапс на исходе работают через `scale*mul`, как раньше.

Принятые решения:
- Сначала пробовали filled half-disk (центр α=1) — дал плотный синий wash. Пользователь хотел «плёнку силового поля» по типу force-field reference image (тонкий ободок-мембрана + прозрачный интерьер). Переделали под annular ring — то что нужно.
- Один membrane mesh вместо двух (halo+core). Двухслойность давала бы более «плотный» вид, но по результату скрина одного слоя достаточно. Если позже захочется глубины — легко добавить второй mesh с другими радиусами/цветом.
- Не реализовали FBM-шум / hex-pattern на мембране (concept art показывал hex grid и interference layer) — это потребовало бы UV+текстур (см. E2-бэклог), а E2.2 закрывает базовый «правильный силуэт». Орнамент — отдельная задача когда заведём UV.
- Не использовали plasma pipeline (additive) для купола — translucent (alpha-blend) даёт более «материальный» силовой щит, additive для тонкой мембраны выглядел бы как «глоу-облако» без чёткой границы.

### E3 — Procedural shader patterns (завершено 2026-05-05)

Триггер: E2.1+E2.2 закрыли «коробчатые» силуэты у вспышек и купола, но и нéбулы (идеальные мягкие диски), и купол (голый кольцевой контур) выглядели «слишком чисто» — ни облачной клочковатости, ни структурной фактуры. UV+текстуры (та задача из E2-бэклога) — большой кусок работы; дешевле получить «pre-UV» полировку через процедурные паттерны в фрагмент-шейдере, используя уже существующие `vWorldPos` и `vLocalXZ` от E2.1. После E3 (если ещё захочется визуального апгрейда) — переходим в E4 = UV+textures.

#### E3.1 — Material plumbing (завершено 2026-05-05)

Сделано:
- ✅ **C++ слой.** `VulkanContext::drawTranslucentMesh` теперь принимает `int32_t material` (default 0). При material=1 пишет `cmd.tint[1] = 1.0f` (NEBULA flag), при material=2 — `cmd.tint[2] = 1.0f` (HEX flag). `renderFrame` translucent-loop теперь делает `memcpy(pc.tint, draw.tint, ...)` → флаги доходят до фрагмент-шейдера.
- ✅ **C API.** `station_engine_draw_translucent_mesh(...)` получил `int32_t material` в сигнатуре.
- ✅ **JNI.** `nativeDrawTranslucentMesh` принимает `jint material`.
- ✅ **Kotlin.** `EngineJni.drawTranslucentMesh(handle, mat4, material: Int = MATERIAL_PLAIN)`. Константы `MATERIAL_PLAIN=0`, `MATERIAL_NEBULA=1`, `MATERIAL_HEX=2` в `EngineJni.Companion`.
- ✅ **Scene.** `SceneObject.material: Int = 0`. `submitScene` пробрасывает в engine.

После E3.1 фрагмент-шейдер ничего не делает по новым флагам — просто плумбинг готов. Нéбулы и купол визуально без изменений.

Принятые решения:
- Не плодим новые pipeline'ы под каждый паттерн — все варианты живут в одном fragment-шейдере с ветвлением по флагам. Это позволяет смешивать (например, плазменный soft-fade `tint.x` сохраняется параллельно с nebula/hex). Если в будущем у нас вырастет 5+ материалов — стоит подумать об отдельных шейдерах по material-id, но пока 3 ветки экономно укладываются в один.
- Флаги через `pc.tint` (4 unused канала, кроме `tint.x` который уже забил E2.1), а не через структурное расширение `DrawCommand`. Самый дешёвый путь — никаких структурных изменений.

#### E3.2 — FBM-нéбулы (завершено 2026-05-05)

Триггер: пользователь увидел в первом проходе откровенный «грид value-noise» — нéбулы выглядели как сетка квадратных тайлов, особенно после `smoothstep` контрастирования. Потребовалось две итерации: сначала повернули октавы (~40°) и подняли `*0.6 → *0.9`, грид частично ушёл; затем добавили **domain warping** (сэмпл шума не в `p`, а в `p + warp(p)`, где `warp` — отдельный fbm) — оставшиеся прямоугольные кластеры размылись в завитки и тендрилы.

Финальная реализация:
- ✅ `hash21(vec2)` — 1D hash из 2D через `sin(dot)*43758`.
- ✅ `vnoise2(vec2)` — 2D value-noise с smoothstep-весами `f*f*(3-2f)`.
- ✅ `fbm4(vec2)` — 4 октавы value-noise с per-octave rotation matrix `R(40°)` + non-power-of-2 freq-step `*2.13` (ломает выравнивание сеток между октавами).
- ✅ `nebulaAlphaMod()` — domain warping: `warp = vec2(fbm4(p), fbm4(p + offset))`, `n = fbm4(p + warp * 0.5)`, затем `smoothstep(0.20, 0.85, n)`. Стоит ~12 шумовых сэмплов на фрагмент — на Pixel 9 эмуляторе тянет без проблем.
- ✅ Все 5 нéбул в `MainActivity.setupBackgroundNebulae` помечены `material = EngineJni.MATERIAL_NEBULA`.

Принятые решения:
- Domain warping вместо более дорогого Perlin/gradient noise — комбинация ротированной FBM + warp визуально эквивалентна гладкому шуму в нашем масштабе, но дешевле в реализации.
- World-pos sampling (`vWorldPos.xz`), не local-pos — нéбулы в разных позициях видят разные срезы шумового поля → не выглядят одинаковыми «копипастами».

#### E3.3 — Hex-щит (завершено 2026-05-05)

Триггер: щит после E2.2 был просто светящимся ободом — функционально читался как «силовое поле», но без структурной фактуры из концепт-арта пользователя (force field с hex-сеткой, multiple layers). E3.3 добавил hex-grid поверх filled half-disk dome.

Сделано:
- ✅ **Топология купола.** `buildDomeMembraneMesh` теперь поддерживает 2 формы: annular ring (centerAlpha=0, как было в E2.2) и filled half-disk (centerAlpha>0, добавляется центральная вершина и triangle-fan от центра до peak-арки). Hex-щит использует filled (centerAlpha=0.22, peakAlpha=0.55) — hex-узор имеет непрерывную поверхность для отрисовки, не лезет в «вакуум» интерьера.
- ✅ **Hex tiling в шейдере.** Адаптация Inigo Quilez hex-tile snippet: `hexTile(vec2 p)` возвращает локальные координаты внутри ближайшей hex-ячейки + индекс ячейки. `hexEdgeDist(vec2)` считает расстояние от точки до ближайшего ребра ячейки (0 на грани, 0.866 в центре).
- ✅ **`hexAlphaMod()`.** Sample в `vLocalXZ * 6.0` (~6 ячеек поперёк купола). После двух итераций тюнинга — мягкий узор: `base = 0.85`, `line bonus = 0.15`, переход `smoothstep(0.10, 0.55)`. Сетку видно как **намёк**, не wireframe.
- ✅ **Силуэт мягче.** `midR` сдвинут с 0.92 на 0.80 — фейд от peak-альфы к внешнему ободу занимает 20% радиуса, не 8% → силуэт купола плавно растворяется в фоне.
- ✅ Купол в `MainActivity.buildShieldDome` помечен `material = EngineJni.MATERIAL_HEX`.

Принятые решения:
- Первая попытка с агрессивным hex (`base=0.35`, `line=0.65`) выглядела как тёмные ячейки с яркими гранями — пользователь сказал «странно, нужно более гладким». Резко смягчили (base 0.35→0.85, line 0.65→0.15) — стало читаться как структурный hint.
- Не добавляли animation pulsation на hex (концепт показывал ripple/wave при impact) — для этого нужно прокинуть time через push-constant, отложили до момента когда понадобится impact-effect.
- Hex sample в `vLocalXZ` (model-space), не `vWorldPos` — паттерн прибит к мешу, не к мировым координатам, поэтому при `mul`-пульсации (E2.2 breathing) hex масштабируется вместе с куполом, не «плывёт».

### E4 — Plasma flash polish (завершено 2026-05-05)

Триггер: пользователь увидел вспышки (muzzle flash, шлейфы пуль, AoE-кольца, удар по астероиду) как «грустные жёлтые прямоугольники» поверх красивого процедурного фона. Хотел вспышки уровня нéбул — с внутренней структурой, не плоские. Развернули общий план движка (E4–E8) и пошли по нему сверху вниз; E4 — самый дешёвый VFX-выигрыш, чисто шейдерный.

Триггер №2 (попутно открыли): plasma pipeline блендит ONE/ONE (`VulkanContext.cpp:707-711`), значит source.alpha сбрасывается фреймбуфером — `plasmaSoftFade()` от E2.1 фактически был **no-op** для plasma-биллбордов. Это и было первоисточником «прямоугольного» силуэта: alpha рассчитывалась корректно, но никуда не уходила. Заодно фиксим.

Сделано:
- ✅ **Новая ветка в `triangle.frag`** под флаг `pc.tint.x ≥ 0.5` (= plasma pipeline). Расположена после `isPlasma`-cyan ветки (g3-холдовер плазма-болтов с цианом — должна сохранить свой вид) и до `lit`-ветки (всё прочее по-прежнему проходит lighting). Внутри:
  - **Premultiply alpha в RGB.** `outColor = vec4(fireColor * fire * alpha, alpha)` — теперь soft-fade реально гасит углы квада, потому что итоговый RGB умножен на alpha; на ONE/ONE-блендинге фреймбуфер видит ровно `RGB*alpha`, а в углах (где alpha→0 от soft-fade) контрибуция нулевая.
  - **Радиальный heat-ramp.** `mix(hot, cool, smoothstep(0.0, 0.7, r))`, где `r = length(vLocalXZ)`. `hot = (1, 0.95, 0.7)` (тёпло-белое ядро), `cool = (1, 0.4, 0.08)` (оранжевый край). Без per-billboard tint данных получаем «огонь» на любом квад-меше с pc.tint.x флагом.
  - **FBM-турбулентность.** Переиспользовали `fbm4()` от E3.2 (4 октавы value-noise + per-octave 40°-rotation + freq×2.13). Сэмпл по `vWorldPos.xz * 8.0` — мировые координаты, поэтому каждая вспышка получает уникальный срез шумового поля. Множитель `0.55 + n * 0.95` даёт диапазон ~[0.55, 1.5] по яркости — клочки горящего материала, не равномерное свечение.
- ✅ Перекомпилирован `triangle.frag.spv` через `glslc`. `./gradlew assembleDebug` — зелёная сборка.

Принятые решения:
- Не трогали C API, JNI, Kotlin, другие пайплайны. Скоуп E4 строго ограничен фрагмент-шейдером — самая дешёвая итерация в волне.
- Heat-ramp и turbulence имеют hardcoded числа в шейдере. Если понадобится разная окраска под событие (синие искры от удара, зелёные от ENERGY-астероида) — это уже E5 (per-billboard tint через C API).
- Не трогали `isPlasma`-cyan ветку, хотя у неё та же проблема с soft-fade no-op. В Outpost cyan-болты не используются (g3-only); если когда-нибудь вернём их, фикс будет в той же манере.
- FBM в world-space, не в `vLocalXZ`. World-space даёт уникальный pattern на каждую вспышку; local-space заставил бы все вспышки выглядеть одинаково. Цена та же (4 octaves of FBM), профиль рендера не меняется.
- Frequency multiplier 8.0 для `vWorldPos.xz` — компромисс: при typical scale=0.3 видно 4-5 «клочков» на квад, читается как огонь. Меньше — слишком крупные пятна (0.5 пятен на квад, читается как «полу-яркий полу-тёмный»); больше — мелкая зернистость, теряется силуэт.

Что E4 не закрывает (передаётся в E5/E6):
- 🟡 Per-billboard цвет — все вспышки сейчас тёпло-оранжевые, нельзя сделать синюю искру или зелёный электро-удар.
- 🟡 Анимация турбулентности — pattern статичен на время жизни вспышки. Для коротких вспышек (~0.15-0.5 сек) этого достаточно, но при долгом эффекте «горения» pattern будет читаться как «застрявший». E6 (time push-constant) даст бегущую турбулентность.
- 🟡 Не-uniform масштаб биллбордов — для растянутых streak-эффектов (длинные шлейфы пуль, плоские ударные волны). E5.

### E5.1 — Per-billboard plasma tint (завершено 2026-05-05)

Триггер: после E4 все вспышки (muzzle, шлейф, AoE, удар по щиту, смерть астероида, ENERGY-pickup) рендерились одинаковым тёплым огненным паттерном — heat-ramp + FBM хардкодом. Игрок не мог отличить событие по цвету: AoE и обычная смерть выглядели идентично, ENERGY-астероид при смерти тоже жёлтый. Чтобы дать каждому событию свою визуальную идентичность, нужен per-billboard цвет — без UV/текстур, через push-constant.

Сделано:
- ✅ **Push-constant расширен.** `PushConstantData` теперь несёт `float plasmaColor[4]` после `tint[4]` — итоговый размер 96 байт (Vulkan гарантирует ≥128, входим). Шейдеры (`triangle.vert/frag`) добавили `vec4 plasmaColor` в push-constant layout с `layout(offset = 80)`. Все остальные пайплайны автоматически пушат 96 байт через `sizeof(PushConstantData)` — junk data в `plasmaColor` для не-плазмы безопасна, потому что фрагмент-шейдер читает её только в гейте `pc.tint.x ≥ 0.5`.
- ✅ **C API расширен.** `station_engine_draw_plasma_billboard(engine, mesh, x, y, z, scale)` → `(..., scale, r, g, b, a)` — 4 явных float-параметра. Стриктно требуется от вызывающего; default белый ставится на Kotlin-уровне через `EngineJni.drawPlasmaBillboard(..., r=1f, g=1f, b=1f, a=1f)`. JNI-обвязка (`nativeDrawPlasmaBillboard`) пробросила параметры. `VulkanContext::drawPlasmaBillboard` записывает `r,g,b,a` в `DrawCommand.plasmaColor[4]` (новое поле). В render-loop переменная мемкопируется в `pc.plasmaColor`.
- ✅ **Шейдер использует тинт.** В E4-ветке `if (pc.tint.x >= 0.5)`: `outColor = vec4(fireColor * pc.plasmaColor.rgb * fire * alpha * pc.plasmaColor.a, alpha)`. RGB-каналы тинта умножаются в heat-ramp result (рекrашивая огонь), alpha-канал работает как overall-brightness scalar. White (1,1,1,1) сохраняет E4-look без изменений.
- ✅ **Kotlin-сцена расширена.** `BillboardDraw` data class добавил `r, g, b, a: Float = 1f`. `Scene.kt::submitScene` пробрасывает их в `engine.drawPlasmaBillboard(...)`. `Flash` data class в `MainActivity` получил `tintR, tintG, tintB, tintA: Float = 1f` (4 явных field — избегаем per-Flash аллокации FloatArray).
- ✅ **6 per-event тинтов в `DraftCombat`.** `FLASH_TINT_MUZZLE` (тёпло-белый, 1.0/0.95/0.7), `FLASH_TINT_TRAIL` (тёплый дим, 1.0/0.8/0.45/0.85 alpha), `FLASH_TINT_EXPLOSION` (оранжево-красный, 1.0/0.5/0.15), `FLASH_TINT_ENERGY` (циан, 0.45/0.85/1.0/1.1 alpha), `FLASH_TINT_DEATH` (тёплый жёлтый, 1.0/0.85/0.4), `FLASH_TINT_SHIELD` (синий, 0.35/0.75/1.0). Все 8 спавн-сайтов в тике (центральный muzzle, боковой muzzle, trail, AoE-ring center, AoE-ring perimeter, ENERGY death, NORMAL/FAST/HEAVY death, shield-absorb) теперь подцепляют свой тинт.

Принятые решения:
- Цвет через push-constant, не через per-vertex или textures. Push-constant дешевле всего по байтам и вписывается в текущий fragment-shader switch без структурных изменений. UV+textures отложили на E7 (это большая правка по описанию из бэклога).
- Алgha-канал `pc.plasmaColor.a` работает как brightness multiplier, не как opacity. Это потому что plasma blend = ONE/ONE — alpha не доходит до фреймбуфера. Использование .a как brightness даёт второй регулятор интенсивности (>1 для ярких событий типа ENERGY-pickup).
- Не ввели per-event константы ярче/тусклее общую яркость flash — пользователь сам выберет: тинт RGB + alpha-multiplier дают достаточно осей. Если когда-то понадобится — это уже E5.x задача.
- 4 отдельных Float поля на Flash вместо `FloatArray(4)` — каждая Flash-инстанция тогда не аллоцирует массив. Но в `DraftCombat` тинты — `FloatArray(4)` (читаются один раз и индексируются). Этот пример хорошо иллюстрирует: const-данные = массив, runtime-данные = поля.
- `isPlasma` cyan-ветку (g3-холдовер) не обновили. Если когда-нибудь cyan-болты вернутся, можно добавить им свой тинт через тот же `pc.plasmaColor`.

Что E5.1 не закрывает (передаётся дальше):
- 🟡 Не-uniform масштаб биллбордов (E5.2). Для streak'ов / shockwave'ов нужно научить `Camera::billboardMatrix` принимать `scaleH, scaleV` (раздельно по right- и up-осям). При первичной разведке выяснилось, что метод сейчас umiform и что разобраться с тем, какая ось `right`/`up`/`back` — screen-vertical при текущем pitch=π/2, надо отдельно. Поэтому E5.2 не делается одновременно с E5.1.
- 🟡 Анимация турбулентности (E6, time push-constant) — pattern всё ещё статичен.
- 🟡 Per-billboard color sampling из текстуры (E7).

### E5.2 — Billboard matrix fix + non-uniform scale (завершено 2026-05-05)

Триггер: пользователь отправил предыдущий план E5.2 («просто пробросить scaleH/V через C API») с требованием перепроверить математику. Проверка нашла, что движок весь это время работал с **багом matrix-mapping**.

Диагностика:
- Камера: pitch=π/2 around X axis. world axis assignments: X=horizontal, Z=vertical, Y=depth (per `Camera::reset` comment).
- `m_rotation.rotate(...)` → right=(1,0,0), up=(0,0,1), back=(0,-1,0).
- Pre-fix `billboardMatrix` ставил col 1 = up·scale, col 2 = back·scale. Для X-Z квада (model.y=0, x∈[-1,1], z∈[-1,1]) это давало:
  - world.x = scale·model.x + cx (✓ horizontal)
  - world.y = -scale·model.z + cy (depth)
  - world.z = cz (constant!)
- Квад лежал в **горизонтальной мировой плоскости** (Z=cz), не перпендикулярно экрану. На экране визуализировался как горизонтальная полоса с perspective foreshortening, не как screen-aligned билборд.
- Pытался ли я скрин — вспышки выглядели «прямоугольными» именно из-за этого; E2.1 radial soft-fade и E4 heat-ramp вычислялись в `vLocalXZ` model-space (круглый pattern), но проектировались на горизонтальную полосу — большая часть круга «съедалась» depth foreshortening, оставалась только тонкая полоска.
- Пользователь подтвердил визуально: трейлы пуль читаются как горизонтальные риски, не круглые точки. Анализ верный.

Сделано:
- ✅ **Swap col 1 ↔ col 2 в `Camera::billboardMatrix`.** Теперь model.y → camera-back (depth, scale=1, не контрибутирует для нашего X-Z меша), model.z → camera-up (screen-vertical). X-Z квады правильно screen-aligned. `vLocalXZ` радиальный pattern теперь действительно круглый на экране — retroactively фиксит intended behaviour E2.1 soft-fade и E4 heat-ramp + FBM.
- ✅ **Non-uniform scale**: новая сигнатура `billboardMatrix(center, scaleH, scaleV)` — col 0 (model.x → horizontal) умножается на scaleH, col 2 (model.z → vertical) на scaleV. col 1 (depth) остаётся scale=1. Старая uniform `billboardMatrix(center, scale)` сохранена как wrapper, чтобы не ломать g3-style `drawBillboardMesh`.
- ✅ **C API расширен.** `station_engine_draw_plasma_billboard(...,scale)` → `(...,scaleH, scaleV)`. JNI и Kotlin EngineJni обновлены. `BillboardDraw` data class получил `scaleV: Float = scale` (default = uniform), `submitScene` пробрасывает его. Все existing call-sites (Outpost flashes + g3 SceneAdapter) автоматически работают как uniform.
- ✅ **DrawCommand расширен** полем `scaleV` (рядом с существующим `scale`, который теперь семантически = scaleH для plasma).
- ✅ Build assembleDebug — зелёный, обе ABI.

Принятые решения:
- Не вводили новый меш — fix матрицы делает существующий `quad.gltf` (X-Z plane) screen-aligned without changes. Меньше работы, нет визуальных регрессий для других мешей.
- col 1 (depth) не масштабируется через scaleD/scaleZ — для нашего X-Z меша это не нужно (model.y=0). Если когда-то понадобится 3D-геометрия с depth-extent, добавим scale_depth тогда.
- Старый API `drawBillboardMesh` (g3 system billboards) использует legacy uniform path — не тронут. Outpost их не использует, g3 features bypassed at runtime, поэтому изменение поведения там безопасно даже если он случайно активируется.
- Выявление этого бага = ROI для будущих волн: правильная screen-aligned билборд-математика разблокирует true streak-эффекты (длинные пули) и flat shockwave'ы (плоские круги ударной волны), которые без E5.2 выглядели бы deformed.

Что E5.2 не закрывает (передаётся дальше):
- 🟡 Анимация турбулентности (E6, time push-constant).
- 🟡 UV + textures (E7).
- 🟡 Particle system (E8).
- 🟡 Additive mesh pipeline (новая будущая волна — для настоящего 3D огненного шара).

### E6 — Time push-constant (завершено 2026-05-05)

Триггер: после E5.2 fragment-шейдер уже умеет heat-ramp + FBM + per-event тинт + true round silhouette. Pattern статичен — на коротких вспышках (~0.15-0.5 сек) не критично, но взрывы (~0.5 сек) и нéбулы (постоянно) хочется оживить. Также time-канал — общая инфраструктура для будущих effects (пульсация щита, бегущие электроразряды, animated lightning bolts) — лучше провести её сейчас, чем переделывать push-constant в каждой следующей волне.

Сделано:
- ✅ **`PushConstantData` расширен `float time`** (offset 96, размер 100 байт). Vulkan гарантирует ≥128 байт push-constant size, входим. Все 6 пайплайнов автоматически пушат 100 байт через `sizeof(PushConstantData)`.
- ✅ **Шейдеры объявляют `float time`.** В `triangle.vert` — внутри push-constant блока после `vec4 plasmaColor`. В `triangle.frag` — `layout(offset = 96) float time;`.
- ✅ **`std::chrono::steady_clock` baseline в `VulkanContext`.** Новые члены `m_renderStart` (time_point) и `m_renderStartInitialised` (bool). На первом `renderFrame()` устанавливаются; затем `elapsedSec = (now - m_renderStart).count() / 1e9` (через `std::chrono::duration<float>`). Steady_clock не подвержен NTP-коррекциям, что важно для плавной анимации.
- ✅ **`pc.time = elapsedSec` в каждом push-loop.** Все 6 точек инициализации `PushConstantData pc{}` в renderFrame получили `pc.time = elapsedSec` — opaque scene, system billboards, translucent, plasma, frame meshes, stars.
- ✅ **Plasma turbulence животная.** В фрагменте `if (pc.tint.x >= 0.5)`: `vec2 fireWarp = vec2(pc.time * 1.4, pc.time * 0.9)`, sample = `fbm4(vWorldPos.xz * 8.0 + fireWarp)`. Два разных (1.4 и 0.9) drift-коэффициента дают не-1D поток, без заметной перидичности. На 0.5-сек взрыве ~0.7 sample-units пройдёт — успеет визуально читаться как «огонь шевелится».
- ✅ **Нéбулы дрейфят медленно.** В `nebulaAlphaMod()`: `vec2 drift = vec2(pc.time * 0.04, pc.time * 0.025)`, `base = vWorldPos.xz * 0.9 + drift`. Скорость подобрана так, чтобы это читалось как фоновый космический ветер, не «полёт пыли». За 60 сек игры пройдёт ~2.4 sample-units по X — едва заметное движение.

Принятые решения:
- `float time` (4 байта) вместо `vec4 params` (16 байт). Push-constant у нас всё ещё 100 < 128 байт — есть запас. Если в будущем понадобятся ещё параметры (delta_time, frame_id, sin/cos cache) — добавим их отдельными float'ами или ещё одной vec4.
- Hex-щит не получил time-pulsation сейчас. Концепт-арт показывал ripple/wave при impact, но без impact-логики (когда пуля бьёт в щит) это просто бесполезный визуальный шум. Отложили до момента, когда появится impact-event-канал.
- Drift-коэффициенты для fire (1.4/0.9) и нéбул (0.04/0.025) подобраны на интуицию; могут потребовать тонкой настройки. Параметры в одном месте в шейдере, легко подкрутить.
- Не делали separate `m_lastFrameTime` для дельты — сейчас не нужно, time достаточно для всех применений. Дельта понадобится только если будем динамически обновлять Kotlin-side particle states в шейдере, что не входит в наш скоуп.

Что E6 разблокирует на будущее:
- 🟡 Pulse hex-щита при попаданиях (нужен также impact event-канал).
- 🟡 Бегущие электроразряды по procedural lightning mesh (когда будет Additive mesh pipeline).
- 🟡 Animated nebula colour shifts (gradient evolves over time).
- 🟡 Twinkling stars (per-vertex hash + sin(time + hash)).

### E7 — Additive Mesh Pipeline (завершено 2026-05-06)

Триггер: до E7 additive blend (ONE/ONE) был доступен только биллбордам через `m_plasmaPipeline`. Все эмиссивные эффекты — взрывы, муззл-флэш, попадания — рендерились плоскими камерно-выровненными квадами. Для настоящего 3D огненного шара взрыва, плазменного луча лазера (третье оружие из `idea.txt` task 10), электроразрядов, плазменных двигателей g3 нужен additive blend на произвольной геометрии.

Сделано:
- ✅ **7-й Vulkan pipeline `m_additivePipeline`** в `VulkanContext`. ONE/ONE color blend (`srcColorBlendFactor=ONE / dstColorBlendFactor=ONE / colorBlendOp=ADD`), depth-test ON read-only (`depthCompareOp=LESS, depthWriteEnable=FALSE`). Отличается от plasma-биллбордов которые имеют depth-test OFF (E5.2-followup): plasma-биллборды — pure overlay VFX, additive-меши — настоящая 3D геометрия которая должна корректно скрываться непрозрачными астероидами/турелями впереди. Создание/уничтожение симметрично прописано в `createPipeline`/`destroyPipelineInfra`.
- ✅ **`m_additiveDrawList`** + чистка в `beginScene`. Render-loop step между translucent и plasma билбордами: `opaque → system billboards → translucent → additive mesh → plasma billboards → frame`. Логика: additive overlay сидит над translucent (т.к. emissive, не должен скрываться альфой нéбул) и под plasma-биллбордами (т.к. depth-tested против depth-test-off билбордов).
- ✅ **C API `station_engine_draw_additive_mesh(engine, mesh, mat4, r, g, b, a, material)`** + JNI `nativeDrawAdditiveMesh` + Kotlin `EngineJni.drawAdditiveMesh(handle, mat4, r, g, b, a, material)`. Тинт пробрасывается через переиспользованный `pc.plasmaColor` (тот же канал что у E5.1 для plasma-биллбордов — экономим push-constant байты, push-const остался 100 байт). `material` поле encode-ится в `cmd.tint[3]` (1.0f = plain, 2.0f = fire — подробнее в E7.1).
- ✅ **Шейдер: новая ветка во фрагменте под `pc.tint.w >= 0.5`.** Plain-additive output: `outColor = vec4(vColor.rgb * pc.plasmaColor.rgb * vColor.a * pc.plasmaColor.a, vColor.a)` — premultiplied alpha (ONE/ONE blend игнорирует source alpha, так что видимый contribution = RGB*A; умножение alpha в RGB embed-ит falloff в итог). Меш-авторы кладут `A=1` в центрах свечения и `A=0` на краях для soft fade. Без отдельной material-логики ветка инфраструктурная — даёт чистый pass-through, конкретные эффекты (fire, beam, lightning) — material flags в `pc.tint.w`.
- ✅ **Scene/EngineView wiring.** `SceneObject` расширен полями `tintR/G/B/A` (forwarded в `pc.plasmaColor` для additive route, ignored на opaque/translucent), `additiveMaterial: Int` (forwarded как material параметр). `EngineView.additiveObjects: List<SceneObject>` Volatile, параллельно `translucentObjects`. `submitScene` принимает `additiveObjects` параметр и итерирует через `engine.drawAdditiveMesh(...)`.
- ✅ Build assembleDebug — зелёный, обе ABI.

Принятые решения:
- Reuse `pc.plasmaColor` (уже было выделено для E5.1 plasma-биллбордов), а не ввели отдельный `vec4 additiveColor` в push-const. Семантика того же поля: rgb = colour, a = brightness scalar — единое для всех additive-emissive путей.
- Depth-test ON read-only — компромисс между "плоский billboard на оверлейном слое" (plasma-биллборды) и "полноценная opaque геометрия". Additive 3D-меш всё ещё emissive (не отбрасывает тени, не пишет в depth), но фрагменты, скрытые ближайшей opaque геометрией, отбрасываются — фаербол за астероидом частично перекрыт, как ожидаем.
- Не объединили additive-меш и plasma-биллборд пайплайны (хотя оба ONE/ONE) — отличаются depth-test и наличием/отсутствием camera-billboard матрицы; render-loop логика разная.

Что E7 разблокировал на будущее:
- ✅ E7.1 — 3D fireball на AoE-взрывах (см. ниже).
- 🟡 Плазменный луч лазера — цилиндр-меш с тинтом по типу события (третье оружие из `idea.txt` task 10).
- 🟡 Электроразряды — zigzag mesh на `loadMeshRaw`, синий тинт.
- 🟡 Плазменные двигатели для g3 — cone-mesh за каждым кораблём.
- 🟡 Lightning bolts при impact'ах — generated zigzag.

### E7.1 — 3D Fireball (завершено 2026-05-06)

Триггер: AoE-взрывы (тяжёлая пушка + EXPLOSIVE-астероид при смерти) рендерились одной плоской плазма-биллбордой через E4 fire shader. Хотелось проверить новую E7-инфру на реальной фиче и заменить плоский диск на полноценный 3D огненный шар.

Сделано:
- ✅ **Material flag в `drawAdditiveMesh`.** Сигнатура расширена `material: Int = 0` (0 = plain, 1 = fire). Encode: `drawAdditiveMesh` пишет `cmd.tint[3] = 1.0f` для plain, `2.0f` для fire (вместо хардкода `pc.tint[3] = 1.0f` в render-loop). Render-loop теперь `memcpy(pc.tint, draw.tint, 16)` — материал пробрасывается через тот же tint канал. `EngineJni.ADDITIVE_PLAIN`/`ADDITIVE_FIRE` константы, `SceneObject.additiveMaterial: Int`.
- ✅ **Fire-material шейдер бранч** под флаг `pc.tint.w >= 1.5`:
  ```glsl
  float facing = abs(vNormal.y);
  vec3 hot  = vec3(1.0, 0.95, 0.70);
  vec3 cool = vec3(1.0, 0.40, 0.08);
  vec3 fireColor = mix(hot, cool, smoothstep(0.0, 1.0, 1.0 - facing));
  vec2 fireWarp = vec2(pc.time * 1.0, pc.time * 0.7);
  float n = fbm4(vWorldPos.xz * 6.0 + fireWarp);
  float fire = 0.55 + n * 0.95;
  float edgeFalloff = smoothstep(0.0, 0.55, facing);
  float a = vColor.a * edgeFalloff * pc.plasmaColor.a;
  outColor = vec4(fireColor * pc.plasmaColor.rgb * fire * a, a);
  ```
  Ключевая идея: `abs(vNormal.y)` под фиксированную камеру проекта (pitch=π/2 → камера смотрит вдоль ±Y) даёт Fresnel-like factor — 1 в видимом центре сферы (нормали вдоль ±Y, лицом к камере), 0 на силуэте (нормали перпендикулярны view). Сфера читается ярким ядром с soft edge, без волюметрик-рейкастинга. Heat ramp перекрашивает от white-yellow в центре к orange к краю, FBM-турбулентность даёт shimmer (медленнее чем у plasma-биллбордов — `*1.0/0.7` против `*1.4/0.9`, потому что fireball дольше живёт). Ограничение: `abs(vNormal.y)` зашит под пэроект — для g3 с подвижной камерой нужно переходить на real view direction через UBO.
- ✅ **Процедурная UV-sphere** — `MainActivity.buildFireballSphereMesh()`. 12 широт × 16 долгот = 221 верт, 384 трианглей, 1152 индекса (под 65k uint16 limit). Y-axis aligned (полюса на ±Y), per-vertex color white(1,1,1) + alpha=1 (тинт даёт `pc.plasmaColor`), normals = unit position (для unit-радиус сферы). Грузится один раз в `setupBackgroundNebulae()` (там же где остальные процедурные меши).
- ✅ **Runtime: `Fireball` data class** в MainActivity. Поля: `x, z, life, maxLife, baseRadius, intensity`. Поля цвета убраны после polish-итерации — цвет теперь curve, а не статика. `fireballs: MutableList<Fireball>` параллельно `flashes`. Tick: декремент `life`, cull при `life <= 0`. `startMission` чистит. `FIREBALL_LIFE_SEC = 0.5` (вдвое дольше FLASH_LIFE_SEC потому что событие более substantial и FBM-турбулентности нужно время чтобы прочитаться).
- ✅ **`spawnExplosion` переписан**: вместо плазма-биллборды добавляет `Fireball` в список. Никаких других изменений в коллизионных коллбеках.
- ✅ **`buildScene` маппит fireballs в `engineView.additiveObjects`** с тремя curve'ами на `t = age/maxLife`:
  - **Scale** ease-out quadratic: `0.4 + (1 - (1-t)²) × 1.0` × baseRadius → быстрый старт, асимптотический фронт. Имитирует deceleration shockwave (физически правильнее было бы Sedov-Taylor `t^(2/5)`, но для VFX ease-out читается чище и без бесконечной скорости в нуле).
  - **Colour** linear lerp `FIREBALL_TINT_START → FIREBALL_TINT_END`: `(1.00, 0.65, 0.20)` (saturated forge-orange) → `(0.90, 0.18, 0.05)` (deep dying-ember red). Шейдер сам каждый кадр перемножает `pc.plasmaColor.rgb` в heat-ramp, цвет shift-ится без шейдер-правок.
  - **Brightness** sqrt-fade: `√(1-t) × intensity`. Линейный fade был слишком быстрым — к моменту когда цвет дойдёт до тёмно-красного (t≈0.7-0.8), ball уже еле виден и переход не успевает прочитаться. sqrt держит яркость дольше в начале, гасит к концу.
- ✅ **Удалён `FLASH_TINT_EXPLOSION`** (dead code после E7.1).

Принятые решения:
- Material flag (1=fire) кодируется в `pc.tint[3]` (1.0f / 2.0f) вместо отдельного push-const поля — экономия байт, плюс симметрия с E3.1 паттерном (material flags через `pc.tint.y/z` для translucent). Если material'ов additive станет >2, можно будет ввести отдельное `additiveMaterial: float` поле в push-const.
- Y-axis aligned mesh обязателен под текущую `abs(vNormal.y)` логику. На rotation Y mesh'а Fresnel пляшет; задокументировано в комментариях. Альтернатива (real view direction) — отложили до момента когда g3 будет переиспользовать E7.
- ONE/ONE blend на сфере: cull mode = NONE (engine-wide), обе стороны draw'аются → центр получает 2× contribution бесплатно, плотность ядра растёт без второго шелла.
- 12×16 — компромисс tris/качество. На быстром глазу разница с 24×32 не видна, но 4× меньше геометрии.
- Color curve вынесена в Kotlin (per-frame в `buildScene`), не в шейдер. Шейдер не знает age конкретного fireball'а — только pc.time глобально. Раздельные fireball'ы с одинаковым возрастом должны выглядеть одинаково — это даёт Kotlin-side computation естественно.

Что E7.1 разблокировал на будущее:
- 🟡 Cyan additive sphere для ENERGY-астероида при смерти — переиспользует тот же mesh + другой тинт + другой material (нужен второй material — например ADDITIVE_ENERGY с холодным цветом и быстрее шевелящимся FBM).
- 🟡 Per-event variable-intensity fireball'ы — `Fireball.intensity` поле уже есть, осталось разные spawner'ы (маленькая пушка → 0.6× intensity, climactic blast → 1.5×).
- 🟡 Real view direction в Fresnel — открывает использование fire-material в g3.

### E8 — UV + textures (завершено 2026-05-07)

Триггер: до E8 геймплей упирался в потолок tinted-geometry. Все астероиды/пули/турели рендерились одноцветными моделями, иконки апгрейдов были цветными квадратиками, deграция базы (idea.txt task 4) была невозможна без текстур. Текстуры также — обязательная инфра под E9 particles (sprite-атласы) и под современные стандарты mobile-game визуала. Самый большой лифт за всё движковое, разбит на 4 подэтапа с верификацией на каждом.

#### E8.1 — UV vertex attribute (завершено 2026-05-07)

Сделано:
- ✅ **`Vertex` struct расширен** `float uv[2]` (sizeof 40 → 48 байт). Все опаковые callsite используют `Vertex v{}` value-init, UV автоматически нулится; никаких brace-init с тремя полями.
- ✅ **`Vertex::getAttributeDescriptions`** возвращает 4 attrs (вместо 3): новый attr 3 = `VK_FORMAT_R32G32_SFLOAT` на `offsetof(Vertex, uv)`.
- ✅ **`GltfLoader::convertPrimitive`** парсит `TEXCOORD_0` accessor когда он в .glb присутствует, иначе fallback `(0, 0)`. Лог теперь выводит `uvs=file/default(0,0)` для диагностики.
- ✅ **`station_engine_load_mesh_raw`** API стабилен (10 floats/vertex), внутренне явно нулит `v.uv = (0, 0)` для детерминированности. Все процедурные меши (soft-disk нéбулы, dome, fireball UV-sphere) работают без правок.
- ✅ **Vertex shader**: новый `inUV` (location 3) → `vUV` (location 4) проброс к фрагменту.
- ✅ **Fragment shader**: declared `vUV` (location 4), не используется в E8.1 — это backbone под E8.3.

Принятые решения:
- Backward-compat: оставили `loadMeshRaw(10-float)` как есть, добавили `loadMeshRawUV(12-float)` параллельно (E8.4). Существующие callers не сломались.
- Vertex stride 48 байт — единый layout для всех пайплайнов, fragment branchится по material flag (а не по vertex layout).

#### E8.2 — Texture infrastructure (завершено 2026-05-07)

Сделано:
- ✅ **`Texture` C++ класс** (`Texture.h/.cpp`): VkImage + VkDeviceMemory + VkImageView + VkSampler + per-texture VkDescriptorSet (set 1). Два пути создания: `createFromPixels(rgba8, w, h)` для процедурных и engine-default; `createFromPng(png_bytes, len)` через stb_image (`thirdparty/stb_image.h` уже был, активировал `STB_IMAGE_IMPLEMENTATION`). Полный upload cycle: staging buffer (HOST_VISIBLE) → VkImage (DEVICE_LOCAL, OPTIMAL tiling) → одноразовый command buffer выполняет layout transitions UNDEFINED→TRANSFER_DST→SHADER_READ_ONLY + `vkCmdCopyBufferToImage` → image view (RGBA8) → sampler (LINEAR, REPEAT, без anisotropy для mobile) → descriptor set из per-texture pool с заполненным `combinedImageSampler` write.
- ✅ **CMakeLists**: `Texture.cpp` добавлен в build.
- ✅ **Descriptor set 1 layout** (`m_textureSetLayout`): один `COMBINED_IMAGE_SAMPLER` binding 0, FRAGMENT_BIT.
- ✅ **Texture pool** (`m_texturePool` VkDescriptorPool): 64 sampler-slots с `FREE_DESCRIPTOR_SET_BIT` (чтобы `Texture::destroy` мог вернуть set в пул).
- ✅ **Pipeline layout** теперь принимает оба set layouts (set 0 UBO + set 1 texture). Все 7 pipelines автоматически подхватывают единый layout.
- ✅ **Default white 1×1 texture** (`m_defaultWhiteTexture`) загружается в `createPipelineInfra` после UBO. Биндится один раз на старте `renderFrame` — descriptor sets персистентны в command buffer'е, untextured draws inheirit без per-pipeline rebind.
- ✅ **Destroy path** правильный порядок: textures (через их pool) → pool → set 1 layout → UBO pool → set 0 layout → buffers.

Принятые решения:
- Один shared pipeline layout вместо per-pipeline разных — упрощает binding (`vkCmdBindDescriptorSets` без изменения layout). Cost: каждый pipeline видит set 1, даже если шейдер не семплит — но это бесплатно (просто слот в layout).
- Per-texture descriptor set (а не bindless с descriptor indexing) — стандартно, work на всех Android Vulkan-устройствах без extension'ов. Bindless оставили в backlog если когда-то понадобится thousands-of-textures сцена.

#### E8.3 — drawTexturedMesh + sampling shader (завершено 2026-05-07)

Сделано:
- ✅ **Texture pool** в VulkanContext (`m_textureSlots[kMaxTextures]`, `m_textureUsed`, размер совпадает с descriptor pool из E8.2). Методы `uploadTexture(png_bytes, length)` и `uploadTextureRaw(rgba8, w, h)` (E8.4) и `freeTexture(token)`.
- ✅ **C API**: `station_engine_load_texture(png_bytes, length)` → `StationTexture*` opaque handle (parallel to `StationMesh*`). `station_engine_unload_texture(...)`. Новый `StationTexture` struct в `engine_api.cpp`.
- ✅ **JNI + Kotlin**: `EngineJni.loadTexture(ByteArray): Long` + `unloadTexture(Long)`.
- ✅ **`drawTexturedMesh`** полная цепочка C++ → C API → JNI → Kotlin. `DrawCommand.textureToken` поле; render-loop step после opaque draw — биндит set 1 на per-texture descriptor (вместо frame-default white), пушит `pc.textureMode = 1.0`, рисует через тот же `m_pipeline` что и обычный opaque mesh. После цикла re-binds default white set 1 для определённости downstream pipelines.
- ✅ **Push-constant** расширен `float textureMode` (offset 100, total 104 байт, в пределах 128-байт минимума Vulkan).
- ✅ **Шейдер** — fragment получил `layout(set=1, binding=0) uniform sampler2D uTex;` и `pc.textureMode`. Lit ветка теперь выбирает albedo: при `textureMode >= 0.5` → `texture(uTex, vUV).rgb * pc.plasmaColor.rgb` (тинт); иначе fallback на `vColor.rgb`. Освещение (diff/fill/rim/ambient) применяется к albedo одинаково.

Принятые решения:
- Textured draws идут через **тот же** opaque pipeline (`m_pipeline`) — без дублирования pipeline state. Различие только в `pc.textureMode` флаге и `set 1` binding.
- `pc.plasmaColor` переиспользуется как textured tint (как в additive/plasma routes для тинта). Семантика того же поля: rgb = colour, a = brightness scalar.
- Lit branch (с освещением) применяется к textured opaque → текстурированные астероиды/декорации получат N·L diffuse, fill, rim — реалистично. Если в будущем понадобится unlit textured (sprite-overlay / UI), добавим отдельный sub-material через `pc.textureMode = 2.0` или новый флаг.

#### E8.4 — Procedural mesh/texture APIs + smoke test (завершено 2026-05-07)

Сделано:
- ✅ **`station_engine_load_mesh_raw_uv`** API через C/JNI/Kotlin (12 floats/vertex с UV — pos3 + rgba4 + normal3 + uv2). Параллельно существующему 10-float `load_mesh_raw`. JNI валидация `vlen % 12 == 0`.
- ✅ **`station_engine_load_texture_raw`** API через C/JNI/Kotlin (RGBA8 bytes + width + height). Параллельно `load_texture(PNG)`. JNI проверяет `length == width * height * 4`.
- ✅ **Procedural smoke-test patches** в `MainActivity.setupBackgroundNebulae`:
  - `buildTexturedQuadMesh()` — UV-mapped X-Z plane quad (4 верт, 2 трианглей, corner UVs (0,0)→(1,1)).
  - `generateRockTexture()` — 128×128 RGBA8 grayscale-noise tile, 3-octave value-noise (тот же `hash21` что в шейдере для визуального семейства), серо-теплый диапазон.
  - `generateIconTexture()` — 64×64 cyan disc с soft rim, прозрачные углы.
  - Два SceneObject теста: rock-patch на `(-1.5, 0, 6.0)` scale 0.55 (UV asymmetry test), icon-disc на `(1.85, 0, 8.6)` scale 0.30 (UI-position).
- ✅ **`SceneObject.textureHandle`** field, **`EngineView.texturedObjects`** Volatile list, **`submitScene` route** через `drawTexturedMesh`.
- ✅ **Verified end-to-end**: оба patches видимы на скриншоте (rock — серо-фиолетовый шум-квад слева в верхней зоне, icon — яркий cyan disc в правом верху). Texture sampling работает, descriptor set 1 ротация работает, UV interpolation работает.
- ✅ **Patches удалены** после верификации (decoration не нужен в production). Engine-side инфра (loadTexture/loadTextureRaw/loadMeshRawUV/drawTexturedMesh/SceneObject.textureHandle/EngineView.texturedObjects) сохранена для реальных consumers.

Принятые решения:
- Оба теста — процедурные (никаких real ассетов), потому что .glb с UV-разметкой и PNG-ассеты ещё не готовы. Это даёт чистую E8 инфра-верификацию без ассет-pipeline зависимостей.
- Test patches на permanent display (не за debug-флагом) — упрощает отладку pipeline'а в любом state. После верификации просто удалили.
- Lit branch dim'ит rock-patch (N·L diffuse + cool ambient) — это _правильное_ поведение для opaque textured 3D, дает реалистичный look для будущих textured астероидов. Если для UI/sprite понадобится unlit — добавим sub-material позже.

Что E8 разблокировал на будущее:
- 🟡 Real consumers: textured астероиды (требует UV-разметку .glb в Blender), PNG-ассеты иконок апгрейдов, sprite-атласы для анимированных взрывов (UV-shift по time).
- 🟡 E9 (particles) sprite-атласы — теперь возможны.
- 🟡 E10 (motion blur) переиспользует sampler descriptor infra от E8.
- 🟡 LUT (color grading), envmaps, любой shader lookup table — общий путь открыт.
- 🟡 Decals на повреждённой базе (idea.txt task 4) — projector-style mesh с прозрачным PNG.

### E9 — Native particle system (завершено 2026-05-07)

Триггер: до E9 каждый VFX-эффект — отдельный draw call (50 plasma billboards для AoE = 50 draws). Под плотные эффекты (искры, дым, обломки) нужен один draw call на всю систему. Также E9 — последний инфраструктурный шаг перед AAA-mobile уровнем; smoke/debris требуют sprite-атласы (E8 sampler infra) + instanced rendering (новое в E9).

Архитектурный выбор: **CPU simulation + GPU instancing.** Kotlin владеет state (matches "Kotlin owns scene"), тикает particles, раз в кадр пакует state в FloatArray и шлёт одним JNI-вызовом. Engine аплоадит в persistent-mapped instance buffer (HOST_VISIBLE), рисует один `vkCmdDrawIndexed` с `instanceCount = N`. Реалистично 1000-4000 particles на mobile (CPU work тривиален, GPU — fillrate-limited через ONE/ONE overlap). Альтернатива — GPU compute simulation (storage buffer + dispatch) — отброшена как переусложнение для этих counts.

Сделано:
- ✅ **2 новых Vulkan pipelines.** `m_particleAdditivePipeline` (ONE/ONE blend, depth-test off) для sparks/embers; `m_particleAlphaPipeline` (SRC_ALPHA / ONE_MINUS_SRC_ALPHA, depth read-only) для smoke/debris. Оба используют unit-quad mesh из binding 0 + per-instance binding 1 (rate INSTANCE, 8 floats per instance: pos3 + size1 + rgba4).
- ✅ **Новые шейдеры.** `particle.vert` — instanced billboarding через camera right/up из `ubo.view`; outputs `vColor=instColor`, `vUV=quad-mapped (0..1)`, `vLocalXZ` для soft-fade. `particle.frag` — две ветки: additive (heat-ramp warm-white→orange + soft-fade × per-instance vColor) и textured (sample uTex × vColor.rgb, vColor.a × fade × sampled.a) под флагом `pc.textureMode`.
- ✅ **2 persistent-mapped instance buffer'а** (`m_particleAdditiveInstanceBuffer`, `m_particleAlphaInstanceBuffer`), HOST_VISIBLE+HOST_COHERENT, 4096 particles × 32 bytes = 128KB each. `vkMapMemory` один раз на старте; renderFrame делает `memcpy` staging → mapped, `vkCmdDrawIndexed` с `instanceCount`.
- ✅ **`drawParticles` API** через C → JNI → Kotlin. Один JNI вызов на pool за кадр (а не 1000+ отдельных draw'ов). `setShader` принимает `particle.vert`/`particle.frag`; `createPipeline` строит particle pipelines если SPV переданы.
- ✅ **Render-loop**: два pass'а (additive затем alpha), внутри каждого — bind pipeline once, loop batches с `vkCmdBindVertexBuffers(binding=1, instance buffer, byteOffset)`, descriptor set 1 binds к per-batch текстуре (default white для additive). После цикла restore default white set 1.
- ✅ **Kotlin particle layer**: `Particle` data class (pos, vel, age, life, size, rgba, drag, gravity); 3 пула (`sparkParticles`, `smokeParticles`, `debrisParticles`). `tickParticles` per-pool: Euler + drag + gravity + cull. `packParticles`: count×8 floats, alpha=`sqrt(1-t)*tintA` для smooth fade.
- ✅ **Procedural textures** (без real ассетов): `generateSmokeTexture` (64×64 soft Gaussian + 2-octave noise wisps, light-gray cool tint, transparent edges), `generateDebrisTexture` (64×64 irregular polygonal asteroid-chunk silhouette, warm gray, top-left light gradient, 1-pixel AA edge).
- ✅ **3 consumers** в gameplay:
  1. **AoE sparks** в `spawnExplosion`: 50-70 искр fan'ом за каждый AoE-взрыв, оранжевые, drag 1.5, life 0.25-0.55s.
  2. **Asteroid death debris+smoke**: NORMAL/FAST/HEAVY смерть → 4-8 textured chunks (с gravity 1.2, drag 0.6) + 3-5 smoke puffs (drift, drag 0.8). HEAVY получает тёмно-красный тинт debris.
  3. **Muzzle flash sparks**: каждый выстрел (центральная + боковые турели) → 3-5 micro-искр в 40°-конусе по vector velocity, drag 2.5, life 0.08-0.16s.

Принятые решения:
- Per-instance stride 8 floats — компромисс между gpu фетч-стоимостью и расширяемостью. Для атласных UV offsets можно расширить до 12 floats отдельной волной если понадобится.
- Particle vertex shader отдельный от triangle.vert: per-instance binding 1 нужен только particle pipelines, не загромождаем основной шейдер.
- Particle fragment shader тоже отдельный (хотя мог бы reuse triangle.frag) — две branched logics частей не пересекаются с основной lit/plasma логикой, чище отдельный файл.
- Procedural textures хранятся в Kotlin, генерятся на старте через `loadTextureRaw` (E8.4). Когда появятся real PNG-ассеты — drop-in replacement через `loadTexture(pngBytes)`.
- Spawn API в Kotlin (`spawnSparkBurst`, `spawnMuzzleSparks`, `spawnAsteroidDeathFX`) использует `Math.random()` без seeded RNG — ок для VFX где детерминизм не нужен.

Что E9 разблокировал на будущее:
- 🟡 Sprite-атласные particles: per-instance UV offset поле, кадровая анимация по age (fire trail, electric arcs).
- 🟡 Particle types через атласы — реальные PNG-textures для smoke/debris вместо процедурных.
- 🟡 Performance scaling: если 4096 окажется тесно, можно расширить kMaxParticles или GPU compute simulation отдельной волной.
- 🟡 Energy-asteroid death cyan particles — отдельный sub-burst со своим тинтом + faster FBM.
- 🟡 Bullet impact sparks — мелкий burst при попадании bullet → asteroid hit.

### E10.1 — Motion blur: offscreen render target + post-process pass (завершено 2026-05-07)

Триггер: первый шаг перед motion blur shader. До E10 scene рисовалась прямо в swapchain image — нет места куда вставить post-process. Restructure: scene в offscreen image, который post-pass семплит и пишет в swapchain. После E10.4 fragment станет motion blur shader; E10.1 — пока passthrough, чтобы verify restructure не сломал визуал.

Сделано:
- ✅ **`RenderResources` расширен**: `postRenderPass`, `postFramebuffers[]` (per-swapchain-image), `offscreenColorImage/Memory/View/Sampler` (single shared, B8G8R8A8_UNORM, COLOR_ATTACHMENT+SAMPLED). Existing `renderPass` теперь scene-pass с finalLayout=SHADER_READ_ONLY_OPTIMAL; `framebuffers` теперь содержит ровно один scene fb (offscreen colour + depth).
- ✅ **Builder**: 4 новых метода — `createPostRenderPass` (single colour attachment, finalLayout=PRESENT_SRC_KHR), `createOffscreenColorResources` (image+memory+view+sampler через стандартный VkImage path), `createSceneFramebuffer` (одиночный shared), `createPostFramebuffers` (per-swapchain-image). `createRenderPass` принял `finalLayout` параметр для разделения scene/post.
- ✅ **`VulkanContext`**: `m_postPipeline`, `m_postPipelineLayout` (отдельный, минимальный — 1 set, no PCs), `m_postSetLayout` (1 binding = COMBINED_IMAGE_SAMPLER), `m_postDescriptorPool` + `m_postDescriptorSet`, `m_postVertModule/m_postFragModule`. Post pipeline создаётся в конце `createPipeline` если SPV переданы. Descriptor set биндится один раз на offscreen colour image+sampler.
- ✅ **`createDepthAndFramebuffers` перестроена**: scene pass + offscreen colour + scene fb + post pass + post fbs. `createCommandInfra` теперь sizes по `postFramebuffers.size()` (= swapchain images count).
- ✅ **`renderFrame`**: scene pass на shared scene-fb (`framebuffers[0]`), все scene draws (mesh, billboards, particles, fireballs, etc.). После `vkCmdEndRenderPass` сцены — отдельный post pass на per-imageIndex post-fb, биндит post pipeline + descriptor (offscreen sampler), один `vkCmdDraw(3, 1, 0, 0)` (fullscreen triangle через gl_VertexIndex).
- ✅ **Шейдеры**: `post.vert` — fullscreen triangle через `gl_VertexIndex` без vertex bindings (стандартный трюк: `(idx<<1)&2, idx&2` → UV (0,0)/(2,0)/(0,2), UV*2-1 → NDC покрывая весь экран в одном trianлге). `post.frag` — пока passthrough `texture(sceneColor, vUV)`. После E10.4 заменим на motion-blur с 8 samples вдоль velocity.
- ✅ **Layout transitions** через render pass attachment finalLayout — implicit, ничего не делать руками. Scene pass: UNDEFINED → SHADER_READ_ONLY_OPTIMAL. Post pass: UNDEFINED → PRESENT_SRC_KHR. Post pass deps[0] обеспечивает COLOR_ATTACHMENT_OUTPUT → FRAGMENT_SHADER_READ синхронизацию между passes.

Принятые решения:
- Один offscreen image (а не per-swapchain-image): `inFlightFence` гарантирует не больше одного frame в pipeline, можно безопасно реиспользовать. Экономит memory.
- Post pipeline layout отдельный (не reuse main `m_pipelineLayout`): post не нуждается в UBO set 0 или push-constants, минимизируем cost binding'а.
- Format = swapchain format (B8G8R8A8_UNORM): trivially compatible blit, никаких conversions. Для HDR в будущем — отдельный путь.
- Post render pass использует CLEAR loadOp защищающим от UB на первом frame после acquireNextImage (хотя fullscreen triangle покрывает весь экран). Bandwidth penalty минимальный (1× clear на ~6MB).

Что E10.1 разблокировал на будущее:
- ✅ E10.2 (velocity attachment) — добавим 2-й color attachment в scene pass.
- ✅ E10.4 (motion blur shader) — fragment shader заменяется без structural changes.
- 🟡 Любой post-process effect (bloom, chromatic aberration, vignette, color grading) — общий путь открыт.
- 🟡 Render-to-texture для других целей (reflection, shadow maps если когда-то нужны) — pattern доказан.

### E10.2 — Motion blur: velocity attachment infrastructure (завершено 2026-05-07)

Триггер: motion-blur shader (E10.4) читает per-pixel screen-space velocity из второго color attachment scene pass'а. Перед тем как считать velocity, нужно физически создать attachment, прокинуть через render pass + framebuffer + все 9 scene-пайплайнов, и фрагмент-шейдеры должны его записывать. E10.2 — чистая инфраструктура: attachment существует, шейдеры пишут zero placeholder, реальный compute velocity ждёт prev-matrices в E10.3.

Сделано:
- ✅ **`RenderResources.h/.cpp`**: новые поля `offscreenVelocityImage/Memory/View/Sampler` (формат `R16G16_SFLOAT`, usage `COLOR_ATTACHMENT + SAMPLED` — same shape как offscreen colour, переиспользует `createOffscreenColorResources` factory с другим форматом). `createRenderPass` принимает `velocityFormat` параметр; `VK_FORMAT_UNDEFINED` отключает (single-attachment режим), valid format добавляет 2-й color attachment с `loadOp=CLEAR`, `storeOp=STORE`, `finalLayout=SHADER_READ_ONLY_OPTIMAL` — тот же layout что у colour, чтобы post pass мог семплить. `createSceneFramebuffer` принимает `offscreenVelocityView` параметр; framebuffer теперь 3 attachments (colour, velocity, depth). `destroy` чистит velocity ресурсы той же логикой что colour.
- ✅ **`VulkanContext.cpp::createDepthAndFramebuffers`** создаёт velocity attachment через `createOffscreenColorResources(R16G16_SFLOAT)` параллельно colour, прокидывает velocity view в `createSceneFramebuffer` и `velocityFormat` в `createRenderPass`.
- ✅ **`VulkanContext.cpp::createPipeline`** — все 9 scene-пайплайнов (`m_pipeline / m_systemPipeline / m_plasmaPipeline / m_translucentPipeline / m_additivePipeline / m_framePipeline / m_starPipeline / m_particleAdditivePipeline / m_particleAlphaPipeline`) получили 2-й `VkPipelineColorBlendAttachmentState`. Замена одиночного `cbAtt` на `cbAtts[2]` с `auto& cbAtt = cbAtts[0]` reference — все существующие per-pipeline blend-state мутации (`cbAtt.blendEnable = ...` для plasma/translucent/additive/particles) меняют `cbAtts[0]`, второй слот остаётся неизменным: `blendEnable=FALSE`, `colorWriteMask=R|G`. `cbCI.attachmentCount=2; cbCI.pAttachments=cbAtts`. Post pipeline (`m_postPipeline`) использует свои `postCb`/`postCbCI` с 1 attachment (только swapchain), не тронут.
- ✅ **`renderFrame::clearValues[3]`** — colour `(0.01, 0.01, 0.04, 1)`, velocity `(0, 0, 0, 0)` (RG only, BA игнорируются), depth `1.0`. Velocity `(0, 0)` clear означает "no motion" — корректное background для motion-blur shader (пиксели где ничего не рисовали будут читаться как static в E10.4).
- ✅ **`m_postSetLayout` расширен до 2 bindings**: binding 0 = sceneColor, binding 1 = sceneVelocity. Pool sized 2 descriptors. `m_postDescriptorSet` пишет оба binding'а через `vkUpdateDescriptorSets(2, ...)`. post.frag в E10.2 всё ещё passthrough и binding 1 не семплит — Vulkan permits descriptor bindings shader не использует, только обратное (shader использует не-объявленный binding) — error. Готовность к E10.4 motion-blur shader без дальнейших descriptor-изменений.
- ✅ **Шейдеры**: `triangle.frag` + `particle.frag` объявили `layout(location=1) out vec2 outVelocity`, в начале `main()` пишут `outVelocity = vec2(0.0)` — placeholder для всех early-return ветвей. Перекомпилированы `.spv`.
- ✅ **Verification**: `./gradlew assembleDebug` — BUILD SUCCESSFUL за 19s, без CMake/glslc warnings. Запуск на устройстве — visually идентично pre-E10.2.

Принятые решения:
- **`R16G16_SFLOAT` а не `R32G32_SFLOAT`**: NDC velocity ∈ [-1, +1], half-float точности 11 бит мантиссы хватает с запасом (~5e-4 quantization). Половина bandwidth (4 bytes/pixel вместо 8), что на mobile полезно. Для motion-blur post pass будем семплить с linear filter — half-float fine.
- **`createOffscreenColorResources` переиспользован для velocity** вместо отдельной фабрики `createOffscreenVelocityResources`: ресурс-shape идентичный (image + memory + view + sampler), различается только формат (передаётся параметром). Удалить дубликат до того как он появится — DRY.
- **`createRenderPass` `velocityFormat=UNDEFINED` опциональный**: оставляет single-attachment режим работоспособным на случай если когда-то понадобится render pass без velocity (тестовый, debug, fallback). Сейчас не используется, но 6 строк бранчинга — дешёвая страховка.
- **post descriptor set 2 bindings сейчас, не в E10.4**: bind sampler без shader-чтения = legal Vulkan, нулевой runtime cost. Тестирует binding wiring сразу. E10.4 заменяет post.frag на motion-blur reader без descriptor изменений.
- **Velocity placeholder `vec2(0.0)` в начале main()** а не перед каждым `return`: фрагмент-шейдеры триangle.frag/particle.frag имеют 4-7 early-return бранчей. Один write в начале — всегда well-defined. Чуть-чуть избыточный ALU, но сразу безопасно.
- **2-й blend attachment везде**: даже у пайплайнов которые "логически" не имеют отношения к motion (stars, frame lines, particle-additive с depth-test off). Vulkan требует чтобы pipeline'овский `attachmentCount` совпадал с render-pass `colorAttachmentCount` — нельзя выборочно. Все scene-пайплайны рендерят в один scene render pass → все обязаны декларировать 2 attachments. Velocity слот для них тривиальный no-blend write `vec2(0)` — почти zero overhead.

Что E10.2 разблокировал на будущее:
- ✅ E10.3 — UBO + push-const расширение `prev*` matrices; vertex shaders считают реальный screen-space velocity = (currClip.xy/w - prevClip.xy/w) * 0.5. Engine tracks per-object prev_model между кадрами.
- ✅ E10.4 — post.frag заменяется на motion-blur shader с 8-tap sample вдоль velocity. Velocity texture уже видна через post descriptor binding 1.
- 🟡 G-buffer extensions (если когда-то понадобятся normal/material attachments для deferred shading) — общий паттерн multi-attachment scene pass проверен.

### E10.3 — Motion blur: prev-frame matrices + per-object velocity (завершено 2026-05-07)

Триггер: E10.2 положил placeholder `vec2(0.0)` в velocity attachment. E10.4 motion-blur shader будет читать этот attachment чтобы размазывать пиксели вдоль вектора motion. Чтобы attachment стал осмысленным, нужны (а) prev_view/prev_proj в UBO для камерной velocity, (б) prev_model per-draw для object velocity, (в) vertex shader, который считает delta NDC, (г) Kotlin tracking prev state для движущихся объектов. Архитектурный вопрос — где хранить prev_model. Push-const exceed 128 bytes, что портабельно небезопасно (Mali-G76 и старее держат только 128). Выбран **dynamic-offset UBO** (set 2): per-frame ring buffer на 4096 слотов × 64 байта (pad to minUboOffsetAlign), offset выбирается в `vkCmdBindDescriptorSets` per draw. Push-const остаётся 104 байта (никаких portability рисков).

Сделано:
- ✅ **UBO grew (set 0)**: `UniformBufferObject` теперь 4 mat4 (`view, proj, prev_view, prev_proj`) — 256 байт. `updateUniformBuffer` пишет все 4 каждый кадр. `m_prevView` / `m_prevProj` поля в `VulkanContext` кэшируют сегодняшние матрицы как завтрашние prev. Первый кадр: `m_prevCameraInitialised=false` → prev = current → нулевая camera-velocity baseline (корректно для отсутствия истории).
- ✅ **Per-draw dynamic UBO (set 2)**: Новый `m_perDrawUboBuffer` (HOST_VISIBLE+COHERENT, persistent-mapped). Размер = `kMaxDrawsPerFrame=4096 × paddedStride` где `paddedStride = align(sizeof(mat4)=64, props.limits.minUniformBufferOffsetAlignment)` (64 на Adreno, до 256 на Mali — обе в безопасной зоне). Слот 0 — sentinel identity, заполняется один раз в `createPipelineInfra`; cursor стартует со слота 1 в `beginScene`. `m_perDrawSetLayout` = single binding `UBO_DYNAMIC` vertex stage; `m_perDrawDescriptorSet` указывает на весь буфер с `range = sizeof(mat4)`, dynamicOffset выбирает слот.
- ✅ **Pipeline layout 3 sets**: shared `m_pipelineLayout` теперь содержит `[0]=UBO, [1]=texture, [2]=perDraw`. Все 9 scene-пайплайнов автоматически подхватили (общий layout). Post pipeline остался со своим минимальным layout (1 set, без push-const) — он не семплит prev_model.
- ✅ **Per-draw slot allocation**: новая helper `allocPerDrawSlotImpl(mapped, stride, cursor, max, prevModel, currentModel)` вызывается из `drawMesh / drawTranslucent / drawAdditive / drawTextured / drawPickable`. Если caller передал `prevModelMatrix` — пишется он, иначе current model (что даёт zero velocity для статики через идентичность prev_clip = curr_clip). Cursor инкрементится; на overflow возвращает offset последнего валидного слота (graceful degradation на >4096 draws/frame, чего у нас не бывает).
- ✅ **Render loop binds**: после `vkCmdBindDescriptorSets(set 1, defaultWhite)` в `renderFrame`, биндится set 2 с offset 0 (sentinel). Каждый mesh draw в 4 mesh-style loops (`m_drawList / m_texturedDrawList / m_translucentDrawList / m_additiveDrawList`) делает `vkCmdBindDescriptorSets(set 2, &draw.perDrawUboOffset)` перед `vkCmdDrawIndexed` — billboard/particle/frame draws инхеритят offset 0, фрагмент пишет `vec2(0)` в их branches.
- ✅ **Draw API extended**: `drawMesh / drawPickableMesh / drawTranslucentMesh / drawAdditiveMesh / drawTexturedMesh` получили optional `prevModelMatrix[16]` параметр в C / JNI / Kotlin (nullable `FloatArray? = null` на Kotlin стороне). `drawPlasmaBillboard / drawBillboardMesh / drawObjectFrameMesh / drawGameplayFrameMesh / drawParticles` оставлены без изменений — у них нет prev_model semantics на E10.3 (для plasma это camera-aligned matrix без tracking, для particles per-instance prev_pos придёт в E10.5).
- ✅ **`triangle.vert`**: декларирует `set=2, binding=0 PerDraw { mat4 prev_model }`. Вычисляет `prevClip = ubo.prev_proj * ubo.prev_view * pd.prev_model * vec4(inPosition, 1.0)`, NDC-делит, `vVelocity = (currNdc - prevNdc) * 0.5` (×0.5 чтобы сидело в [-1,+1] units не [-2,+2]). Делитель `max(w, 1e-4)` защищает от near-clip / behind-camera vertex.
- ✅ **`triangle.frag`**: дефолт `outVelocity = vVelocity` в `main()` (для mesh / additive / translucent / textured branches которые имеют валидный prev_model). Frame branch и plasma branch (`pc.tint.x >= 0.5`) перезаписывают на `vec2(0.0)` — у них prev_model либо not meaningful (camera-aligned billboard matrix), либо UI overlay без motion semantics.
- ✅ **`particle.vert`**: расширил UBO struct до 4 mat4 чтобы матчить scene UBO layout (даже без чтения prev_*, struct должен совпадать). Particle vertex shader не читает prev_model и не вычисляет velocity — particle.frag пишет `vec2(0)` placeholder. Per-instance prev_pos с float layout 8→14 — отдельная волна E10.5.
- ✅ **Kotlin gameplay tracking**:
  - `Asteroid` — `prevZ`, `prevRotation` (xPos не меняется — астероиды падают строго вниз).
  - `Bullet` — `prevX`, `prevZ` (rotationY константен — фиксированный velocity vector).
  - `Fireball` — `prevLife` (drives ease-out scale curve).
  - Tick code в `MainActivity` снимает prev BEFORE применения движения в трёх местах (bullet move, asteroid fall, fireball aging). Bullet trail spawn использует `b.x / b.z` *после* движения — корректно, prev уже снят.
- ✅ **`SceneObject.prevModelMatrix: FloatArray? = null`**: дефолт null = static = engine трактует prev = current = zero velocity. `submitScene` пробрасывает поле в каждый draw API call.
- ✅ **`buildScene` mapping**: для asteroids / bullets / fireballs строит вспомогательный `SceneObject` из prev fields, забирает его `modelMatrix()`, пихает в `prevModelMatrix` boxed SceneObject. Платформа, турели, нéбулы, щит, плёш-биллборды — всё с дефолтным null = zero velocity.

Принятые решения:
- **Dynamic UBO вместо большого push-const**: 168-байтный push-const (model 64 + prev_model 64 + tint 16 + plasmaColor 16 + time 4 + textureMode 4) сломал бы старые Adreno (до 660) и Mali (до G77) с 128-byte limit. Engine shared с g3 → portable выбор обязателен. Cost = +1 vkCmdBindDescriptorSets per scene draw — на наших ~30 объектах per frame пренебрежимый.
- **Slot 0 = identity sentinel**: альтернатива — каждый non-mesh draw allocate свой slot со своим current_model. Цена — лишние записи и инкременты для billboard/particle/frame draws (которые всё равно пишут zero velocity в frag). Простота и cache hit перевешивают.
- **prevModelMatrix `FloatArray?` (nullable) в Kotlin / C**: альтернатива — non-null с обязательным "pass current model дважды для static". Nullable короче, читается яснее ("ничего не отслеживаем"), и null → C nullptr → engine ветка с current model — простая.
- **Particles trackа НЕТ в E10.3**: per-instance prev_pos требует расширить particle layout с 8 до 14 floats (pos3 + size1 + rgba4 + prevPos3 + ?). Это ломает CPU-side packParticles и instance buffer stride. Отдельная волна E10.5.
- **`vVelocity * 0.5`**: NDC ∈ [-1,+1], delta ∈ [-2,+2]. Шейдер motion-blur читает velocity и итерирует по UV-пространству [0..1]. Делителем 0.5 переводим в half-NDC что = full-UV. Cleaner для blur shader реализации в E10.4.
- **`pickRadius * scale` для drawPickableMesh prev**: prev radius использует current scale (мы и так bounding-radius проверки делаем для текущего кадра). Не critical — pick radius не участвует в velocity computation.

Что E10.3 разблокировал на будущее:
- ✅ E10.4 — motion blur shader. Velocity attachment теперь нагружен реальными NDC velocities, post.frag читает через уже привязанный `binding=1` sampler.
- ✅ E10.5 — particle prev_pos. Инфра set 2 / push-const / shaders готова; particle layout 8→14 + tickParticles patches.
- ✅ E10.6 — verify полностью pipeline на устройстве. RenderDoc capture покажет velocity buffer заполненным.
- 🟡 **TAA / temporal upsampling**. Per-frame jitter offset в proj matrix + history buffer + reproject через motion vectors — мы уже имеем половину инфры (motion vectors from E10.3, history через render-to-texture E10.1).
- 🟡 **Per-draw uniform data беспредельно**: dynamic UBO set 2 паттерн доказан и тривиально расширяется (можно увеличить slot stride, добавить второй binding). Любые per-draw scalars/matrices помимо matrices — туда же.

### E10.4 — Motion blur: post-process shader (завершено 2026-05-07)

Триггер: E10.3 заполнил velocity attachment реальными NDC motion vectors. E10.4 — наконец читает их и размывает scene colour. Базовый naive blur (наивный 8-tap symmetric centred sample вдоль velocity) работает для крупных объектов но **проваливается на мелких быстрых пулях**: ~5px пуля при velocity ~30px/frame размывается на 130px range, 7 из 8 sample-точек попадают на фон → bullet pixel читается как 12% bullet + 88% background = призрачное мерцание. Нужно **velocity dilation** (max в окрестности → static пиксели возле движущегося объекта используют его velocity, blur halo расширяется в соседние пиксели) + **weighted blur** (moving samples весят больше static).

Plus побочный bug: 8 non-opaque scene pipelines (system / star / plasma / translucent / additive / frame / particle additive / particle alpha) имели `cbAtts[1].colorWriteMask = R+G` с `blendEnable=FALSE`. Их фрагмент пишет `vec2(0)` в velocity attachment → overlay overwrite → плазма-биллборды (muzzle flashes / trail flashes) поверх движущихся пуль/астероидов перетирали их velocity → "shimmer halo" около турелей где плотные плазма-эффекты + bullets going through. **Исправлено**: `cbAtts[1].colorWriteMask = 0` сразу после opaque pipeline create — все 8 overlay pipelines наследуют, opaque velocity сохраняется.

Сделано:
- ✅ **`post.frag` shader rewrite**: 5×5 velocity dilation finds max-magnitude vector в окрестности (≈±5px на 1080p), weighted 8-tap blur (moving samples weight 1.0, static 0.2) prevents bullet washout, length-clamp at `kMaxBlur=0.05` (5% screen), kIntensity=1.5 (1.0 = physical shutter, 1.5 reads cleaner at 60Hz). Static fast path для пикселей с velocity < kEpsilon (1e-4) пропускает 16 sample fetches — большинство пикселей. ~33 fetches/pixel на blurred регионы (25 dilation + 8 color + 8 velocity weight); ~5G fetches/sec на 60Hz 1080p, в bandwidth budget Adreno 619.
- ✅ **Overlay clobber fix**: `VulkanContext::createPipeline` после m_pipeline (opaque) выставляет `cbAtts[1].colorWriteMask = 0`. Все 8 последующих pipelines наследуют 0 → их фрагмент пишет vec2(0), но color blend attachment 1 не записывается. Velocity attachment под overlay-биллбордами сохраняет underlying opaque values. Post pipeline (`m_postPipeline`) использует свой `postCb`/`postCbCI` с 1 attachment к swapchain — не тронут.
- ✅ **Bullet trail VFX переделан**: периодические `trailTimer`-driven Flash entries вдоль bullet path удалены вместе с TRAIL_INTERVAL_SEC / TRAIL_LIFE_SEC / TRAIL_HALF / FLASH_TINT_TRAIL constants и `trailTimer` field на Bullet. Direct (non-AoE) hits спавнят `Flash` с `halfMax = b.halfW * HIT_FLASH_SIZE_MUL` (cannon ~0.20, mg ~0.12) с `FLASH_TINT_HIT` (warm orange). AoE bullets continue with fireball + sparks через `spawnExplosion`. Motion blur и hit flash вместе нагружают impact moment без trail-spam.

Принятые решения:
- **Dilation 5×5 а не 7×7 / tile-based**: 25 fetches per pixel — на грани bandwidth budget mobile, 7×7 (49 fetches) уже превышает. Tile-based motion blur (Guertin / McGuire technique — pre-pass находит max velocity per 16×16 tile в отдельном passe) — следующий tier качества, но требует separate compute/render pass = engine infrastructure work. 5×5 хватает для ±5px halo extension у пуль.
- **Weighted blur (kStaticWeight=0.2)** vs uniform averaging: pulls bullet color contribution с 12% (uniform) до ~50-60% (weighted), без необходимости neighbour-max или depth-aware sampling.
- **Overlay clobber fix вместо per-pipeline conditional writeMask**: в эту волну motion blur нужен только на opaque mesh draws. Fireball / particles / other overlays могли бы тоже писать velocity (хочется motion blur на fireball), но additional logic = additional bug surface. Простой "opaque writes, overlays preserve" решает 90% случаев чисто.
- **Trail VFX удалён, не уменьшен**: периодические trail flashes СОЗДАВАЛИ visual noise который motion blur не мог корректно обрабатывать (точки sparse spaced over path с собственным lifecycle). Удаление дает blur clean signal для размытия. Hit flash + motion blur — достаточный feedback "shot impacted".

Что E10.4 разблокировал на будущее:
- ✅ E10.5 — particle prev-pos. Particle layout 8→14 floats с per-instance prevPos для motion-blurring sparks/smoke/debris.
- ✅ E10.6 — RenderDoc capture / на-устройстве verify.
- 🟡 Tile-based velocity max — separate compute pass для большего blur halo. Нужен только если пользователь жалуется на quality.
- 🟡 Per-pipeline conditional writeMask — если additive (fireballs) хочет motion blur тоже, ввести fine-grained mask per-pipeline.

### E11 — Rotated plasma billboards + cone muzzle blast (завершено 2026-05-07)

Триггер: пользователь попросил cannon-with-muzzle-brake muzzle flash в форме секторов. Plasma billboards до E11 были круглые quad meshes camera-aligned, с `scaleH/scaleV` non-uniform но без rotation. Для 3 секторов на 120° apart по barrel direction (которая может быть произвольной при manual aim центральной турели) нужна arbitrary world rotation в локальной X-Z плоскости mesh'а. `Camera::billboardMatrix` не имела rotation параметра.

Сделано:
- ✅ **`drawPlasmaBillboard(..., rotation: float)`** — новый параметр через C / JNI / Kotlin EngineJni. `DrawCommand.rotation` field, default 0 = legacy behavior. Render-loop plasma section композирует `multiply(billboard, Mat4::rotationY(rotation))` — локальный Ry перед billboard align. `Camera::billboardMatrix` оставлена как есть (rotation применяется на этапе render-loop, не в Camera).
- ✅ **`buildMuzzleConeMesh(segments=12)`**: triangle fan в X-Z плоскости, ±15° aperture (30° total wedge) вокруг локального +Z, радиус 1, alpha 1 везде. Plasma fragment soft-fade (`smoothstep(0.4, 1.0, length(vLocalXZ))`) делает alpha-gradient от bright tip (alpha 1) к transparent rim (alpha 0).
- ✅ **3-cone trefoil muzzle blast**: `spawnMuzzleBlast` spawnит 3 Flash entries на muzzle position с `rotation = atan2(dirX, dirZ) ± 0/120/-120°`. Forward apex смотрит вдоль bullet velocity, два других — в back-side jets pattern. FBM turbulence + heat ramp работают на rotated cone unchanged. **Размер × 3** относительно pre-E11: `MUZZLE_FLASH_HALF` 0.13 → 0.39.
- ✅ **`Flash.meshHandle: Long = 0L` + `Flash.rotation: Float = 0f`** + **`BillboardDraw.rotation: Float = 0f`** — optional fields с дефолтами для existing round flashes (death / energy / shield / hit / etc.). Mapping `flashes → BillboardDraw` пробрасывает meshHandle (с fallback на quadFlashHandle если 0) и rotation.
- ✅ **Side turrets теперь cannon-style** — gameplay re-balance, частично продиктована motion-blur health (один heavy bullet/sec читается чище под blur чем поток мелких). `FIRE_INTERVAL_SEC` 0.15 → 1.0; `SIDE_BULLET_*` constants (speed 18, halfW 0.065, halfH 0.117); `SIDE_DAMAGE_MUL=3`, `SIDE_AOE_RADIUS=0.5`, `SIDE_AOE_DAMAGE_MUL=0.6`. Mesh = `bulletHeavyMeshHandle`. Side turrets visually match central HEAVY_CANNON profile.

Принятые решения:
- **Engine change vs Kotlin-only workaround**: рассматривался additive mesh pipeline + custom cone — без FBM turbulence, gladkii smooth wedge без огненного wispy look. Plasma rotation engine change даёт полноценный wispy fire wedge. Stoимость engine change ~15 строк суммарно через C/JNI/Kotlin/render — minimal.
- **`multiply(billboard, Ry)` order**: применяется на этапе render-loop а не внутри `Camera::billboardMatrix`. Camera methods остались immutable; rotation — local concern of plasma path. Если plasma не использует rotation (rotation == 0), идём по fast path: только billboard без mat-mul.
- **Cone aperture 30° (±15°)**: 60° слишком широко (overlap с соседними cones at 120°), 15° слишком узко (читается как линия, не сектор). 30° балансируется visual readability и trefoil distinction.
- **Forward = local +Z**: matches engine convention для billboard meshes (X-Z plane meshes), `rotation=0` mapping → screen-up по Camera::billboardMatrix col 2 = camera-up. `atan2(dirX, dirZ)` в Kotlin gives correct screen-direction angle.
- **`MUZZLE_FLASH_HALF * 3`**: пользователь попросил радиус ×3. После trial-and-error на устройстве с `kIntensity=1.5` motion blur размер cone'а меньше = читается мелко.

Что E11 разблокировал:
- 🟡 Любой directional plasma effect: плазменные laser muzzle, electric arcs, jet thruster afterburn — все могут использовать rotation + custom mesh через plasma pipeline.
- 🟡 Streak bullets через plasma вместо opaque mesh — direction-aligned elongated quad через scaleH/V + rotation.
- 🟡 Animated rotation: можно tick-обновлять `Flash.rotation` для spinning shockwaves / vortex effects.

## Бэклог по движку (после E10)

E1 закрыл базовую прозрачность и процедурные меши, E2.1 — soft-fade на plasma-вспышках, E2.2 — annular-membrane для купола, E3 — material plumbing + procedural FBM/hex паттерны, E4 — plasma flash polish (огонь вместо квадратов + фикс soft-fade no-op), E5.1 — per-billboard plasma tint, E5.2 — billboard matrix fix + non-uniform scale, E6 — time push-constant, E7 — additive mesh pipeline (3D ONE/ONE для произвольных мешей), E7.1 — 3D fireball на AoE-взрывах, E8 — UV + textures (vertex UV, sampler descriptor set, load_texture/load_texture_raw/load_mesh_raw_uv/draw_textured_mesh API), E9 — native particle system (2 pipelines + drawParticles instanced API + 3 consumers), E10.1 — offscreen render + post-process passthrough, E10.2 — velocity attachment infrastructure (R16G16_SFLOAT 2-й color attachment + 9 scene-пайплайнов + post descriptor 2 bindings + placeholder zero writes), E10.3 — prev-frame matrices + per-object velocity (UBO 4 mat4, dynamic-offset UBO set 2 для prev_model, real screen-space velocity для mesh/additive/translucent/textured draws + Kotlin tracking для asteroids/bullets/fireballs), E10.4 — motion blur shader (5×5 dilation + weighted 8-tap blur + length-clamp + overlay clobber fix; bullet trail VFX переделан в hit flash; пули читаются как непрерывные streak'и), E11 — rotated plasma billboards (drawPlasmaBillboard rotation + 3-cone trefoil muzzle blast at 120° + side turrets stали cannon-style). Активная запланированная волна: **E10.5-E10.6 (Particle prev-pos, verify)** — детали в milestone-таблице выше. Что НЕ запланировано (подумать когда понадобится):

- **Procedural-shader варианты для нéбул.** Текущий нéбул = FBM domain-warped soft-disk (E3.2). Можно ещё богаче: multi-color gradients, nebula-specific noise types. Дешевле всего через E8 (текстуры с запечённым шумом).
- **Скайбокс / starfield с шириной.** Сейчас звёзды — point-list, без вариаций яркости/цвета. После E8 можно сделать звёзды через текстурированные quads с per-vertex-twinkle.
- **Decals на повреждённой базе.** После E8 — projector-like меш, накладывает текстуру повреждений на базу при низком HP.
- **Lightning / electric arc procedural mesh.** После E7 (Additive Mesh) — генерим зигзаг-меш в Kotlin и пускаем через additive pipeline.
- **TAA или другие screen-space effects.** После E10 (мотion blur) уже будет render-to-texture infra; добавить TAA не сильно дороже.

Закрытые в E1: ✅ полупрозрачный mesh-pipeline, ✅ per-vertex alpha (RGBA), ✅ процедурные меши через `load_mesh_raw`.
Закрытые в E2.1: ✅ радиальный soft-fade на plasma-биллбордах (vLocalXZ + `pc.tint.x` как флаг).
Закрытые в E2.2: ✅ annular half-membrane mesh для купола щита (force-field-силуэт через translucent pipeline + per-vertex alpha).
Закрытые в E3.1: ✅ material plumbing (translucent draws + `pc.tint.y/z` флаги для шейдера).
Закрытые в E3.2: ✅ FBM нéбулы с domain warping (wispy clouds вместо soft-disks).
Закрытые в E3.3: ✅ hex-grid pattern на куполе щита (force-field structural hint).
Закрытые в E4: ✅ plasma flash polish — premultiply alpha (фикс soft-fade no-op на ONE/ONE blend) + heat-ramp + FBM-турбулентность; вспышки превратились из жёлтых квадратов в wispy огненные кляксы.
Закрытые в E5.1: ✅ per-billboard plasma tint — расширили C API/JNI/Kotlin+push-constant `vec4 plasmaColor`; 6 типов событий теперь имеют свои цвета (muzzle тёпло-белый, trail тёплый дим, AoE оранжево-красный, ENERGY циан, обычная смерть жёлтый, shield-absorb синий).
Закрытые в E5.2: ✅ billboard matrix bug fix + non-uniform scale — `Camera::billboardMatrix` swapped col 1↔2 чтобы model.z (а не model.y) маппился на screen-vertical; X-Z квады теперь screen-aligned, вспышки стали true circles вместо horizontal stripes; добавлен `scaleH, scaleV` через C API → JNI → Kotlin для streak-эффектов.
Закрытые в E5.2-followup: ✅ plasma depth test off — после E5.2 круглый взрыв на y=0 заслонялся 3D астероидным мешем (model.y∈[-1,+1] → часть астероида ближе к камере чем плоскость flash). Изменили plasma pipeline `depthTestEnable VK_TRUE → VK_FALSE` (`compareOp LESS_OR_EQUAL → ALWAYS`); вся plasma-VFX теперь честно overlay-режим как и должно быть для аддитивных эффектов.
Закрытые в E6: ✅ time push-constant — `float time` (offset 96) в `PushConstantData`, `std::chrono::steady_clock` baseline в `VulkanContext`, elapsedSec пишется в каждый push в `renderFrame`; шейдер использует `pc.time` для warp-а FBM в plasma branch (огонь живой), и для медленного дрейфа нéбул (космический газ ползёт). Инфраструктура time push-const разблокирует будущие animated procedural effects (импульсация щита, бегущие электроразряды и т.п.).
Закрытые в E7: ✅ 7-й Vulkan pipeline `m_additivePipeline` для 3D ONE/ONE additive mesh draws (depth-test on read-only); `station_engine_draw_additive_mesh` через C/JNI/Kotlin; `SceneObject.tintR/G/B/A` + `additiveMaterial`; `EngineView.additiveObjects`. Plain-additive шейдер ветка под `pc.tint.w >= 0.5` — разблокирует 3D огненные шары, плазменные лучи, электроразряды.
Закрытые в E7.1: ✅ Material flag в `drawAdditiveMesh` (закодирован в `cmd.tint[3]`: 1.0=plain, 2.0=fire); fire-material шейдер ветка с `abs(vNormal.y)` Fresnel + heat-ramp + animated FBM; процедурная UV-sphere через `loadMeshRaw`; `Fireball` data class + curve-driven анимация в `buildScene` (ease-out quad scale, color lerp orange→red, sqrt brightness fade).
Закрытые в E8: ✅ Vertex UV attribute (`Vertex::uv`, location 3, format VK_FORMAT_R32G32_SFLOAT); `Texture` C++ класс (VkImage + sampler + per-texture descriptor set); descriptor set 1 layout (combined image sampler) + shared pipeline layout; default 1×1 white texture loaded at engine init и biнded на старте `renderFrame`; `station_engine_load_texture(png)` + `load_texture_raw(rgba8)` + `load_mesh_raw_uv` + `draw_textured_mesh` API через C/JNI/Kotlin; `pc.textureMode` push-const flag (offset 100, total 104 байт) + textured fragment branch (`texture(uTex, vUV).rgb * pc.plasmaColor.rgb` как albedo вместо `vColor.rgb`); `SceneObject.textureHandle` + `EngineView.texturedObjects` + `submitScene` route. `GltfLoader` парсит `TEXCOORD_0` accessor с fallback `(0, 0)`. Проверено двумя procedural smoke-test patches (rock noise + cyan icon disc), затем patches удалены.
Закрытые в E9: ✅ 2 particle pipelines (`m_particleAdditivePipeline` ONE/ONE depth-off + `m_particleAlphaPipeline` SRC_ALPHA depth read-only); `particle.vert/.frag` шейдеры с per-instance binding 1 (8 floats stride: pos3+size1+rgba4); 2 persistent-mapped instance VkBuffer (4096 particles each); `drawParticles` API через C/JNI/Kotlin (single batched call per pool); render-loop two-pass (additive + alpha) с per-batch mesh+texture binds и `vkCmdDrawIndexed(instanceCount=N)`; Kotlin `Particle` data class + 3 пула (sparkParticles/smokeParticles/debrisParticles) + tick (Euler+drag+gravity) + pack (alpha=`sqrt(1-t)*tintA`); procedural smoke (64×64 Gaussian+noise) + debris (64×64 polygonal silhouette) textures через `loadTextureRaw`; 3 consumers (AoE sparks, asteroid death debris+smoke, muzzle micro-sparks); HEAVY смерть получает тёмно-красный debris тинт.
Закрытые в E10.1: ✅ Render flow restructured scene→offscreen→post→swapchain. `RenderResources.offscreenColorImage/Memory/View/Sampler` (B8G8R8A8_UNORM, COLOR_ATTACHMENT+SAMPLED, single shared); `postRenderPass` + `postFramebuffers[]` (per-swapchain-image). Existing scene `renderPass` теперь finalLayout=SHADER_READ_ONLY_OPTIMAL; `framebuffers` содержит один shared scene fb. `m_postPipeline` + own minimal layout (1 set, no PCs) + dedicated descriptor set bound to offscreen sampler. `post.vert` (fullscreen-triangle через gl_VertexIndex без vertex bindings) + `post.frag` (passthrough sample, motion blur — E10.4). `renderFrame` two-pass: scene draws everything as before, then post pass draws 3 verts. Visually идентично pre-E10.
Закрытые в E10.2: ✅ Velocity attachment infrastructure. `RenderResources.offscreenVelocityImage/Memory/View/Sampler` (R16G16_SFLOAT, COLOR_ATTACHMENT+SAMPLED) — параллельно offscreen colour. `createRenderPass(velocityFormat=...)` добавляет 2-й color attachment в scene pass; `createSceneFramebuffer(offscreenVelocityView)` бинды 3 attachments. Все 9 scene-пайплайнов получили 2-й `VkPipelineColorBlendAttachmentState` (no-blend, write-mask R+G) — replaced single `cbAtt` with `cbAtts[2]` array + reference alias `auto& cbAtt = cbAtts[0]` чтобы сохранить per-pipeline blend-state мутации. Post pipeline (`m_postPipeline`) использует свои `postCb`/`postCbCI` с 1 attachment, не тронут. `renderFrame::clearValues[3]` (color, velocity (0,0), depth). `m_postSetLayout` 2 bindings (sceneColor, sceneVelocity) — готов к E10.4 без descriptor-изменений. `triangle.frag` + `particle.frag` объявили `layout(location=1) out vec2 outVelocity`, пишут placeholder `vec2(0.0)` в начале main() для всех early-return ветвей. Реальный compute velocity ждёт E10.3 (prev_view/prev_proj в UBO + prev_model push-const). Visually идентично pre-E10.2.
Закрытые в E10.3: ✅ Per-frame prev camera matrices + per-object prev_model. `UniformBufferObject` grew to 4 mat4 (`view, proj, prev_view, prev_proj`); `m_prevView`/`m_prevProj` cached in `updateUniformBuffer` for next frame. **Per-draw dynamic UBO** (set 2) — new `m_perDrawUboBuffer` HOST_VISIBLE+COHERENT persistent-mapped, 4096 slots × `align(64, minUboOffsetAlignment)`. Slot 0 sentinel identity, cursor starts at slot 1 in `beginScene`, mesh-style draws allocate own slots. `m_perDrawSetLayout` = single binding `UBO_DYNAMIC` vertex stage. Pipeline layout 3 sets `[UBO, texture, perDraw]`; render loop binds set 2 with `dynamicOffset` per mesh draw. **Draw API extended**: `drawMesh / drawPickableMesh / drawTranslucentMesh / drawAdditiveMesh / drawTexturedMesh` got optional `prevModelMatrix` (nullable in Kotlin → nullptr in C → engine fallback prev=current=zero velocity). Plasma billboards / particles / frame draws use sentinel slot 0. `triangle.vert` declares `set=2 binding=0 PerDraw{ mat4 prev_model }`, computes `vVelocity = (currNdc - prevNdc) * 0.5`. `triangle.frag` defaults `outVelocity = vVelocity` in main(); frame and plasma branches force `vec2(0.0)`. `particle.vert` UBO struct mirrored for layout match (no prev_* read yet). Kotlin tracking: `Asteroid.prevZ/prevRotation`, `Bullet.prevX/prevZ`, `Fireball.prevLife` snapshotted before move; `SceneObject.prevModelMatrix: FloatArray? = null`; `submitScene` threads it through; `buildScene` for asteroids/bullets/fireballs constructs prev SceneObject and uses its `modelMatrix()`. Visually identical pre-E10.3 — motion blur shader (E10.4) реально его прочтёт.
Закрытые в E10.4: ✅ Motion blur post-process. `post.frag` = 5×5 velocity dilation (max-magnitude в окрестности ±5px) + weighted 8-tap blur (moving samples weight 1.0, static 0.2) + length-clamp (`kMaxBlur=0.05`, `kIntensity=1.5`). Static fast path для пикселей с velocity < `kEpsilon` (1e-4) обходит 16 sample fetches. **Overlay velocity-clobber fix**: `cbAtts[1].colorWriteMask=0` сразу после opaque pipeline — все 8 non-opaque pipelines (system / star / plasma / translucent / additive / frame / particle additive / particle alpha) наследуют 0 → их `outVelocity = vec2(0)` write никуда не идёт, opaque velocity сохраняется при overdraw. **Bullet trail VFX переделан**: `trailTimer` field, trail spawn block в tick, `TRAIL_*` constants и `FLASH_TINT_TRAIL` удалены; `HIT_FLASH_LIFE`/`HIT_FLASH_SIZE_MUL`/`FLASH_TINT_HIT` добавлены; non-AoE direct hits спавнят hit flash (sized by `b.halfW * 3`); AoE остаётся с fireball + sparks через `spawnExplosion`. Пули теперь читаются как continuous streak'и под motion blur вместо мерцающих точек.
Закрытые в E12: ✅ Railgun muzzle effect. Shader-параметрические lightning bolts через plasma pipeline: внутри `pc.tint.x>=0.5` ветки добавлен sub-branch `pc.tint.y>=0.5` который рисует электрическую дугу на quad'е — 2-octave FBM perpendicular displacement of centerline (animated via `pc.time` + per-bolt `seed=pc.tint.z`), narrow Gaussian core × cyan halo, brightness modulation вдоль длины, smoothstep end-fade. `drawPlasmaBillboard(..., lightningSeed: Float)` extended C/JNI/Kotlin (BillboardDraw + Flash field). `spawnRailgunMuzzle` для центрального HEAVY_CANNON: bright cyan-white core flash + 5-7 lightning bolts (rotation = perp-to-barrel ± random spread; per-bolt seed; varied length+life), плюс cyan-tinted muzzle sparks через E9. WeaponCatalog displayName «Тяжёлая пушка» → «Рельсотрон». **nebulaAlphaMod / hexAlphaMod gated** `pc.tint.x<0.5` — slot reuse `tint.y/z` (translucent material flags ↔ lightning sub-mode + seed) безопасен потому что translucent и plasma никогда не разделяют draw call; гейт делает семантику явной.
Закрытые в E11: ✅ Rotated plasma billboards + cone muzzle blast. `drawPlasmaBillboard(..., rotation: float)` через C/JNI/Kotlin. Render-loop plasma section композирует `multiply(billboard, Mat4::rotationY(rotation))`; default 0 = legacy axis-align. `buildMuzzleConeMesh` — 12-segment triangle fan ±15° aperture (30° wedge total) вокруг local +Z, alpha 1, plasma soft-fade делает alpha gradient. `spawnMuzzleBlast` спавнит 3 cone'а с `rotation = forwardAngle ± 0/120°`, размер scaled by `bullet.halfW`. `MUZZLE_FLASH_HALF` 0.13 → 0.39 (3×). `Flash.meshHandle/rotation` + `BillboardDraw.rotation` фициальные fields с дефолтами. Side turrets теперь cannon-style: `FIRE_INTERVAL_SEC` 0.15→1.0s, `SIDE_BULLET_SPEED=18`, `SIDE_BULLET_HALF_W=0.065`/`H=0.117`, `SIDE_DAMAGE_MUL=3`, `SIDE_AOE_RADIUS=0.5`, `SIDE_AOE_DAMAGE_MUL=0.6`, mesh = `bulletHeavyMeshHandle`.
Закрытые в M7.2: ✅ GltfLoader merge multi-primitive meshes (multi-material .glb теперь грузятся целиком, не кусочком).

### M8 — Геймплейный pivot: auto-aim + способности (завершено 2026-05-08)

Триггер: пользователь сообщил, что постоянно целиться пушкой неудобно — палец закрывает экран, мелкие FAST-цели сложно вести. Гипотеза: переключиться на «игрок управляет базой и способностями, а не прицелом», сохранив ручное переключение приоритета через тап. Финальная форма после короткого design discussion: **гибрид, не полный pivot** — auto-aim ядро + tap-to-priority + энергия + 2 способности.

Сделано:
- ✅ **M8.1 Target-lock** — `aimTargetX/Z`/`isTouching` выпилены, заменены на `centralTargetId: Long?`. Touch handler: `ACTION_DOWN` хит-тестит астероид в радиусе `TAP_PICK_RADIUS=0.6` world units (генерозный для пальца), на попадание ставит priority lock; tap по пустоте — no-op. `Asteroid.id: Long` (новое поле, генерится через `nextAsteroidId++` при спавне) даёт стабильную идентификацию между кадрами. **Sticky lock**: `centralTurretAngle()` сначала проверяет `centralTargetId`, если цель жива — возвращает её; иначе авто-pick по max-current-HP с tiebreak по дистанции до пивота, и сохраняет id выбранной цели в `centralTargetId`. Никакого pересчёта цели на каждом кадре — два HEAVY с близким HP больше не дают jitter. `aimAligned` гейт сохранён (защита от off-target shots при свинге).
- ✅ **M8.2 HP-bars** — Kotlin-side scene assembly, no engine work. `Asteroid.maxHp: Int` snapshotted at spawn (= hp). Новые мешхэндлы `quadHpBgHandle` (тёмно-серый 0.18/0.20/0.22) и `quadHpFgHandle` (зелёный 0.30/0.85/0.35), грузятся через `loadMeshColored`. `buildHpBars(): List<SceneObject>` для каждого астероида с `0 < hp < maxHp` спавнит 2 квада: bg (ширина 1.6×asteroid.half) + fg (scaleX = barHalfW × hp/maxHp, anchored к левому краю — shrinks rightward как reload bar). Fg сдвинут на `y=-0.01` для прохождения LESS depth-test. Append к `engineView.scene` через `+ buildHpBars()`.
- ✅ **M8.3 Energy resource** — `energy: Float ∈ [0, 100]` + `ENERGY_REGEN_PER_SEC=10`. Tick регенерит, UI text refresh троттлится по integer-floor (≈10 апдейтов/сек tops). `hudEnergyText` под `hudHpText` в правой колонке HUD, формат `⚡ N/100`, цвет `COL_ACCENT_BLUE` чтобы не путаться с белым HP. `startMission` сбрасывает `energy = ENERGY_MAX`.
- ✅ **M8.4 Ability framework** — `game/Ability.kt`: `AbilityId` enum, `Ability` data class (id/displayName/shortLabel/description/cost/cooldownSec/needsTarget), `AbilityCatalog` с двумя элементами. В MainActivity `AbilitySlot(ability, currentCd, cdUiLast)` runtime, `abilitySlots: List<AbilitySlot>` (immutable). UI: горизонтальный `abilityBar: LinearLayout` заменяет старого top-level shieldButton FrameLayout-child; держит [Щит] [Ракеты] [Лазер]. 4 состояния кнопки способности: READY (blue accent), COOLING (`${sec}с`, dim), INSUFFICIENT-ENERGY (label dim, disabled), ARMED (зелёный + `!`). `armedAbilityId: AbilityId?` — для targeted способностей, while non-null engine-surface touch handler routes to `handleArmedAbilityTouch` instead of priority-lock tap.
- ✅ **M8.5 Ракетный залп** — cost 30, cd 8c. `findRocketTargets(maxN)` возвращает top-N астероидов по убыванию current HP (tiebreak nearest). `launchRocketStrike(targets)` спавнит per-target homing missile с `bullet_heavy` mesh, начальный velocity вектор → к цели, AoE 0.4 / 60% сплеш, damage = `effectiveMainWeaponDamage × 4 × buff`. `Bullet` расширен mutable `vx/vz` + `homingTargetId/homingTurnRate`. Move-loop: перед движением, если `homingTargetId != null` и target жив — вызывается `homeBulletTowardsTarget(b, target, dt)` (atan2-based angular correction, clamped по `turnRate*dt=4 rad/sec`, скорость сохраняется). Per-rocket muzzle blast (warm cone trefoil из E11). `activateAbility` стал Boolean: спавн эффекта первый, energy/cd только если эффект сработал (рефанд если `findRocketTargets` пуст).
- ✅ **M8.6 Лазерный удар** — cost 50, cd 18c, `needsTarget=true`. Тап по `ЛАЗЕР` → arm. ACTION_DOWN на engine surface → `laserStartX/Z = worldXZ`, `laserGestureActive=true`. ACTION_UP → marshal на mission thread → `fireLaserStrike(slot, ax, az, bx, bz)`. Геometry — для каждого live астероида: проекция центра на сегмент (clamped to [0..len]), точка ближайшая, проверка `d² ≤ (a.half + LASER_HIT_PAD)²` (pad=0.10). Damage 80 (one-shot для NORMAL/FAST, half-HP для HEAVY). Слишком короткий drag (<0.05) — отмена без spend. VFX: 5 параллельных lightning bolts через E12 sub-shader, halfMax = thin (0.045), halfMaxV = `len/2` (lightning рисуется вдоль локальной +Z mesh оси, scaleV = halfMaxV — после rotationY длинная ось ложится вдоль сегмента). Tint `LASER_TINT` cyan-white.
- ✅ **Threading fix** — `bullets`/`asteroids` мутируются tick-loop'ом на `DraftTickThread`. UI-thread добавление пуль из ability activation racing iterator → CME. Все activations marshalled через `missionHandler?.post { ... }` так что spawn идёт между двумя тиками atomically.
- ✅ **`Flash.halfMaxV: Float = halfMax`** (M8.6 addition) — non-uniform plasma billboards. По умолчанию uniform — все existing flashes (round, cone, railgun bolts) не тронуты. `buildScene` форвардит и `scaleH=halfMax` и `scaleV=halfMaxV` в BillboardDraw.
- ✅ **Mission counts ×1.5–2** — M1 7+7, M2 10/12/14, M3 12/14/16, M4 14/16/18, M5 18/20/22/24. Spawn intervals тактнее (M5 финал 1.0с между астероидами).

Принятые решения:
- **Sticky lock vs per-frame re-pick.** Принципиально per-frame было для adaptability, но user explicit feedback после M8.1 («лупит её пока не сломает, не переключается»): sticky выиграл. Edge case — спавнится более жирный HEAVY mid-fight: турель добивает текущую цель, потом auto-picks нового. Если игроку не нравится — тапнул и переключил.
- **HP-bar — Kotlin scene assembly, не engine.** Engine-визуальные правки требуют E-волны; для UI-примитивов (квадрат с тинтом) уже всё есть. Принципиальная позиция: engine только когда нужно реально новое поведение (новый pipeline / шейдер branch / API). Если потом захотим animated damage flash или smooth shrink — это будет E-волна.
- **Energy regen rate 10/sec.** Ракеты 30 = 3 сек, лазер 50 = 5 сек. Cooldowns (8/18) длиннее → energy не bottleneck когда кольдауны активны, но между активациями игроку приходится решать «копить или тратить».
- **Sticky на одного слота для priority** vs separate `priorityTargetId` + `autoLockId`. Двух-полевой вариант имел странное поведение — после смерти priority возвращаемся к старому autoLock (мог уже быть полу-мёртвым). Single-var с пересчётом при смерти — чище.
- **Boolean `activateAbility` + рефанд** — если ракета не находит targets (пустой экран между волнами), не тратим energy/cd. Альтернатива «спендим всегда + UX feedback на пустой клик» отложена — пока silent fail acceptable.
- **Marshal на mission thread**, не synchronized lock. CME pattern наиболее clean решается threading boundary, не fine-grained locks. Цена — ~33ms задержка реакции. Незаметно.
- **Lightning bolt длина = halfMaxV**, не halfMax. Шейдер E12 рисует дугу вдоль mesh-local +Z (= scaleV в BillboardDraw convention). Лазер — длинная ось вдоль сегмента → halfMaxV = `len/2`. На M8.6 это всплыло как невидимый луч до фикса (изначально были halfMax = len/2 + halfMaxV = thin → bolt получался тонкой полоской поперёк направления гесcture).

Что M8 разблокировал на будущее:
- 🟡 **Ability persistence через мету** — пока обе abilities всем доступны, balance numbers захардкожены. Когда захотим прогрессию, мета ladder типа «cooldown -10%», «extra rocket per salvo», «laser pierces through more», «freeze ability unlocks at lvl 3».
- 🟡 **Energy upgrades за металл** (max + regen rate) — обсуждалось, отложено до отдельной волны.
- 🟡 **Targeted abilities UI feedback** — laser сейчас arm + drag без preview. Live aim line (тонкая полупрозрачная вспышка между ACTION_DOWN и текущей позицией) добавила бы читаемости. Простое расширение, не engine.
- 🟡 **Ability bar visual polish** — кнопки одинаковые по UX-style; per-ability иконка / цветовой акцент сделал бы их более distinguishable. Это не блокирует играть.

### M9 — Шит rework: HP-based barrier + hold-to-recharge (завершено 2026-05-08)

Триггер: после M8 шит остался last vestige старого on/off design — 3 сек активный + 15 сек cooldown. Plus, шит-куpol (E2.2 + E3.3 hex pattern) был визуально привязан к ACTIVE-фрейму. Pользователь нарисовал на скрине новую форму — широкую плоскую дугу через всю платформу, и попросил permanent шит с HP=500, hold-to-recharge from energy.

Сделано:
- ✅ **State machine выпилена.** `enum ShieldState { READY, ACTIVE, COOLING }`, `shieldTimer`, `shieldCooldown`, `SHIELD_DURATION_SEC`, `SHIELD_COOLDOWN_SEC`, `shieldUiSecLast`, `onShieldTapped()` — всё удалено. Заменено на: `shieldHp: Float` (стартует с `SHIELD_MAX_HP=500f`), `shieldRecharging: Boolean`, `shieldUiPctLast` (UI throttle по integer-percent).
- ✅ **Damage routing.** Asteroid → platform collision проходит через щит:
  - `shieldHp ≥ dmg` → full absorb, shield drops by full damage, base untouched, shield-hit flash.
  - `0 < shieldHp < dmg` → partial absorb, shield breaks, `overflow = dmg - shieldHp` идёт в платформу, shield-hit flash.
  - `shieldHp == 0` → full pass-through на платформу.
- ✅ **Hold-to-recharge.** Кнопка ЩИТ — `setOnTouchListener` вместо `setOnClickListener`. ACTION_DOWN → `shieldRecharging=true`. ACTION_UP/CANCEL → false. Tick: пока `shieldRecharging && energy > 0 && shieldHp < SHIELD_MAX_HP`, тратит `SHIELD_RECHARGE_ENERGY_PER_SEC=50`, добавляет `SHIELD_RECHARGE_HP_PER_SEC=200` (4× ratio — full energy bar = +400 shield HP). Energy clamp на 0.
- ✅ **`refreshShieldButton`.** 3 visual cases:
  - Recharging (нажата + energy > 0 + не полный) → зелёный фон.
  - Full HP → серый, dim.
  - Damaged, не нажата → синий.
  Текст всегда `ЩИТ N` (целое значение HP).
- ✅ **Гео — `buildShieldArchMesh()`.** 64 sectors, halfW=2.40, halfH=1.00, thickness=0.06. Вершины **в мировых координатах** (pre-scaled), SceneObject просто транслирует в `(0, -0.05, PLATFORM_TOP_Z)` со scale=1. Три концентрических кольца offset вдоль outward-нормали эллипса (gradient `(cx/halfW, cz/halfH)` нормализован) на ±thickness/2 — даёт **constant-thickness band** в мировом пространстве. (Альтернатива «unit half-circle + non-uniform SceneObject scale» дала бы анisotropy: thicker на apex, thinner на feet.) Цвет cyan-blue (0.45, 0.75, 1.00), peak alpha 0.85. Translucent pipeline, no hex/nebula material — clean glow line.
- ✅ **`buildShieldDome()`** упрощена: возвращает emptyList если `shieldHp <= 0`, иначе single SceneObject с новым меш-handle (`domeMembraneHandle` теперь = arch handle).

Принятые решения:
- **Hold-to-recharge** vs discrete tap-spend. User explicitly выбрал hold. Преимущество: tactile control, можно дозировать (отпустил когда решил). Недостаток — нельзя одновременно прицеливаться рукой держащей шит, но при auto-aim это не проблема.
- **shieldHp как Float** не Int. Recharge 200 HP/sec при 30Hz tick = 6.67/tick — fractional. Float accumulator сохраняет точность; UI показывает `toInt()`. Damage routing использует `toFloat()` для overflow math.
- **4× energy:HP ratio.** Полная полоска энергии (100) = +400 HP щита. Чтобы полностью восстановить с нуля = 1.25× full energy (~12.5 сек реального времени hold). Достаточно «дорого» чтобы быть стратегическим решением, но не frustrating.
- **Permanent render когда `shieldHp > 0`.** User said «висит всегда». Если HP=0 — щит сломан, рендера нет (visible cue). Если хотим ghost-outline-at-0, добавим отдельной задачей.
- **Constant alpha = 0.85**, не модулирована по HP%. Engine path для translucent не пробрасывает per-frame tint — пришлось бы делать engine work. На MVP: HP читается через кнопку (текст + цвет). Если playtest покажет что нужен visual HP indicator на щите — будет E-волна.
- **Vertices в мировых координатах**, не unit-half-circle с SceneObject scale. Anisotropy — реальная проблема для тонкой полосы (thickness разная на разных частях арки). Trading mesh-rebuild-cost on parameter changes (нужно пересоздавать handle при изменении halfW/H/thickness) на чистое визуальное представление. Параметры захардкожены сейчас — если их понадобится крутить, добавим setupBackgroundNebulae-style rebuild.
- **Гекс-pattern удалён.** На widely-stretched арке hex-grid визуально странный (тайлы deformed). User screenshot был чистой линией — без узора. Plain alpha-blended band читается как force-field outline.

Что M9 не закрывает (передаётся дальше):
- 🟡 **HP-modulated alpha/color на щите** — engine work. Нужен либо tint-forward в translucent path, либо runtime-rebuild mesh при смене HP threshold (ugly).
- 🟡 **Shield impact ripple/pulse** — на момент попадания было бы здорово видеть ripple. Нужен impact event-канал (когда астероид бьёт щит → передать coordinate в шейдер). E6 time push-constant + ещё один push-const slot → ripple в шейдере.
- 🟡 **Энергию upgrade за металл** — связана с M8 ability balance. Отложена.

### M10 — UI / щит-полировка (завершено 2026-05-09)

Послойный подгон action-бара и купола под концепт «играю-управляю-базой».

Сделано:
- ✅ **Иконки на ability-кнопках** — replaced labels (ЩИТ/РАКЕТЫ/ЛАЗЕР) на инфографику. Новый `IconDrawable` (Path-based, runtime-tintable). Три фабрики: `makeShieldIcon` (V-shaped heater shield: плоский верх, выпуклые плечи, острый V-кончик + chief line), `makeRocketIcon` (вертикальная пуля + два плавника + выхлоп), `makeLaserIcon` (диагональный луч режет нерегулярный астероид-полигон). Кеширование per-button — retint на state-change через `setIconTint`, без аллокаций.
- ✅ **Щит-кнопка как HP-полоса** — убрали число, фон вертикально делится: снизу зелёный (остаток HP), сверху серый (потрачено). Граница опускается по мере падения HP, поднимается при подзарядке. `ShieldFillDrawable` — кастомный Drawable с rounded-rect клипом и двумя rect-fill.
- ✅ **Action-bar 32dp** — высота снижена с 52dp до 32dp. Способности двухрежимные: icon-mode (`textSize=0`) когда READY/INSUFFICIENT/ARMED, text-mode (`SP_CAPTION` + `setCompoundDrawables(null)`) только при COOLING. ARMED-state отказались от caption "!" в пользу зелёного фона + светлой иконки.
- ✅ **Шит-арка superellipse n=4** — `|x/a|^n + |z/b|^n = 1` вместо обычного полу-эллипса. Плоский верх и резкие плечи, читается как force-field band. `SHIELD_ARCH_SHARPNESS = 4f`. Параметризован градиент-нормаль для constant-thickness ring. Подъём `SHIELD_ARCH_LIFT_FRAC = 0.05` — концы зависают над платформой.
- ✅ **Иконка приложения** — заменена с зелёной g3-шной на «тёмно-синий космос + астероид + cyan луч». Скопированы PNG из `art/icon/android/mipmap-*` в `res/mipmap-*` (5 плотностей × 4 файла = 20 PNG), удалены старые `.webp`. `mipmap-anydpi/ic_launcher.xml` обновлён на `@mipmap/ic_launcher_foreground/background` (новые density-PNG) вместо `@drawable/ic_launcher_*` (старые vector).
- ✅ **Астероиды разбиваются о щит-арку** — раньше астероид долетал до платформы (визуально проходя сквозь дугу), и щит абсорбировал урон. Теперь collision-check проверяет дугу первой: для X внутри `±SHIELD_ARCH_HALF_W` считается `archZ(x) = baseZ + halfH × (1 − |x/halfW|^n)^(1/n)`. Если астероидBottom опускается до `archZ` — астероид удаляется, флэш в точке контакта на дуге, урон роутится в щит как раньше (full absorb / partial+overflow). На флангах за пределами `halfW` — старая логика прохода до платформы.
- ✅ **Recharge-режим даёт −20% урона по щиту** + **cyan-искры бегут по дуге**. `SHIELD_RECHARGE_DAMAGE_MUL = 0.80f` — пока кнопка нажата, входящий dmgF умножается. `emitShieldRechargeSparks(dt)` — fractional accumulator на `RATE × dt`, каждая искра спавнится в случайной точке superellipse (параметрический u ∈ [-1,+1]) с **тангенциальной** скоростью (gradient-derived, rotated 90°), randomized direction (вправо/влево по дуге), drag=4 (быстро тормозят), life 0.10-0.22s, cyan tint `(0.55, 0.85, 1.00)`. ~14 одновременных искр при RATE=90/сек.

### M11 — Процедурные платформенные меши (завершено 2026-05-09)

Заменили flat-quad визуалы турелей и абилки-источников на процедурные сборки через `TurretMeshBuilder`.

Сделано:
- ✅ **`TurretMeshBuilder`** — общий builder с `addRect`, `addChamferedRect` (8-vertex octagonal silhouette + center, triangle fan), `addTri` (для плавников/нос-конусов ракет), `addHalfDisk` (для лазерного купола). Вершины с `pos3 + rgba4 + normal3 = (0,1,0)`. Опциональный `y` per-call для слойных деталей (LESS depth-test trick — `-0.005f` для overlay-слоёв).
- ✅ **Расщепление турелей** — каждая турель стала **базой (статичная) + башней (вращается вокруг своего origin)**. `buildTurretBaseMesh`: chamfered slab + 2 vent slits + accent stripe. `buildTurretBarrelMesh`: pivot collar + chamfered housing с акцентным цветом + 2 dark slits + mantlet + barrel + cooling fin + muzzle ring + dark bore. Параметры: housing/barrel/muzzle half-W и length, accent RGB (red для центральной, blue для боковых). `centralBaseMesh + centralBarrelMesh + sideBaseMesh + sideBarrelMesh` хранятся как 4 хэндла. Pivot Z двигается на `BASE_HEIGHT` вверх (CENTRAL_TURRET_BASE_Z с -0.94 на -0.90), формула muzzleZ = `BASE_Z + 2*HALF_H` сохранена потому что общая длина башни = `housingLen + barrelLen + muzzleLen = 2*HALF_H` by construction. Боковые турели тоже стали поворотными — `sideTurretAngles[2]` ранится экспоненциальным lerp каждый тик к `nearestAsteroidInArc(...)`. Боковые muzzleZ переехал с фиксированной точки на `(tx + nx*SIDE_TOTAL_LEN, tz + nz*SIDE_TOTAL_LEN)` — дуло в реальном кончике повёрнутого ствола.
- ✅ **Лазерная установка** — наземно-телескопический dome: фасочная плита-фундамент + half-disk купол (24-сегментная триангуляция фаном) + cyan-полоса на стыке. Раньше включала торчащий лазерный ствол + linear opening, после фидбэка пользователя «увеличь на 60% и убери телескоп» — остались только база и купол, размеры × 1.6.
- ✅ **Ракетная шахта** — open-hatch установка зеркально с лазером. Слои: chamfered foundation + mid-tower + slightly-wider top rim (создающий «горловину») + 2 vertical warning stripes (orange) на боку tower'а + dark deep-cut launch opening. Позиция X = -0.9 (зеркало лазерного купола на X=+0.9).
- ✅ **`addHalfDisk`** добавлен в TurretMeshBuilder — расширяемый builder поддерживает теперь все нужные примитивы для процедурных VFX-объектов будущего.

### M12 — Непрерывный лазер + чистка legacy (завершено 2026-05-09)

Старый «drag-line strike» (M8.6) был заглушкой под идею способности; пользователь захотел нормальный непрерывный лучевой режим.

Сделано:
- ✅ **Лазер: 5 сек continuous beam, 50 DPS** — `LASER_BEAM_DURATION_SEC = 5f`, `LASER_BEAM_DPS = 50f`. `Beam` weapon-effect (см. M13) ray-cast-ит каждый тик от dome к мастер-таргету, ищет первый астероид на луче (no piercing — толстый астероид в пути блокирует луч), наносит fractional DPS с `dmgAccum: Float` для int-HP астероидов. Цель умерла → auto-pick подхватывает следующего, луч следует.
- ✅ **`Ability.LASER_STRIKE.needsTarget = false`** — кнопка теперь instant-fire, без drag. Описание обновлено.
- ✅ **Lazer pipeline через E14** — впервые задействован новый `drawLaserBeam` engine API (см. ниже E14). Раньше луч использовал плазма-биллборды с lightning-shader hack-ом; теперь — собственный pipeline.
- ✅ **Выпилен legacy code:** `fireLaserStrike()` функция, `handleArmedAbilityTouch()`, `armedAbilityId` поле + все ссылки, `laserGestureActive/laserStartX/Z`, `Ability.needsTarget` свойство (фреймворк armed-target больше нигде не используется), legacy `LASER_DAMAGE/LASER_HIT_PAD/LASER_BOLT_*` константы, ARMED-ветка в `refreshAbilityButton`, `if (armedAbilityId != null)` ветка в touch-listener, `if (a.needsTarget)` ветка в `onAbilityTapped`.

### E13 — Plasma billboard rotation + non-uniform scale fix (завершено 2026-05-09)

Триггер: пользователь сказал «лазер бьёт только вертикально, не следует за rotation». Расследование показало старый известный баг (комментарий в самом VulkanContext.cpp признавал его): rotation композирована как `billboard_with_scale × Ry(rot)` где scale baked в camera-aligned axes. С uniform scale OK, с non-uniform (тонкая в perp, длинная вдоль) — длинная и тонкая оси меняются местами в screen при поворотах ≠ 0/π.

Сделано:
- ✅ **`Mat4::scale(Vec3)`** factory добавлена в `cpp/engine/math/Mat4.h`.
- ✅ **Композиция в `VulkanContext::renderFrame` plasma-секции** переписана:
  ```
  було: billboard(scaleH, scaleV) × Ry(rot)
  стало: billboard(1,1) × Ry(rot) × Scale(scaleH, 1, scaleV)
  ```
  Вертекс сначала растягивается non-uniform в model space, потом крутится в model X-Z, потом billboard камера-выравнивает с uniform scale=1. Стрик-форма правильно следует за rotation.
- ✅ **Backward-compat математически проверен:**
  - Uniform scale, rot=0 (round flashes) → `billboard(1,1)*S(s,1,s) = billboard(s,s)` идентично.
  - Uniform scale, rot≠0 (E11 muzzle cones) → идентично старому.
  - Non-uniform + rotation (наш лазер, E12 railgun bolts, legacy drag-laser) — раньше шерсило, теперь честно крутится.

### E14 — Dedicated beam API (завершено 2026-05-09)

Триггер: после E13 пользователь решил, что плазма-биллборды + lightning-sub-shader — не идеальный путь для непрерывного лазера, и попросил "отдельный API под лучевые эффекты", потому что движок будет переиспользоваться (g3 strategy game с многими кораблями-лазерами одновременно).

Сделано:
- ✅ **Новые шейдеры** `app/src/main/shaders/beam.vert` + `beam.frag`. Vert разворачивает 6-вершинный quad из `gl_VertexIndex` (без vertex buffer), считает view-aligned perpendicular как `cross(beamDir, viewForward)` где `viewForward = -ubo.view[*][2]` — работает для произвольной камеры (g3-критично). Frag красит Gaussian core perpendicular (`exp(-v²·24)`) + soft halo + smoothstep end-fade на последних 2% длины + лёгкий пульс по `pc.time`. Premultiplied alpha для ONE/ONE additive.
- ✅ **`m_beamPipeline`** — новый Vulkan pipeline в VulkanContext. Свой минимальный layout (set 0 = scene UBO, push constants для beam params, 64 bytes std140 — start/end vec3 + color vec4 + width + time). Empty vertex input, TRIANGLE_LIST topology, additive ONE/ONE blend, depth-test on read-only, velocity attachment writeMask=0.
- ✅ **Public API** через всю вертикаль:
  - C: `station_engine_draw_laser_beam(start, end, width, rgba)` в `engine_api.h/cpp`
  - JNI: `nativeDrawLaserBeam` в `cpp/android/EngineJni.cpp`
  - Kotlin: `EngineJni.drawLaserBeam(startXYZ, endXYZ, width, rgba)`
  - Scene: `BeamDraw` data class + `submitScene(beams: List<BeamDraw>)` маршрутизирует
  - EngineView: `@Volatile var beams: List<BeamDraw>`
  - StationEngine struct: `beamVertSpv/beamFragSpv`, `set_shader("beam.vert"/"beam.frag")`
- ✅ **Render-pass integration** — между additive mesh (3D fireballs) и plasma billboards в scene render pass. Один вызов `vkCmdDraw(6, 1, 0, 0)` per beam с push constants.

### M13 — WeaponEffect umbrella architecture (завершено 2026-05-09)

Триггер: пользователь спросил «как реализована логика снаряда» — ответ был «жирная Bullet data class + ветки в тике». Согласовали поэтапный refactor; stage 1 — единая umbrella для projectile + beam, stage 2 (slot/installation система) отложен на «дизайн базы».

Сделано:
- ✅ **`WeaponEffect` interface** — единый абстракт с `tick(dt: Float): Boolean (consumed)`. Заменяет старые `bullets: MutableList<Bullet>` + плоские `laserBeam*` поля единым `effects: MutableList<WeaponEffect>`.
- ✅ **`Projectile` class** (бывший `Bullet`, переименован) — implements WeaponEffect, инкапсулирует прежнюю tick-логику (snapshot prev → behavior.tick → move → off-screen cull → AABB collision → behavior.onImpact). Несёт `behaviour: ProjectileBehavior` strategy для steering/impact variation. Дополнительные поля `modelScale: Float` и `modelYawOffset: Float` (defaults under legacy .glb-bullet'ам, процедурная ракета передаёт `1f / 0f` под её +Z-forward, world-unit меш).
- ✅ **`Beam` class** — implements WeaponEffect для непрерывных лучей. Source как **`() -> Vec3` closure** (для g3-портабельности с движущимися кораблями), aim selector как **`() -> Asteroid?` closure** (re-evaluated каждый тик), optional `canEngage: (Asteroid) -> Boolean` для arc-gating (см. M15). Tick: ray-cast от source к aim, find first asteroid intersecting line, fractional DPS damage, endpoint stash для buildScene.
- ✅ **`Vec3`** small data class private inside MainActivity (3D вектор для closure-источников; Y=0 в Outpost, не-ноль в g3).
- ✅ **Tick-loop simplification** — был 70+ строк ветвлений (homing? aoe? hit flash?), стал 3 строки:
  ```kotlin
  effects.iterator().forEach { if (it.tick(dt)) it.remove() }
  ```
- ✅ **buildScene query** — `effects.filterIsInstance<Projectile>()` для SceneObjects, `effects.filterIsInstance<Beam>()` для BeamDraw'ов.
- ✅ **`updateLaserBeam(dt)`** функция выпилена — её логика инкапсулирована в `Beam.tick()`.
- ✅ **`launchRocketStrike` → `RocketSilo.fire()`** (см. M14) — спавны привязаны к weapon-классу.

### M14 — Ракетный overhaul (завершено 2026-05-09)

Включает: type-bound RocketSilo class, процедурный rocket mesh, 3-фазный VFX (boost+trail с ASCENDING/FLYING-разделением + ignition flash + reactive jet), spring-launch sequence с очередью.

Сделано:
- ✅ **`RocketSilo` weapon class** (`private inner class`) — `fire(targets: List<Asteroid>)` хардкодит Projectile + HomingRocketBehavior конструкцию. Через эту точку входа НЕВОЗМОЖНО структурно произвести non-rocket Projectile. Поле `rocketSilo` инициализируется константами `ROCKET_SILO_X / ROCKET_SILO_MUZZLE_OFFSET`.
- ✅ **Процедурный меш ракеты** `buildRocketMesh()` — origin at centre, axis along +Z, total length 0.30 (ROCKET_BODY_LENGTH). Слои: engine bell (фасочный, тёмный) + body rect (грей) + 2 fin triangles (mid-grey, через `addTri`) + nose triangle + warning stripe orange (y-сдвиг `-0.003`). `rocketMeshHandle` строится в `buildTurretMeshes()`. Per-projectile `modelScale = 1f` и `modelYawOffset = 0f` (vs legacy bullet'ы с `halfH × MUL` и `-π/2`).
- ✅ **Spring-launch sequence + очередь** — `RocketSilo.fire()` теперь **не спавнит сразу**, а queues target IDs в `pending: ArrayDeque<Long>`. Каждый тик вызывается `rocketSilo.tick()`: если в `effects` нет ракеты в фазе `RocketPhase.ASCENDING` (tube свободен) — pop первого target ID и spawn. Спавнится **строго вверх** (vx=0, vz=ASCENT_SPEED), центром в `siloZ + LENGTH/2` чтобы база сидела на горловине. Spring-puff (warm cone) с направлением `(0,1)`.
- ✅ **`HomingRocketBehavior` 2-фазная state-machine** — `RocketPhase { ASCENDING, FLYING }` enum (private file-level, потому что Kotlin запрещает enum внутри inner class). ASCENDING: constant straight-up rise at ASCENT_SPEED, **двигатель ВЫКЛ — нет ни smoke trail ни jet** (rocket inert, рулит инерция пружины). При `p.z - launchZ >= ASCENT_HEIGHT` (= 2 × LENGTH = 0.60) → переход в FLYING. FLYING: boost-accel вдоль heading до `cruiseSpeed` + steer toward `targetId` clamped по `turnRate`. Smoke trail и engine jet эмитятся только в FLYING.
- ✅ **Ignition flash** — one-shot bright burst spawned at `ASCENDING → FLYING` transition. `spawnRocketIgnition(p.x, p.z)`: `halfMax = 0.18` (крупнее обычного hit-flash), `life = 0.20s` (быстрый fade), `tint = FLASH_TINT_MUZZLE × alpha 1.4` — ярче muzzle-blast'а.
- ✅ **Reactive jet** — `spawnRocketJet(x, z, vx, vz)` каждые 0.02 сек (50 Hz) во FLYING. Position = центр ракеты сдвинут назад на `LENGTH × 0.45` вдоль обратной velocity. HalfMax 0.055, life 0.07s. Per-instance `jetTimer` jitter — салво не пульсирует в lock-step. Получается «язык пламени» из 3-4 одновременных перекрывающихся вспышек.
- ✅ **Smoke trail** — `spawnRocketTrail(x, z, vx, vz)` каждые 0.025 сек во FLYING. Использует существующий E9 alpha-textured smoke pool. Backward drift, life 0.5-0.9s, cooler grey-blue tint (vs warmer asteroid-death smoke).
- ✅ **`steerProjectileTowards(p, target, turnRate, dt)`** — выделен из старого `homeBulletTowardsTarget` как pure helper, используется HomingRocketBehavior.

### M15 — Per-weapon firing arcs + priority-lock semantics (завершено 2026-05-09)

Pull-request-style полировка: каждое оружие имеет свою боевую дугу (90/80/80/70/95/95% от 180°), priority-lock на астероид охватывает обе центральные пушки И лазер (master target).

Сделано:
- ✅ **Константы дуг в DraftCombat** (radians, half-arc):
  ```
  ARC_CENTRAL_CANNON_HALF_RAD = 1.4137  // 90% × 180° / 2 = ±81° (Рельсотрон)
  ARC_CENTRAL_MG_HALF_RAD     = 1.2566  // 80% (Автомат)
  ARC_SIDE_CANNON_HALF_RAD    = 1.2566  // 80%
  ARC_SIDE_MG_HALF_RAD        = 1.0996  // 70% (задел на будущий)
  ARC_LASER_HALF_RAD          = 1.4923  // 95%
  ARC_ROCKET_HALF_RAD         = 1.4923  // 95%
  ```
- ✅ **Хелперы:** `isWithinArc(a, sx, sz, halfArc)`, `centralWeaponHalfArc()` (dispatch по `currentWeapon.id`), `nearestAsteroidInArc(sx, sz, halfArc)` (для боковых — filter+nearest), `bestHpTargetInArc(sx, sz, halfArc)` (для центральной — filter+max-HP+tiebreak nearest).
- ✅ **Priority lock = master target** — Tap на астероид: `centralTargetId = picked.id` (новый лок) или `null` если уже залочен (re-tap toggle). Lock пережит изменение arc-relations: `centralTurretTarget()` возвращает locked **независимо от дуги**. Auto-pick (lock=null) ограничен дугой текущего оружия центральной.
- ✅ **Центр клампит вращение в дугу** — `targetAngleRaw = atan2(...)` (не клампится, для `aimAligned`), `targetAngleClamped = raw.coerceIn(-halfArc, +halfArc)` (smoothing target). Турель доводит ствол до края дуги и встаёт. `aimAligned` сравнивает `centralTurretAngle` с `targetAngleRaw` — у out-of-arc цели турель упёрлась в clamp, diff остаётся = arc-edge-to-target, всегда выше threshold → fire-gate не пропускает. **Турель ведёт цель но не стреляет когда не достаёт.**
- ✅ **Лазер: тот же мастер-таргет + `canEngage` gate** — `aimSelector = { centralTurretTarget() }` (как в первой версии), но в `Beam` добавлен `canEngage: (Asteroid) -> Boolean` closure. Лазер передаёт `{ a -> isWithinArc(a, dome, ARC_LASER_HALF_RAD) }`. Каждый тик: если `canEngage(target)` → ray-cast + урон + видимый луч; иначе → `endPos = src` (нулевая длина = невидимо), `dmgAccum = 0`, **таймер тикает дальше**. Цель умерла → beam terminate.
- ✅ **Ракетная шахта** — `findRocketTargets` теперь считает top-N **от позиции шахты, не центра**, фильтрует на `ARC_ROCKET_HALF_RAD = 95%`. Шахта может стрелять по флангам, до которых центр не достаёт.
- ✅ **Боковые турели** — переехали с `nearestAsteroid(tx, tz)` на `nearestAsteroidInArc(tx, tz, ARC_SIDE_CANNON_HALF_RAD)` (80%). И в aim-loop'е, и в fire-loop'е. Стволы не пытаются развернуться за горизонт.

Сценарии:

| Ситуация | Центр | Лазер |
|---|---|---|
| Лок на цель в обоих дугах | Стреляет | Стреляет |
| Лок на цель только в дуге лазера | Ведёт ствол на edge, молчит | Стреляет |
| Лок на цель за дугами обоих | Ведёт на edge, молчит | Луч скрыт, таймер идёт |
| Лок умер → auto-pick | Подхватывает в своей дуге | Следует за тем же auto-pick |
| Re-tap на лок | Релиз → auto-pick | Auto-pick (если в дуге лазера) |

## Старый бэклог (мелкая шерсть)

Сохранён до решения по релевантности:

- **Контент:** реальные модели вместо квадратиков (центральная турель, боковые турели, база); звуки (выстрел, попадание, взрыв, фон); реальные иконки апгрейдов вместо цветных квадратиков.
- **UI/UX:** параллакс-фон со звёздами и туманностью; визуальная деградация базы по мере падения HP (трещины, искры, дымок); полноценная вёрстка экранов вместо программно собранных оверлеев.
- **Тех. долг:** g3-инфраструктура (`SimulationWorld`, `StationAI`, `MissionController`, `SceneAdapter`, `FleetRegistry`, `voice/`, `sound/`, `ml/`) лежит в исходниках, но не используется — можно вычистить либо оставить как реликт. Тесты (g3 имел юнит-тесты в src/test) не перенесены — потребуется обновить package декларации.
- **Релиз:** иконка приложения сейчас зелёная g3-шная — заменить.
- **Мета:** разблокировка миссий по прогрессу (миссия N открывается после прохождения N−1); daily missions; экран настроек.
- **Debug-toolset для скриншотов VFX (2026-05-07):** короткие VFX-события (~0.1-0.5 сек) сложно поймать вручную для скрина. Нужен pause-toggle (debug-кнопка / системный жест), который шорт-сёркитит tick-loop (`if (paused) { buildScene(); return }`), но оставляет рендеринг живым. Сцена застывает в текущем состоянии, делается скрин, тап → продолжение. Вариант: автопауза на AoE-события под debug-флагом. ~30 строк Kotlin, движок не трогается. Альтернатива без кода — `adb shell screenrecord` + scrub видео.

## Технические заметки

- **Движок:** 3D Vulkan (g3), используется в режиме фиксированной side-view камеры. Большая часть g3-инфраструктуры в коде есть, но **в рантайме обходится** — `buildScene()` собирает сцену напрямую, tick-loop выполняет только логику Outpost.
- **Геометрия:** единый quad в плоскости X-Z с двусторонними треугольниками. `SceneObject` расширен полями `scaleX/Y/Z` и `rotationY` специально для этого draft.
- **Координаты:** X — горизонталь экрана, Z — вертикаль, Y — глубина (всегда 0). Видимая область: X ∈ [−2.47, +2.47], Z ∈ [−1.49, +9.49] при экране ~1080×2400.
- **State:** `GameProgress` (persistent: металл, уровни апгрейдов) сохраняется через `ProgressRepository` в `SharedPreferences`. `MissionRun` (in-flight: статы текущего прогона) сбрасывается на старте миссии. Игровой state-machine — enum `MENU/PLAYING/WON/LOST`, tick работает только в `PLAYING`.
- **Все g3-UI оверлеи** (кнопки команд, axis-индикатор, ship-card, mic, settings) скрыты в `MainActivity.onCreate`. Outpost-UI собирается программно через `OverlayFactory`.
- **Пакет:** `com.example.asteroidoutpost`. JNI-символы соответственно `Java_com_example_asteroidoutpost_*`.
