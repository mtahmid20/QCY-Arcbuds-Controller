# QCY standard BLE protocol (HT07 / ArcBuds)

Independent write-up of the wire format used by QCY earbuds. The command
semantics below were established by observing the HT07's traffic and cross-checked
against the public reverse-engineering notes in
[hui1601/Quicky](https://github.com/hui1601/Quicky) (which is unlicensed, so no
text or code is reproduced here).

Chipset: **Wuqi WQ7003AR**. This is the "QCY standard" protocol path — QCY also
ships Jieli, Airoha and ZR variants whose ANC/EQ handling differs. If a command
below is ignored, you are probably on one of those.

## GATT layout

Main service `0000a001-0000-1000-8000-00805f9b34fb`.

| UUID (`0000xxxx-…-00805f9b34fb`) | Mode | Purpose |
|---|---|---|
| `1001` | write | command input — everything is framed here |
| `1002` | notify | command output / state updates |
| `0007` | read | firmware version, 3 or 6 bytes |
| `0008` | read | battery, `[left, right, case]` |
| `000b` | write | simple EQ (unframed) |
| `000d` | write | key/touch mapping (unframed) |
| `0009` | read | prompt language |

Standard characteristics in the same service (`2a19` battery, `2a24` model,
`2a26` firmware, `2a29` manufacturer) are also usually readable.

Battery byte: bit 7 = charging, bits 0–6 = percent.

## Framing

```
ff  <bodyLen>  <cmd> <paramLen> <params…>  [<cmd> <paramLen> <params…>] …
```

`bodyLen` counts everything after it. Several commands may share one write or
one notification; the parser walks the body by `paramLen`.

Two characteristics bypass this framing entirely: `000b` (EQ) and `000d`
(key mapping) take raw payloads.

## Discovery

Filter BLE advertisements on manufacturer company id **`0x521c`**. Layout of
that manufacturer data (needs ≥20 bytes; ≥24 for MACs):

| Offset | Meaning |
|---|---|
| 0–1 | vendor id, big-endian |
| 3 | colour, `(b & 0x18) >> 1` |
| 5 | left battery (bit7 = charging) |
| 6 | right battery |
| 7 | case battery |
| 11–16 | control MAC, scrambled: `12:11:13:16:15:14` |
| 18–23 | other bud MAC, scrambled: `19:18:20:23:22:21` |

The earbuds never report their model name over GATT. `vendorId` is the only
identity, and it is what selects a feature set in QCY's app.

Three MACs exist per pair: the advertising BLE address, the control MAC, and the
other bud's MAC. Connect to the advertising address; the control MAC is what the
classic audio link uses.

## Commands

Sent as `[cmd, len, params…]` inside the frame above.

| Cmd | Params | Meaning |
|---|---|---|
| `01` | 0 | reset settings (keeps pairing) |
| `02` | 0 | clear pairing |
| `03` | 0 | factory reset |
| `04` | 1 | music: 1 play, 2 pause, 3 prev, 4 next |
| `06` | 1 | in-ear detection: 1 on, 2 off |
| `08` | 3 | volume `[left, right, 0]` |
| `09` | 1 | low latency: 1 on, 2 off |
| `0c` | 1 | noise: 0 off, 1 anc, 2 outdoor, 3 transparency |
| `10` | 1 | sleep mode |
| `14` | 4 | auto power-off, minutes LE16 + 2 reserved |
| `16` | 1 | balance 0…100 (50 = centre) |
| `17` | 3 | ANC scene `[mode, subScene, noise]` |
| `18` | n | rename, UTF-8 |
| `1d` | 1 | prompt tone volume |
| `20` | n | parametric EQ v1, 6 bytes/band |
| `22` | n | parametric EQ v2, 7 bytes/band |
| `23` | 1 | LDAC |
| `27` | 1 | adaptive EQ |
| `2b` | n | key mapping, `[keyId, funId]` pairs |
| `2c` | 3–4 | wearing detection |
| `2d` | 1 | spatial audio |
| `2f` | 3 | battery `[left, right, case]` |
| `30` | 3 or 6 | firmware version |
| `3e` | 7 | sync time |
| `3f` | 7 | alarm |
| `46`/`47` | n | per-channel EQ, left/right |
| `4a` | 1 | game-mode config |
| `fe` | 1 | **read**: request current value of any command |

Values of `1`/`2` mean on/off for the toggle commands.

### Reading a setting

Send `fe 01 <cmd>`; the device answers with that command's own response format
on `1002`. This is how the UI populates every toggle without guessing.

### ANC scenes

For models with scene support, `0x0c` is too coarse and the app sends `0x17`:

| mode | subScene | Scene |
|---|---|---|
| 0 | 0 | off |
| 2 | 1–3 | silent environment, level 1–3 |
| 3 | 1–3 | working environment, level 1–3 |
| 4 | 1–3 | noisy environment, level 1–3 |
| 0x0a | 1–7 | transparency, level 1–7 |

The app packs these into one integer as `(mode << 16) | (sub << 8) | noise`.

## EQ

Parametric, 7 bytes per band in v2:

```
[eqIndex, masterGain LE16, (freq LE16, gain LE16, q LE16, bandType) × N]
```

`gain` and `masterGain` are signed hundredths of a dB, clamped to ±12.7 dB.
`freq` is raw Hz. `q` is hundredths. v1 is the same minus `bandType`.

Practical approach, and what the web UI does: read the current profile with
`fe 01 22`, keep the device's own frequencies/Q/types, adjust only gains, write
back. That avoids inventing a band layout the DSP does not have.

## Key mapping

Unframed pairs on `000d`: `[keyId, funId, …]`.

| keyId | Gesture | | funId | Action |
|---|---|---|---|---|
| 01/02 | L/R single tap | | 00 | none |
| 03/04 | L/R double tap | | 01 | play/pause |
| 05/06 | L/R triple tap | | 02 | previous |
| 07/08 | L/R quad tap | | 03 | next |
| 09/0a | L/R long press | | 04 | voice assistant |
| 15–1e | same, call mode | | 05/06 | volume ± |
| | | | 07 | game mode |
| | | | 08/09/0a | answer/reject/hold |
| | | | 0b | redial |
