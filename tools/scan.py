#!/usr/bin/env python3
"""Scan for QCY earbuds and decode their advertisement payload."""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from bleak import BleakScanner  # noqa: E402

from qcy.advert import QCY_COMPANY_ID, parse_manufacturer_data  # noqa: E402


async def main(timeout: float, show_all: bool) -> None:
    seen: dict[str, None] = {}
    qcy_hits = 0

    def on_detect(device, adv):
        nonlocal qcy_hits
        mfr = adv.manufacturer_data or {}
        data = mfr.get(QCY_COMPANY_ID)
        decoded = parse_manufacturer_data(data) if data else None

        if decoded is None:
            if show_all:
                key = device.address
                if key not in seen:
                    seen[key] = None
                    print(f"  {device.name or '?':<24} {device.address}  rssi={adv.rssi}")
            return

        qcy_hits += 1
        print("\nQCY device found")
        print(f"  ble address : {device.address}")
        print(f"  name        : {device.name}")
        print(f"  rssi        : {adv.rssi} dBm")
        print(f"  vendorId    : {decoded.vendor_id} (0x{decoded.vendor_id:04x})")
        print(f"  color index : {decoded.color_index}")
        print(f"  battery     : {decoded.battery_summary}")
        if decoded.control_mac:
            print(f"  control MAC : {decoded.control_mac}")
            print(f"  other MAC   : {decoded.other_mac}")
        print(f"  raw         : {decoded.raw.hex(' ')}")

    scanner = BleakScanner(detection_callback=on_detect)
    print(f"scanning {timeout:.0f}s for manufacturer id 0x{QCY_COMPANY_ID:04x}...\n")
    await scanner.start()
    await asyncio.sleep(timeout)
    await scanner.stop()

    if not qcy_hits:
        print("\nNo QCY advertisements seen.")
        print("  - earbuds must be out of the case and awake")
        print("  - disconnect them from your phone first (a phone holds the BLE link)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("-t", "--timeout", type=float, default=10)
    ap.add_argument("--all", action="store_true", help="list every BLE device seen")
    args = ap.parse_args()
    try:
        asyncio.run(main(args.timeout, args.all))
    except KeyboardInterrupt:
        pass
