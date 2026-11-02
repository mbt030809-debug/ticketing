"""시간 보정 + 정밀 대기."""
from __future__ import annotations

import socket
import statistics
import struct
import time
from typing import Callable, Optional

_NTP_EPOCH_DELTA = 2208988800  # 1900-01-01 -> 1970-01-01


def ntp_offset(server: str = "time.google.com", samples: int = 3, timeout: float = 2.0) -> Optional[float]:
    """NTP 서버 시각 - 로컬 시각 (초). 실패 시 None."""
    offsets = []
    for _ in range(samples):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.settimeout(timeout)
                packet = b"\x1b" + 47 * b"\0"
                t0 = time.time()
                s.sendto(packet, (server, 123))
                data, _ = s.recvfrom(48)
                t3 = time.time()
            if len(data) < 48:
                continue
            f = struct.unpack("!12I", data)
            t1 = f[8] - _NTP_EPOCH_DELTA + f[9] / 2**32  # server receive
            t2 = f[10] - _NTP_EPOCH_DELTA + f[11] / 2**32  # server transmit
            offsets.append(((t1 - t0) + (t2 - t3)) / 2)
        except OSError:
            continue
    return statistics.median(offsets) if offsets else None


class Clock:
    """offset 이 보정된 현재 시각 + 목표 시각까지 대기."""

    def __init__(
        self,
        offset: float = 0.0,
        now: Callable[[], float] = time.time,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.offset = offset
        self._now = now
        self._sleep = sleep

    def now(self) -> float:
        return self._now() + self.offset

    def sleep(self, seconds: float) -> None:
        self._sleep(seconds)

    def wait_until(self, target_ts: float, spin_window: float = 0.05, on_tick: Optional[Callable[[float], None]] = None) -> None:
        """target_ts(보정 시각 기준 epoch 초) 가 될 때까지 대기. 마지막 spin_window 초는 짧게 폴링."""
        last_report = None
        while True:
            remaining = target_ts - self.now()
            if remaining <= 0:
                return
            if on_tick and (last_report is None or int(remaining) != last_report):
                last_report = int(remaining)
                on_tick(remaining)
            # 멀리 있을 땐 크게 쉬고(최대 0.5s), 마지막 spin_window 구간은 1ms 단위로 폴링
            self._sleep(max(0.001, min(remaining - spin_window, 0.5)))
