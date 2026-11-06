"""QCY packet framing: [0xFF, body_len, (cmd, param_len, params...)*]."""

from __future__ import annotations

from dataclasses import dataclass

SOF = 0xFF

CMD_WRITE_UUID = "00001001-0000-1000-8000-00805f9b34fb"
CMD_NOTIFY_UUID = "00001002-0000-1000-8000-00805f9b34fb"
MAIN_SERVICE_UUID = "0000a001-0000-1000-8000-00805f9b34fb"

# direct-write characteristics (no 0xFF framing)
UUID_VERSION = "00000007-0000-1000-8000-00805f9b34fb"
UUID_BATTERY = "00000008-0000-1000-8000-00805f9b34fb"
UUID_EQ = "0000000b-0000-1000-8000-00805f9b34fb"
UUID_KEY_FUNCTION = "0000000d-0000-1000-8000-00805f9b34fb"


@dataclass
class Command:
    cmd: int
    params: bytes = b""

    def encode(self) -> bytes:
        params = bytes(self.params)
        return bytes([self.cmd, len(params)]) + params


def build(*commands: Command) -> bytes:
    """Frame one or more commands into a single BLE write payload."""
    body = b"".join(c.encode() for c in commands)
    if len(body) > 254:
        raise ValueError(f"body too long: {len(body)} bytes")
    return bytes([SOF, len(body)]) + body


def build_one(cmd: int, params: bytes = b"") -> bytes:
    return build(Command(cmd, params))


def parse(payload: bytes) -> list[Command]:
    """Parse a notification payload into commands. Tolerates junk/garbage."""
    out: list[Command] = []
    i = 0
    n = len(payload)
    while i < n:
        # find the next start-of-frame; devices sometimes pad or prefix junk
        if payload[i] != SOF:
            i += 1
            continue
        if i + 1 >= n:
            break
        body_len = payload[i + 1]
        body = payload[i + 2 : i + 2 + body_len]
        j = 0
        while j + 1 < len(body):
            cmd = body[j]
            plen = body[j + 1]
            params = body[j + 2 : j + 2 + plen]
            out.append(Command(cmd, params))
            j += 2 + plen
        i += 2 + body_len
    return out


def hexs(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)
