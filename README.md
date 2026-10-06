# HUD for Audi A4 B9 (MLB-Evo) — Android companion app

[Русская версия](README_ru.md) · [SuperMini firmware and wiring](https://github.com/WARMW00D/HUD-Audi-A4-B9-MLB-Evo-ESP32-S3-SuperMini-LCD-2.79-NV3007-BLE)

Native Android BLE app for the Audi HUD project by WARMW00D. It changes HUD settings and sends application firmware over BLE. App **1.2** (`versionCode 3`), Android **8.0+**, interface **RU / EN**. Package/application ID: `ru.hud.supermini`.

The project grew from the [Waveshare HUD](https://github.com/WARMW00D/HUD-Audi-A4-B9-MLB-Evo-Waveshare-ESP32-S3-Touch-LCD-3.49) and its SuperMini adaptation. **The current app targets the SuperMini phone GATT services:** settings firmware v28.1/v28.2 and v29.0; OTA requires v29.0 or a compatible later firmware. The existing Waveshare firmware needs those services ported before this app can control it.

## Features

| Feature | Behaviour |
|---|---|
| Phone interface | RU / EN, saved locally; switching language preserves the BLE session |
| Connection | Initial scan; saved/bonded HUD can be selected for reconnect |
| HUD language | RU / EN independently of the app language |
| Units | km/h, km, m or mph, mi, ft; litres or US gallons |
| Traffic signs | Enable/disable PSD and VZE separately |
| Confirmed settings | BLE write with response, then read back; firmware stores settings in NVS |
| BLE OTA | Android file picker, application-image checks, SHA-256, progress and cancellation |
| Update result | Separate verification/commit phase; uncertain final ACK prompts restart/check |

The app does not read CAN itself and does not stream a phone map to the HUD. Navigation and vehicle data are decoded by the firmware from I-CAN or the BLE gateway.

## Build

Source is included; no prebuilt APK or signing key is committed.

| Tool / setting | Version |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| Java source/target | 17 |
| JDK to run Gradle | 17 or 21 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 |

Install Android Studio and SDK Platform 35; install the Build-Tools requested by AGP (34.0.0 in this setup). Use an ASCII-only Windows project path, for example `D:\HUD\android`; a parent directory containing Cyrillic can trigger the AGP path check.

The repository includes a Windows bootstrap script that downloads the official Gradle 8.9 distribution, verifies its SHA-256 and generates the wrapper in an isolated bootstrap project:

```powershell
powershell -ExecutionPolicy Bypass -File .\prepare-and-build.ps1
```

It runs `:app:assembleDebug` and `:app:lintDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`. If needed, pass `-JavaHome` and `-SdkPath`. Use `-PrepareOnly` to generate the wrapper, then open the root folder in Android Studio.

For Linux/macOS, generate the wrapper with an installed Gradle 8.9 (`gradle wrapper --gradle-version 8.9`), set the Android SDK path locally and run `./gradlew :app:assembleDebug :app:lintDebug`. Generated wrapper files are not included in this source snapshot.

To update an installed app, retain **applicationId and the same signing key**. Debug keys can differ between computers. Do not commit `local.properties`, private signing keys or passwords. Release signing is configured by the maintainer outside this repository.

## First connection

1. Start the HUD. An unowned HUD advertises as **HUD-SuperMini**.
2. Open the app, allow the Bluetooth permissions requested by Android and scan. Older Android versions may require location permission/service for BLE scanning.
3. Select the HUD and complete the Android pairing dialog. Default PIN: **482731**, set by firmware `HUD_SETTINGS_PIN`.
4. The first authenticated phone becomes the owner. Read the current settings, then change the required values.
5. Reconnect using the saved/bonded device. After ownership is stored, the HUD uses anonymous/non-discoverable advertising and may not appear by name in a new scan.

If the phone’s bond has been removed, reset ownership on the running HUD: hold **BOOT for 5 s, then release**. Remove the old HUD bond on Android and pair again. HUD settings and gateway keys are retained. Changing the configured PIN does not revoke old bonds by itself.

## Firmware update

First install firmware v29.0 via USB with its **two-slot partition table**, without Erase All Flash. The app cannot migrate partitions.

1. Build HUD firmware and export **`supermini_hud.ino.bin`** — only the application image.
2. Connect the owner phone. Choose `.bin` in the Firmware update section.
3. The app checks size, ESP32-S3 application header and calculates SHA-256.
4. Confirm Update HUD. Keep the app open and power stable; the display stays awake during transfer.
5. At 100%, wait for image verification and boot-slot commit. After reboot, reconnect and check the HUD.

Maximum: **2,031,616 bytes**. Do not select bootloader/partitions/merged images or flash dumps. An arbitrary ESP32-S3 application is not necessarily compatible with the HUD.

Cancellation and disconnect before commit discard the incomplete update; restart from the beginning. A lost final acknowledgment can occur after the new slot has already been selected, so the app reports uncertainty and asks you to wait/check instead of claiming failure. Automatic rollback of a newly booted but faulty application is not guaranteed; recover by USB.

[OTA guide](docs/OTA.md) · [Инструкция OTA](docs/OTA_ru.md)

## BLE interface

Settings service: `74d0a100-3d92-4f50-9b1a-478142000001`. Each setting is one byte, read/write with response:

| UUID suffix | Setting | 0 | 1 |
|---|---|---|---|
| `…0002` | PSD | off | on |
| `…0003` | VZE | off | on |
| `…0004` | HUD language | RU | EN |
| `…0005` | Speed/distance | km | miles |
| `…0006` | Fuel volume | litres | US gallons |

OTA service: `74d0a200-3d92-4f50-9b1a-478142000001`; control `…0002`, data `…0003`. It negotiates MTU, sends one acknowledged operation at a time and reads the confirmed offset after each chunk. Encrypted, authenticated bonding and owner identity are enforced by the HUD.

`MainActivity.java` handles Android BLE, resources and file selection; `OtaTransfer.java` handles the update state machine. The project uses Android platform APIs without third-party app dependencies.

## Troubleshooting and validation

| Symptom | Action |
|---|---|
| Non-ASCII project-path error | Move the entire project to an ASCII-only path |
| Gradle 9/10 compatibility error | Use the project’s Gradle 8.9 with AGP 8.7.3 |
| HUD not listed by name after first pairing | Reconnect through the saved/bonded-device list |
| Settings work but OTA is absent | Install firmware v29.0 and the new table through USB |
| Services changed | Reconnect; if necessary toggle Android Bluetooth and restart HUD |
| Cannot install over an existing app | Check application ID and signing key |

The user reported the earlier settings app working. The v1.2 OTA additions need an Android build/lint run and a transfer test on real hardware. This source snapshot was not compiled into an APK here. Firmware host tests exercise the protocol and failures; they do not replace Android BLE tests. RU/EN resource keys and formatting placeholders were checked for consistency.

## Credits and license

- **WARMW00D (Warmwood):** project owner, vehicle/hardware integration and testing.
- **OpenAI Codex / ChatGPT:** Android BLE app, RU/EN resources, OTA transfer flow, firmware-side integration assistance and documentation.
- **Claude (Anthropic):** development assistance credited by the original Waveshare HUD project.
- Android / AOSP; NimBLE-Arduino on the HUD side.

[MIT License](LICENSE), with the original Warmwood copyright retained. This hobby project is not affiliated with AUDI AG / Volkswagen AG.
