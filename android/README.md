# ArcBuds — native Android app

Vendor-free control for QCY HT07 / ArcBuds as a real app. Talks to the buds'
BLE GATT command service directly (write `0x1001`, notify `0x1002` in service
`a001`) — the same protocol as the web UI and `tools/qcyctl.py`. No vendor
SDK, no account, no network access at runtime.

The Bud-finding is automatic: it scans for the QCY company id in BLE
advertisements (0x521C — the same signal `tools/scan.py` keys on) and
connects. There is no device picker to trip over.

## Getting the APK (GitHub Actions — no local tooling needed)

This machine has no Java/Android SDK, so the APK is built remotely:

1. Create a GitHub repo and push this project to it (see the steps printed by
   `git init` — nothing else is needed, the workflow lives at
   `.github/workflows/arcbuds-apk.yml` and builds `android/`).
2. On the repo page, open the **Actions** tab → **ArcBuds APK** run →
   download the `arcbuds-debug-apk` artifact.
3. Unzip on the phone, tap the APK. Android will warn about installing from
   unknown sources — allow it for your file manager. Play Protect may show a
   second warning (there is no Play signing for a debug build; the APK is
   signed with an ephemeral debug key generated in CI).

The APK is a debug build: it is only for your own install, not distribution.

## Building locally instead

Install Android Studio, open the `android/` folder, press Run with the phone
plugged in (USB debugging enabled). Same project, no workflow needed.

## Permissions

- Android 12+: *Nearby devices* (BLUETOOTH_SCAN + BLUETOOTH_CONNECT both marked
  neverForLocation, so no location prompt).
- Android 6–11: *Location* is required by the OS for BLE scanning.

## Layout of the app

```
app/src/main/java/dev/mtahmid/arcbuds/
  Protocol.kt      packet framing/parse — port of qcy/protocol.py
  QcyDevice.kt     BLE client: scan hit → GATT connect → MTU 247 → subscribe
  MainActivity.kt  UI (battery, ANC, EQ, volume/balance, rename, auto-off,
                   packet console) mirroring the web UI's command set
```
