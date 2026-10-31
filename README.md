# biwurth

A wall display for Home Assistant on an Android tablet: a kiosk launcher that shows your home on
pages of tiles, configured from a website the tablet hosts itself, and that shows up in Home
Assistant as an MQTT device. Built and tested on a Cepter Tab Nexa (CTABNEXT10C, Unisoc T7250,
Android 16, 4 GB RAM); any Android 11+ tablet should do.

- **Panel** (the tablet screen): pages of tiles you swipe between: clock, entity value,
  toggle/button, history graph (several sensors per graph), weather, calendar, camera. Any
  resolution and orientation, with colour themes and the Inter font (tabular figures). The
  background is the theme colour, a colour, a gradient (optionally slowly turning) or a picture
  (fill, fit, stretch, tile, centre, dimmed), and tiles can be see-through.
- **Website** on the tablet (`http://<tablet>:2323`): drag-and-drop layout editor, pages,
  orientation, grid and theme, plus Home Assistant, MQTT and screen settings.
- **Own data sources**, with or without Home Assistant: HTTP/JSON APIs, MQTT topics, status
  checks and Modbus TCP, as entities every tile can show, with history kept on the tablet.
- **MQTT device**: the tablet appears in Home Assistant with a screen switch, brightness,
  sleep timeout and page select, and battery, charging, last touch, Wi-Fi and IP sensors.

## Build and install

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f io.github.thinke.biwurth
adb shell cmd package set-home-activity io.github.thinke.biwurth/.MainActivity
```

Use the release build on the tablet: the debug build is several times slower (swiping pages
stuttered up to 500 ms per frame in debug, 24 ms in release). Both are signed with the debug
key, so either installs over the other and keeps the settings.

## Website

Open `http://<tablet IP>:2323` and enter the key from the tablet's Settings (tap the top-left
corner of the panel five times). `http://<tablet IP>:2323/#key=<key>` logs in directly and is
fine to bookmark on your own devices.

- **Layout**: drag tiles to move, drag the corner to resize; pages (× on the tab deletes one),
  orientation, grid size, theme and font; export a page to a file and import it on any panel.
- **Compile & save**: the tablet checks the layout (overlaps, missing or non-numeric entities,
  cameras without a source), preloads its data (history, forecasts, calendars, snapshots) and
  only then switches to it. The report shows the time taken and any warnings.
- **Cameras**: go2rtc address (Frigate's built-in one works), live stream limit, and whether
  cameras stay live while the backlight is off. A camera tile takes a go2rtc stream name or a
  full `rtsp://` / HLS URL; tap it for full screen.
- **Backup**: everything above in one file, the HA token and MQTT password only if ticked;
  restore applies a file (and compiles its layout).
- **Home Assistant**: URL and long-lived access token (profile → Security). The token is stored
  on the tablet only and never sent back to the browser.
- **MQTT**: broker host, port, user, password, TLS and the device name in Home Assistant.
- **Screen**: sleep timeout, sleep mode, brightness, wake/sleep now.

## Data sources

Website → **Sources**. Home Assistant is one of them (on/off; its URL and token are on the Home
Assistant tab); the others can be added as many times as needed, each switched on or off. Every
field becomes an entity `src.<source>.<field>` (Home Assistant ids have one dot, these two),
which any tile can show. Numeric values are kept on the tablet for two weeks, so graphs work
without Home Assistant and survive restarts.

| Source | Settings | Each field |
|---|---|---|
| HTTP / JSON | URL, interval, headers (`Name: value` per line, e.g. API keys) | a JSON path: `current.temperature_2m`, `data[0].value`; empty = the whole reply |
| MQTT topics | the broker from the MQTT tab | a topic and optionally a JSON path; a command topic with on/off payloads makes it a button |
| Status checks | interval | an `http(s)://` URL (up below status 400) or `host:port` (TCP); also `<field>_ms` |
| Modbus TCP | host, port, unit id, interval | holding/input register (int16, uint16, int32, uint32, float32, word order, scale) or coil/discrete input; a coil is a button that switches it |

Backups include the sources; HTTP headers only with "include secrets", as they often hold keys.

## Planned

**CAN bus.** The tablet's kernel has CAN networking (`CAN_RAW`, `CAN_BCM`) but no CAN
interfaces or USB adapter drivers, and bringing one up needs root, so SocketCAN can't be used
on the tablet itself. The routes that work without root:

- a USB CAN adapter driven by biwurth in user space over Android's USB host API (the tablet has
  it): slcan/Lawicel adapters (CANable with slcan firmware) and candleLight/gs_usb (CANable 2,
  CANtact), with an OTG cable that also charges the tablet;
- SocketCAN over the network from a Linux machine or Raspberry Pi running `socketcand` or
  `cannelloni`;
- a CAN → MQTT bridge (e.g. an ESP32), read with an MQTT source.

Frames would be decoded by simple signal definitions (id, start bit, length, byte order, scale,
offset, unit) or an uploaded DBC file, and buttons could send frames.

## Releases and updates

Pushing a tag `v1.2.3` builds a signed APK on GitHub Actions and publishes it as a release with
its `.sha256` (`.github/workflows/release.yml`); a tag with a suffix (`v1.2.3-rc1`) becomes a
pre-release. The repo needs four secrets, the same keystore as SnapTV: `BIWURTH_KEYSTORE_BASE64`
(`base64 -w0 release.jks`), `BIWURTH_KEYSTORE_PASSWORD`, `BIWURTH_KEY_ALIAS`, `BIWURTH_KEY_PASSWORD`.

The app checks for releases daily (website → Updates, or Home Assistant's update entity), checks
each download against its SHA-256 and the installed app's signing key, and installs it. Updates
only reach release builds signed with that key: install one release by hand first; debug builds
never update.

## One-time tablet setup (over adb)

Device admin (for the "Screen off" sleep mode) needs no adb: **Allow screen off** in the
tablet's Settings, or on the website's Screen tab, opens Android's own dialog on the tablet.
**Remove device admin** in the tablet's Settings gives it back, which Android requires before
biwurth can be uninstalled. The rest:

```sh
# Keeps Wireless debugging on after reboots
adb shell pm grant io.github.thinke.biwurth android.permission.WRITE_SECURE_SETTINGS
# Background: no Doze or app standby, so MQTT and the website stay up with the screen off
adb shell cmd deviceidle whitelist +io.github.thinke.biwurth
adb shell am set-standby-bucket io.github.thinke.biwurth active
```

A foreground service (`PanelService`) keeps the process running when another app is in front,
and holds the CPU awake while the screen is off so MQTT keep-alives don't stall.

### Kiosk mode (device owner)

Android only allows a device owner when no accounts are on the tablet, so remove the Google
account first (Settings → Passwords & accounts). Then:

```sh
adb shell dpm set-device-owner io.github.thinke.biwurth/.AdminReceiver
```

biwurth configures itself the moment it becomes device owner, no restart needed: it disables
the lock screen, stays the home app, locks the tablet to itself (no
status bar, Home or Recents), grants itself any runtime permission without prompts and installs
its updates silently. Remove the screen lock PIN first: Android won't drop a lock screen that has
one. Turn kiosk mode off in the tablet's Settings to reach Android.

adb can't remove a device owner. To undo it, use **Release device owner** in the tablet's
Settings (top-left corner, five taps); after that biwurth can be uninstalled.

### Setting up a new tablet with a QR code

Instead of adb, a factory-reset tablet can become a biwurth panel straight from Android's setup:
on any running panel's website, Backup → **Set up a new tablet** shows a QR code (optionally with
Wi-Fi). On the new tablet's first "Welcome" screen, tap six times in the same spot and scan it:
Android downloads the latest biwurth release from GitHub, checks it's signed with the release
key, and makes it device owner, which then configures itself. No Google account is added.
Restore a backup from the old panel's website to bring the layout over. The code only works
from a panel running a release build (it carries the release key's checksum).

## Sleep

| Mode | Asleep | Wakes on |
|---|---|---|
| Backlight off (default) | screen on, backlight 0, black | a touch (the touch itself is not passed on), MQTT, the API |
| Screen off | screen really off (`lockNow`) | the power button, MQTT, the API |

The tablet's double-tap-to-wake setting is on but the touchscreen ignores it, hence these modes.
Going to sleep returns the panel to its first page.

## MQTT device

Uses Home Assistant's device discovery (`homeassistant/device/biwurth_<id>/config`), so it needs
Home Assistant 2024.11 or newer and the MQTT integration. Topics live under `biwurth/biwurth_<id>/`:
`state` (retained JSON), `availability` (`online`/`offline`, also the last will) and
`<screen|brightness|sleep_after|page>/set`. The device's page in Home Assistant links back to
the website.

## HTTP API

Every `/api/*` call and the three below need `Authorization: Bearer <key>`:

```sh
curl -H "Authorization: Bearer $KEY" http://<tablet>:2323/state   # {"screen":"awake"|"dimmed"|"off"}
curl -X POST -H "Authorization: Bearer $KEY" http://<tablet>:2323/wake
curl -X POST -H "Authorization: Bearer $KEY" http://<tablet>:2323/sleep
```

Give the tablet a fixed IP (DHCP reservation) so the website and these keep working.

## License

MIT, see [LICENSE](LICENSE). The bundled Inter font is under the SIL Open Font License
([licenses/Inter-OFL.txt](licenses/Inter-OFL.txt)).
