"""Decoder for QCY BLE advertisement / manufacturer data.

Layout derived from observed QCY HT07 (ArcBuds) advertising traffic and the
public Quicky protocol write-up. Validated live against the real device.
"""

from __future__ import annotations

from dataclasses import dataclass

QCY_COMPANY_ID = 0x521C  # QCY earphones
QCY_WATCH_COMPANY_ID = 0x05D6  # QCY watches, ignored here


def _flag_and_level(byte: int) -> tuple[int, bool]:
    return byte & 0x7F, bool(byte & 0x80)


def _mac(data: bytes, idx: tuple[int, ...], order: tuple[int, ...]) -> str:
    """QCY stores MAC bytes scrambled; `order` indexes into `idx`."""
    try:
        parts = [data[idx[o]] for o in order]
    except IndexError:
        return ""
    return ":".join(f"{b:02X}" for b in parts)


@dataclass
class QcyAdvertisement:
    vendor_id: int
    color_index: int
    left: int
    right: int
    case: int
    left_charging: bool
    right_charging: bool
    case_charging: bool
    control_mac: str
    other_mac: str
    raw: bytes

    @property
    def battery_summary(self) -> str:
        def fmt(level: int, charging: bool) -> str:
            return f"{level}%{'+' if charging else ''}"

        return (
            f"L {fmt(self.left, self.left_charging)}  "
            f"R {fmt(self.right, self.right_charging)}  "
            f"case {fmt(self.case, self.case_charging)}"
        )


def parse_manufacturer_data(data: bytes) -> QcyAdvertisement | None:
    """Parse QCY manufacturer data. Needs >=20 bytes for battery/vendor id."""
    if len(data) < 20:
        return None

    vendor_id = (data[0] << 8) | data[1]
    color_index = (data[3] & 0x18) >> 1
    left, lchg = _flag_and_level(data[5])
    right, rchg = _flag_and_level(data[6])
    case, cchg = _flag_and_level(data[7])

    control_mac = other_mac = ""
    if len(data) >= 24:
        control_mac = _mac(data, (11, 12, 13, 14, 15, 16), (1, 0, 2, 5, 4, 3))
        other_mac = _mac(data, (18, 19, 20, 21, 22, 23), (1, 0, 2, 5, 4, 3))
        if other_mac == "00:00:00:00:00:00":
            other_mac = control_mac

    return QcyAdvertisement(
        vendor_id=vendor_id,
        color_index=color_index,
        left=left,
        right=right,
        case=case,
        left_charging=lchg,
        right_charging=rchg,
        case_charging=cchg,
        control_mac=control_mac,
        other_mac=other_mac,
        raw=data,
    )
