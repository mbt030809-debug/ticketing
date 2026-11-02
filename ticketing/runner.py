"""앱 실행 → 쿠폰 페이지 이동 → 오픈 시각 대기 → 클릭 루프."""
from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime
from typing import Callable, Optional, Tuple

from .clock import Clock
from .config import Event, Selector, Step
from .device import Device

TERMINAL = {"success", "already", "soldout"}


class RunError(RuntimeError):
    pass


@dataclass
class RunResult:
    status: str  # success | already | soldout | clicked | dry_run | timeout | error
    attempts: int = 0
    detail: str = ""


class Runner:
    def __init__(
        self,
        dev: Device,
        clock: Clock,
        log: Callable[[str], None] = print,
        shot_dir: str = "logs",
        stamp: Callable[[], datetime] = datetime.now,
    ):
        self.dev = dev
        self.clock = clock
        self.log = log
        self.shot_dir = shot_dir
        self.stamp = stamp

    # ---- 보조 ----
    def _has_text(self, text: str) -> bool:
        return self.dev.exists({"textContains": text}) or self.dev.exists({"descriptionContains": text})

    def _dismiss(self, ev: Event) -> None:
        for sel in ev.popups:
            if self.dev.click(sel):
                self.log(f"팝업 닫음: {sel}")

    def _do_step(self, st: Step, ev: Event, idx: int) -> None:
        self._dismiss(ev)
        if st.wait is not None and not self.dev.wait(st.wait, st.timeout):
            if not st.optional:
                raise RunError(f"pre_steps[{idx}] wait 실패: {st.wait}")
            self.log(f"pre_steps[{idx}] wait 건너뜀(optional)")
        if st.click is not None:
            ok = self.dev.wait(st.click, st.timeout) and self.dev.click(st.click)
            if not ok and not st.optional:
                raise RunError(f"pre_steps[{idx}] click 실패: {st.click}")
            self.log(f"pre_steps[{idx}] click {'OK' if ok else '건너뜀(optional)'}: {st.click}")
        if st.sleep > 0:
            self.clock.sleep(st.sleep)

    def _detect(self, ev: Event) -> Optional[Tuple[str, str]]:
        groups = (("success", ev.success_texts), ("already", ev.already_texts), ("soldout", ev.soldout_texts))
        deadline = self.clock.now() + ev.result_wait_s
        while True:
            for status, texts in groups:
                for t in texts:
                    if self._has_text(t):
                        return status, t
            if self.clock.now() >= deadline:
                return None
            self.clock.sleep(0.1)

    def _shot(self, ev: Event, status: str) -> None:
        ts = self.stamp().strftime("%Y%m%d_%H%M%S")
        path = os.path.join(self.shot_dir, f"{ev.name}_{status}_{ts}.png")
        try:
            os.makedirs(self.shot_dir, exist_ok=True)
            self.dev.screenshot(path)
            self.log(f"스크린샷: {path}")
        except Exception as e:
            self.log(f"스크린샷 실패: {e}")

    # ---- 본체 ----
    def run(self, ev: Event, dry_run: bool = False, immediate: bool = False) -> RunResult:
        """dry_run: 타겟 탐색만 하고 클릭 안 함(대기도 생략). immediate: 오픈 시각 대기 생략."""
        ev.validate()
        immediate = immediate or dry_run
        try:
            self.log(f"[{ev.name}] 앱 실행: {ev.package}")
            self.dev.start_app(ev.package)
            for i, st in enumerate(ev.pre_steps):
                self._do_step(st, ev, i)

            if not immediate:
                fire_at = ev.open_ts - ev.lead_ms / 1000
                self.log(f"[{ev.name}] 대기: {ev.open_at.isoformat()} (lead {ev.lead_ms}ms, offset {self.clock.offset * 1000:+.0f}ms)")
                self.clock.wait_until(
                    fire_at,
                    on_tick=lambda r: self.log(f"  T-{int(r)}s") if r <= 10 or int(r) % 30 == 0 else None,
                )

            result = self._loop(ev, dry_run)
        except RunError as e:
            result = RunResult("error", 0, str(e))
        self.log(f"[{ev.name}] 결과: {result.status} (시도 {result.attempts}회) {result.detail}")
        self._shot(ev, result.status)
        return result

    def _loop(self, ev: Event, dry_run: bool) -> RunResult:
        has_result_texts = bool(ev.success_texts or ev.already_texts or ev.soldout_texts)
        misses = 0
        for attempt in range(1, ev.max_attempts + 1):
            if dry_run:
                if self.dev.exists(ev.target):
                    return RunResult("dry_run", attempt, f"타겟 발견, 클릭 생략: {ev.target}")
                clicked = False
            else:
                clicked = self.dev.click(ev.target)

            if clicked:
                self.log(f"  #{attempt} 클릭")
                for sel in ev.post_click:
                    if self.dev.wait(sel, 0.8):
                        self.dev.click(sel)
                if not has_result_texts:
                    return RunResult("clicked", attempt, "결과 문구 미설정 — 클릭만 수행")
                found = self._detect(ev)
                if found:
                    status, matched = found
                    return RunResult(status, attempt, matched)
                self.log(f"  #{attempt} 결과 문구 미확인 → 재시도")
                self._dismiss(ev)
            else:
                misses += 1
                if misses % 3 == 1:
                    self._dismiss(ev)
                if ev.refresh_on_miss and misses % 5 == 0:
                    self.dev.pull_refresh()
                    self.log(f"  #{attempt} 타겟 없음 → 새로고침")
            self.clock.sleep(ev.interval_ms / 1000)
        return RunResult("timeout", ev.max_attempts, "max_attempts 소진")
