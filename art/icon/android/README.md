# Asteroid Outpost — иконка приложения

Это набор иконок, сгенерированных как замена дефолтной зелёной g3-иконки
(см. `ROADMAP.md` → бэклог → «иконка приложения сейчас зелёная g3-шная — заменить»).

## Концепция

Тёмно-синий космический фон в тон игровому небу (`#1F2F4F`-семейство),
астероид-силуэт по центру, циановый луч пули снизу вверх — суть игры
«базовая оборона от падающих астероидов» в одном кадре.

## Что в папке

```
android/
├── ic_launcher_*.svg                 — векторные исходники
├── ic_launcher_512.png               — превью / Play Store (опционально)
├── ic_launcher_1024.png              — превью / Play Store
├── mipmap-mdpi/                      — 48×48 launcher, 108×108 adaptive
├── mipmap-hdpi/                      — 72×72 launcher, 162×162 adaptive
├── mipmap-xhdpi/                     — 96×96 launcher, 216×216 adaptive
├── mipmap-xxhdpi/                    — 144×144 launcher, 324×324 adaptive
└── mipmap-xxxhdpi/                   — 192×192 launcher, 432×432 adaptive
```

В каждой `mipmap-*`:
- `ic_launcher.png` — квадратный launcher (для устройств без adaptive icon)
- `ic_launcher_round.png` — круглый launcher (для round mask)
- `ic_launcher_foreground.png` — переднее тело adaptive icon (астероид + луч + звёзды)
- `ic_launcher_background.png` — фоновый слой adaptive icon (космос с градиентом)

## Как подключить (для Claude Code)

Самый простой и совместимый путь — заменить статические PNG в `app/src/main/res/mipmap-*/`:

1. **Backup**: `git add -A && git commit -m "snapshot before icon replacement"`
2. **Удалить старые webp**:
   ```
   rm app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.webp
   rm app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.webp
   ```
3. **Скопировать новые PNG** из `art/icon/android/mipmap-*/ic_launcher.png` и
   `ic_launcher_round.png` в `app/src/main/res/mipmap-*/` соответственно.
4. **Поправить XML adaptive icon**:
   - `app/src/main/res/mipmap-anydpi/ic_launcher.xml` — заменить `@drawable/ic_launcher_foreground/_background` на `@mipmap/ic_launcher_foreground/_background`,
     либо удалить эти XML вместе с `drawable/ic_launcher_foreground.xml` и
     `drawable/ic_launcher_background.xml`, и тогда Android возьмёт PNG из mipmap.
5. **Удалить старые drawable** (после шага 4):
   ```
   rm app/src/main/res/drawable/ic_launcher_foreground.xml
   rm app/src/main/res/drawable/ic_launcher_background.xml
   ```
6. **Сборка и проверка**: `./gradlew clean assembleDebug installDebug`

## Альтернативный путь (vector → mipmap)

Если хочешь сохранить векторное представление, есть два варианта:
- Вставить SVG-исходники в Android Studio через File → New → Vector Asset
  (Studio сам сконвертирует в `<vector>` XML).
- Или использовать `androidx.core.graphics.drawable` API для adaptive icon из
  XML-векторов — это уже сделано в `mipmap-anydpi/ic_launcher.xml`, нужно
  лишь подменить ссылки на новые ресурсы.

## Источники / редактирование

Если нужно подкрутить (цвета, форму астероида, размер луча) — правь
SVG-исходники в `art/icon/`:
- `ic_launcher_preview.svg` — полная композиция (для рендера квадратного и
  круглого launcher-а)
- `ic_launcher_foreground.svg` — только астероид + луч + звёзды
- `ic_launcher_background.svg` — только фон
- `ic_launcher_round_preview.svg` — composite с круглой маской

После правки SVG — заново прогнать рендер по DPI-бакетам (см. скрипт-генератор).
