# ArcBuds — vendor-free control for QCY HT07

Replaces the QCY Android app with a single local HTML page that talks to the
earbuds' BLE GATT service directly. No APK, no account, no vendor SDK, no
network access, nothing to install on the phone.

```
web/index.html      the whole UI, one file, zero dependencies
android/            native Android app (same protocol, builds via GitHub Actions)
tools/serve.py      local server (localhost = secure context for Web Bluetooth)
tools/qcyctl.py     terminal client, same protocol
tools/scan.py       decode QCY advertisements (battery, MACs, vendor id)
qcy/                Python protocol library
docs/protocol.md    command reference
```

## Reality check on "remove proprietary software"

Two different things live under that phrase, and only one is achievable:

| Component | Replaceable? | Why |
|---|---|---|
| **The phone app** | Yes, completely | This repo does it. It only ever spoke BLE GATT; the protocol is plain and documented. |
| **The earbud firmware** | No | WQ7003AR (Wuqi) has no public SDK, no open TWS firmware, no documented flashing interface, and no known recovery path. OTA updates are signed and delivered through QCY's cloud. |

The important part: **the earbuds themselves have no network access.** They are a
Bluetooth audio sink with a small settings NVM. Any telemetry or data collection
lived in the app, on your phone. With the app gone, there is no proprietary code
left in the loop that can phone home — the buds just play audio and respond to
GATT writes.

What you should *not* do: try to flash or "de-bloat" the firmware. There is no
image to flash it with, and the debug pads are undocumented. You would end up
with a pair of dead earbuds and no way back.

## Verifying what the app was doing

If you still have the QCY app installed, you can confirm the above yourself:
packet-capture the phone's traffic (Android's *Developer options → Enable
Bluetooth HCI snoop log*, or Wireshark with an nRF sniffer). Everything it does
is local GATT writes to `0x1001` and reads from `0x1002`. Its only outbound
traffic is model metadata and firmware-check requests.

## Use it

### On this Mac (works right now)

```bash
cd ~/Desktop/qcy-arcbuds
./.venv/bin/python tools/serve.py
```

Open <http://localhost:8770> in Chrome or Edge, click **Connect earbuds**,
pick the buds. Take them out of the case first — and if your phone is connected
to them, disconnect it there, because the phone holds the link.

### On your phone

`web/index.html` is self-contained, so any HTTPS static host works. This repo
already lives on one: open <https://qcy-arcbuds.netlify.app> in Chrome on
Android and connect. To redeploy after edits: `netlify deploy --prod --dir web`.
The page makes no network requests at all; the host only serves the file.

Serving it from this machine over the LAN also works, but plain `http://` is not
a secure context and Chrome blocks Web Bluetooth. `tools/serve.py` prints the
workaround (the `unsafely-treat-insecure-origin-as-secure` flag).

### Terminal

```bash
./.venv/bin/python tools/qcyctl.py info       # battery + firmware
./.venv/bin/python tools/qcyctl.py state      # current settings + EQ
./.venv/bin/python tools/qcyctl.py anc anc    # off | anc | outdoor | transparency
./.venv/bin/python tools/qcyctl.py set ldac on
./.venv/bin/python tools/qcyctl.py gatt       # dump the GATT tree
```

## Getting the real data out of your pair

```bash
./.venv/bin/python tools/scan.py --all
```

That prints the `vendorId` (the only way these earbuds identify themselves),
battery state straight out of the advertisement, and the control MAC. Note it on
your unit — it is what the QCY app uses to pick a feature set, and it is the
number to put in an issue if something here misbehaves.

## Status

| Area | State |
|---|---|
| Advertisement parsing | verified against the real advertisement layout |
| Framing / packet parse | verified, round-trips |
| ANC, game mode, LDAC, in-ear, balance, volume, rename, auto-off | implemented, unverified on hardware |
| Parametric EQ read/write | implemented, unverified on hardware |
| Web UI | connects and drives all of the above |
| Realtime noise meter | local mic SPL graph (60 s trace, min/Leq/peak, ANC + manual markers) — live with the buds disconnected too |

The protocol in `docs/protocol.md` was reverse-engineered on the **HT07
specifically**, so the command set should line up. Still, firmware revisions
differ — verify with **Packet console** before trusting a toggle.

## Safety

- **Packet console** writes raw commands. `03 00` factory-resets, `02 00` clears
  pairing. Nothing there is irreversible except those two.
- EQ writes are clamped to ±12.7 dB, which is the device's own limit.
- Don't rename the device to something you won't recognise, or you will have
  trouble finding it in the Bluetooth picker.

## Credits

Protocol knowledge stands on the public reverse-engineering write-up in
[hui1601/Quicky](https://github.com/hui1601/Quicky) (`docs/protocol.md`,
`docs/service.md`). That repository has no license, so its source is not copied
here — this is an independent implementation of the wire format, and the
documentation below is written from scratch. Keep that in mind if you
redistribute.
