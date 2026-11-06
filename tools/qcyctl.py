#!/usr/bin/env python3
"""qcyctl — control QCY earbuds from a terminal, no vendor app required."""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from qcy import ANC_OFF, ANC_ON, ANC_OUTDOOR, ANC_TRANSPARENCY, Device  # noqa: E402
from qcy import device as D  # noqa: E402

ANC_MODES = {
    "off": ANC_OFF,
    "anc": ANC_ON,
    "outdoor": ANC_OUTDOOR,
    "transparency": ANC_TRANSPARENCY,
}
ANC_NAMES = {v: k for k, v in ANC_MODES.items()}


async def _attach(args) -> Device:
    dev = Device(address=args.address) if args.address else await Device.discover()
    if not dev:
        sys.exit("no QCY earbuds advertising — take them out of the case")
    await dev.connect()
    return dev


async def cmd_info(dev: Device) -> None:
    print(f"connected: {dev.name or '?'}  ({dev.address})")
    if dev.vendor_id:
        print(f"vendor id: {dev.vendor_id} (0x{dev.vendor_id:04x})")
    bat = await dev.read_battery()
    print(f"battery  : {bat if bat else 'n/a'}")
    ver = await dev.read_version()
    for v in ver:
        print(f"firmware : {v}")
    await dev.disconnect()


async def cmd_gatt(dev: Device) -> None:
    await dev.dump_gatt()
    await dev.disconnect()


async def cmd_anc(dev: Device, mode: str) -> None:
    await dev.set_anc(ANC_MODES[mode])
    print(f"anc -> {mode}")
    await asyncio.sleep(0.4)
    await dev.disconnect()


async def cmd_scene(dev: Device, mode: int, sub: int, noise: int) -> None:
    await dev.set_anc_scene(mode, sub, noise)
    print(f"anc scene -> mode={mode} sub={sub} noise={noise}")
    await asyncio.sleep(0.4)
    await dev.disconnect()


async def cmd_state(dev: Device) -> None:
    for cmd, label in ((D.CMD_NOISE_CANCEL, "anc"), (D.CMD_LOW_LATENCY, "game"),
                       (D.CMD_LDAC, "ldac"), (D.CMD_IN_EAR, "in-ear"),
                       (D.CMD_VOLUME, "volume"), (D.CMD_BALANCE, "balance")):
        r = await dev.request(cmd)
        if not r:
            print(f"{label:<9}: no reply")
            continue
        if cmd == D.CMD_NOISE_CANCEL:
            print(f"{label:<9}: {ANC_NAMES.get(r.params[0], r.params[0])}")
        elif cmd == D.CMD_VOLUME:
            p = r.params
            print(f"{label:<9}: L={p[0]} R={p[1]}" + (f" max={p[2]}" if len(p) > 2 else ""))
        else:
            print(f"{label:<9}: {r.params.hex(' ')}")
    eq = await dev.read_eq()
    if eq:
        print(f"eq       : preset {eq['type']}, master {eq['master_gain']:+.2f} dB, {len(eq['bands'])} bands")
        for i, b in enumerate(eq["bands"], 1):
            print(f"           {i}: {b['freq']:>5} Hz  {b['gain']:+.2f} dB  Q {b['q']:.2f}")
    await dev.disconnect()


async def cmd_set(dev: Device, key: str, value: str) -> None:
    on = value in ("on", "1", "true", "yes")
    match key:
        case "game":
            await dev.set_low_latency(on)
        case "ldac":
            await dev.set_ldac(on)
        case "in-ear":
            await dev.set_in_ear(on)
        case "volume":
            left, right = (int(x) for x in value.split(","))
            await dev.set_volume(left, right)
        case "balance":
            await dev.set_balance(int(value))
        case "auto-off":
            await dev.set_auto_off_minutes(int(value))
        case "name":
            await dev.rename(value)
        case "spatial":
            await dev.send(D.CMD_SPATIAL, bytes([1 if on else 2]))
        case "adaptive-eq":
            await dev.send(D.CMD_ADAPTIVE_EQ, bytes([1 if on else 2]))
        case _:
            sys.exit(f"unknown setting: {key}")
    print(f"{key} updated")
    await asyncio.sleep(0.4)
    await dev.disconnect()


def main() -> None:
    ap = argparse.ArgumentParser(prog="qcyctl")
    ap.add_argument("-a", "--address", help="BLE address/UUID (skip discovery)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("info", help="battery + firmware")
    sub.add_parser("gatt", help="dump GATT tree")
    sub.add_parser("state", help="read current settings")

    p = sub.add_parser("anc", help="set ANC mode")
    p.add_argument("mode", choices=list(ANC_MODES))

    p = sub.add_parser("scene", help="advanced ANC scene (HT07 style)")
    p.add_argument("mode", type=lambda s: int(s, 0))
    p.add_argument("sub", type=lambda s: int(s, 0))
    p.add_argument("--noise", type=lambda s: int(s, 0), default=0)

    p = sub.add_parser("set", help="toggle a setting")
    p.add_argument("key", choices=["game", "ldac", "in-ear", "spatial",
                                   "adaptive-eq", "volume", "balance",
                                   "auto-off", "name"])
    p.add_argument("value")

    args = ap.parse_args()

    async def run():
        dev = await _attach(args)
        try:
            if args.cmd == "info":
                await cmd_info(dev)
            elif args.cmd == "gatt":
                await cmd_gatt(dev)
            elif args.cmd == "state":
                await cmd_state(dev)
            elif args.cmd == "anc":
                await cmd_anc(dev, args.mode)
            elif args.cmd == "scene":
                await cmd_scene(dev, args.mode, args.sub, args.noise)
            elif args.cmd == "set":
                await cmd_set(dev, args.key, args.value)
        finally:
            if dev.connected:
                await dev.disconnect()

    asyncio.run(run())


if __name__ == "__main__":
    main()
