# Wireless ADB — подключение телефона по Wi-Fi

Когда USB-кабель влом, или хочется поставить APK без кабеля.

## Условия

- ПК и телефон в **одной сети** (одна точка, без AP isolation на роутере).
- На телефоне включены **Developer options** → **Wireless debugging**.
- adb версии **≥ 33**. Проверка: `.\adb.exe --version`.

## Шаг 1 — однократный pair (только при первой настройке)

На телефоне: **Settings → Developer options → Wireless debugging → Pair device with pairing code**.

Появятся:
- `IP address & Port`, например `192.168.0.100:46825`
- 6-значный код, например `472287`

⚠️ Эти значения протухают за ~30 сек. Делай быстро.

В PowerShell на ПК:

```powershell
cd $env:LOCALAPPDATA\Android\Sdk\platform-tools
.\adb.exe pair 192.168.0.100:46825
# вводишь код 472287
```

Должен ответить:
```
Successfully paired to 192.168.0.100:46825 [guid=adb-...]
```

Если получил `protocol fault (couldn't read status message)` — порт уже обновился.
Закрой/открой `Pair device with pairing code`, бери новый порт+код и повтори.

## Шаг 2 — connect (каждый раз после toggle off/on или перезагрузки)

Вернись на главный экран **Wireless debugging**. Под надписью `IP address & Port`
там **другой** порт (не тот, что был в pairing — connect-порт отдельный).

Например: `192.168.0.100:45739`.

```powershell
.\adb.exe connect 192.168.0.100:45739
```

Ожидаемый ответ: `connected to 192.168.0.100:45739`.

Проверка:
```powershell
.\adb.exe devices
```
В списке должна появиться строка вида `192.168.0.100:45739   device`.

## Гочи

- **Pair port ≠ Connect port.** Pair одноразовый, connect — каждый сеанс.
- **Connect port меняется** при toggle off/on Wireless debugging и при перезагрузке телефона. Pair при этом сохраняется (записан в paired devices), его повторять не надо.
- **`cannot connect: 10061` (refused)** — порт неправильный или толгл выключен. Зайди на телефоне на Wireless debugging, перепиши свежий IP:Port.
- **Студия не видит устройство после успешного `adb devices`** — рестарт adb-сервера:
  ```powershell
  .\adb.exe kill-server
  .\adb.exe start-server
  .\adb.exe connect 192.168.0.100:<порт>
  ```

## Альтернатива — QR через Android Studio

Если CLI не хочется:
1. В Studio: `View → Tool Windows → Device Manager → Pair using Wi-Fi → Pair using QR code`.
2. На телефоне: `Wireless debugging → Pair device with QR code`, скан.
3. Studio сам делает pair + connect.
