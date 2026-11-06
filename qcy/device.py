"""High-level QCY device control over BLE GATT.

Implements the QCY standard protocol (the one used by HT07 / ArcBuds).
Reference: docs/protocol.md, derived from live traffic + public RE notes.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

from bleak import BleakClient, BleakScanner

from . import protocol as P
from .advert import QCY_COMPANY_ID, parse_manufacturer_data

# ---------------------------------------------------------------- command ids

CMD_RESET_DEFAULT = 0x01
CMD_CLEAR_PAIRING = 0x02
CMD_FACTORY_RESET = 0x03
CMD_MUSIC_CONTROL = 0x04
CMD_IN_EAR = 0x06
CMD_VOLUME = 0x08
CMD_LOW_LATENCY = 0x09
CMD_NOISE_CANCEL = 0x0C
CMD_SLEEP = 0x10
CMD_POWER_OFF_TIMER = 0x14
CMD_BALANCE = 0x16
CMD_ANC_SETTING = 0x17
CMD_RENAME = 0x18
CMD_EQ_V1 = 0x20
CMD_EQ_V2 = 0x22
CMD_LDAC = 0x23
CMD_ADAPTIVE_EQ = 0x27
CMD_WEAR_DETECTION = 0x2C
CMD_SPATIAL = 0x2D
CMD_BATTERY = 0x2F
CMD_VERSION = 0x30
CMD_GAME_CONFIG = 0x4A
CMD_REQUEST = 0xFE

# HT07 (vendor 19779) ANC uses server startcmdid values via 0x0C (verified live).
ANC_OFF = 2          # Normal
ANC_ON = 65          # Indoor level 1
ANC_INDOOR_2 = 66
ANC_INDOOR_3 = 67
ANC_OUTDOOR = 81     # Commuting level 1
ANC_NOISY = 97       # Noisy level 1
ANC_TRANSPARENCY = 160

STATE_ON = 0x01
STATE_OFF = 0x02


@dataclass
class Battery:
    left: int = 0
    right: int = 0
    case: int = 0
    left_charging: bool = False
    right_charging: bool = False
    case_charging: bool = False

    @staticmethod
    def _decode(byte: int) -> tuple[int, bool]:
        return byte & 0x7F, bool(byte & 0x80)

    @classmethod
    def from_bytes(cls, b: bytes) -> "Battery":
        lv, lc = cls._decode(b[0])
        rv, rc = cls._decode(b[1])
        cv, cc = cls._decode(b[2]) if len(b) > 2 else (0, False)
        return cls(lv, rv, cv, lc, rc, cc)

    def __str__(self) -> str:
        def f(level, charging):
            return f"{level}%{'⚡' if charging else ''}"

        return f"left {f(self.left, self.left_charging)}  right {f(self.right, self.right_charging)}  case {f(self.case, self.case_charging)}"


def _fmt_version(b: bytes) -> list[str]:
    if len(b) >= 6:
        return [
            f"left  {b[0]}.{b[1]}.{b[2]}",
            f"right {b[3]}.{b[4]}.{b[5]}",
        ]
    if len(b) >= 3:
        return [f"{b[0]}.{b[1]}.{b[2]}"]
    return []


@dataclass
class Device:
    address: str
    name: str | None = None
    vendor_id: int | None = None
    _client: BleakClient | None = field(default=None, repr=False)
    _notifications: asyncio.Queue = field(default_factory=asyncio.Queue, repr=False)

    # ------------------------------------------------------------- discovery

    @classmethod
    async def discover(cls, timeout: float = 12.0) -> "Device | None":
        """Find the first advertising QCY earbud."""
        found: list[Device] = []

        def cb(dev, adv):
            data = (adv.manufacturer_data or {}).get(QCY_COMPANY_ID)
            if not data:
                return
            info = parse_manufacturer_data(data)
            if info and not found:
                found.append(
                    cls(address=dev.address, name=dev.name, vendor_id=info.vendor_id)
                )

        scanner = BleakScanner(detection_callback=cb)
        await scanner.start()
        try:
            for _ in range(int(timeout * 10)):
                if found:
                    break
                await asyncio.sleep(0.1)
        finally:
            await scanner.stop()
        return found[0] if found else None

    # ------------------------------------------------------------- transport

    async def connect(self, timeout: float = 15.0) -> None:
        self._client = BleakClient(self.address, timeout=timeout)
        await self._client.connect()
        # This firmware advertises duplicate 0x1001/0x1002 characteristics,
        # so resolving by UUID string fails. Resolve by object instead.
        self._write_char = None
        self._notify_char = None
        self._battery_char = None
        self._version_char = None
        # HT07 exposes 1001/1002 in both 7033 and a001 — prefer a001 (docs).
        services = sorted(
            self._client.services,
            key=lambda s: 0 if s.uuid == P.MAIN_SERVICE_UUID else 1,
        )
        for svc in services:
            for ch in svc.characteristics:
                if ch.uuid == P.CMD_WRITE_UUID and self._write_char is None:
                    if "write" in ch.properties or "write-without-response" in ch.properties:
                        self._write_char = ch
                if ch.uuid == P.CMD_NOTIFY_UUID and self._notify_char is None:
                    if "notify" in ch.properties or "indicate" in ch.properties:
                        self._notify_char = ch
                if ch.uuid == P.UUID_BATTERY and self._battery_char is None:
                    self._battery_char = ch
                if ch.uuid == P.UUID_VERSION and self._version_char is None:
                    self._version_char = ch
        if self._notify_char is not None:
            try:
                await self._client.start_notify(self._notify_char, self._on_notify)
            except Exception:
                pass  # some firmware only notifies after first request

    async def disconnect(self) -> None:
        if self._client and self._client.is_connected:
            try:
                if getattr(self, "_notify_char", None) is not None:
                    await self._client.stop_notify(self._notify_char)
            except Exception:
                pass
            await self._client.disconnect()

    @property
    def connected(self) -> bool:
        return bool(self._client and self._client.is_connected)

    def _on_notify(self, _char, data: bytearray) -> None:
        for cmd in P.parse(bytes(data)):
            self._notifications.put_nowait(cmd)

    async def send(self, cmd: int, params: bytes = b"") -> None:
        if not self._client:
            raise RuntimeError("not connected")
        char = getattr(self, "_write_char", None) or P.CMD_WRITE_UUID
        await self._client.write_gatt_char(
            char, P.build_one(cmd, params), response=False
        )

    async def request(self, cmd: int, wait: float = 1.5) -> P.Command | None:
        """Send cmd 0xFE (read setting) and wait for the reply."""
        await self.send(CMD_REQUEST, bytes([cmd]))
        return await self.wait_for(cmd, wait)

    async def wait_for(self, cmd: int, wait: float = 2.0) -> P.Command | None:
        try:
            while True:
                got = await asyncio.wait_for(self._notifications.get(), timeout=wait)
                if got.cmd == cmd:
                    return got
        except asyncio.TimeoutError:
            return None

    async def drain(self) -> list[P.Command]:
        out = []
        while not self._notifications.empty():
            out.append(self._notifications.get_nowait())
        return out

    # ------------------------------------------------------------- reads

    async def read_battery(self) -> Battery | None:
        r = await self.request(CMD_BATTERY)
        if r and len(r.params) >= 3:
            return Battery.from_bytes(r.params)
        if self._client and getattr(self, "_battery_char", None) is not None:
            try:
                raw = await self._client.read_gatt_char(self._battery_char)
                if len(raw) >= 3:
                    return Battery.from_bytes(bytes(raw))
            except Exception:
                pass
        return None

    async def read_version(self) -> list[str]:
        r = await self.request(CMD_VERSION)
        if r:
            return _fmt_version(r.params)
        return []

    # ------------------------------------------------------------- writes

    async def set_anc(self, mode: int) -> None:
        await self.send(CMD_NOISE_CANCEL, bytes([mode]))

    async def set_anc_scene(self, mode: int, sub_scene: int, noise: int = 0) -> None:
        await self.send(CMD_ANC_SETTING, bytes([mode, sub_scene, noise]))

    async def set_volume(self, left: int, right: int) -> None:
        await self.send(CMD_VOLUME, bytes([left, right, 0x00]))

    async def set_low_latency(self, on: bool) -> None:
        await self.send(CMD_LOW_LATENCY, bytes([STATE_ON if on else STATE_OFF]))

    async def set_ldac(self, on: bool) -> None:
        await self.send(CMD_LDAC, bytes([STATE_ON if on else STATE_OFF]))

    async def set_in_ear(self, on: bool) -> None:
        await self.send(CMD_IN_EAR, bytes([STATE_ON if on else STATE_OFF]))

    async def set_balance(self, value: int) -> None:
        await self.send(CMD_BALANCE, bytes([max(0, min(100, value))]))

    async def set_auto_off_minutes(self, minutes: int) -> None:
        await self.send(CMD_POWER_OFF_TIMER, minutes.to_bytes(2, "little") + b"\x00\x00")

    async def rename(self, name: str) -> None:
        raw = name.encode("utf-8")[:32]
        await self.send(CMD_RENAME, raw)

    async def music(self, action: int) -> None:
        await self.send(CMD_MUSIC_CONTROL, bytes([action]))

    async def reset_default(self) -> None:
        await self.send(CMD_RESET_DEFAULT)

    # ------------------------------------------------------------- eq

    async def read_eq(self) -> dict | None:
        """Read parametric EQ (v2 format preferred, falls back to v1)."""
        for cmd, stride in ((CMD_EQ_V2, 7), (CMD_EQ_V1, 6)):
            r = await self.request(cmd)
            if not r or len(r.params) < 3:
                continue
            eq_type = r.params[0]
            master = int.from_bytes(r.params[1:3], "little", signed=True)
            bands = []
            chunk = r.params[3:]
            for i in range(0, len(chunk) - stride + 1, stride):
                b = chunk[i : i + stride]
                band = {
                    "freq": int.from_bytes(b[0:2], "little"),
                    "gain": int.from_bytes(b[2:4], "little", signed=True) / 100,
                    "q": int.from_bytes(b[4:6], "little") / 100,
                }
                if stride == 7:
                    band["type"] = b[6]
                bands.append(band)
            return {"type": eq_type, "master_gain": master / 100, "bands": bands}
        return None

    async def write_eq(self, bands: list[dict], eq_index: int = 0, master_gain: float = 0.0,
                       band_type: int = 0) -> None:
        """Write a parametric EQ profile (v2, 7 bytes per band)."""
        payload = bytearray([eq_index])
        payload += int(round(master_gain * 100)).to_bytes(2, "little", signed=True)
        for band in bands:
            gain = max(-1270, min(1270, int(round(band["gain"] * 100))))
            payload += int(band["freq"]).to_bytes(2, "little")
            payload += gain.to_bytes(2, "little", signed=True)
            payload += int(round(band.get("q", 1.0) * 100)).to_bytes(2, "little")
            payload += bytes([band.get("type", band_type)])
        await self.send(CMD_EQ_V2, bytes(payload))

    # ------------------------------------------------------------- gatt dump

    async def dump_gatt(self) -> None:
        if not self._client:
            raise RuntimeError("not connected")
        print(f"device: {self.name}  ({self.address})")
        for svc in self._client.services:
            print(f"\nservice {svc.uuid}  {svc.description}")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                print(f"  char {ch.uuid}  [{props}]  {ch.description}")
