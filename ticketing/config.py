"""이벤트 설정 로드/검증."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from zoneinfo import ZoneInfo

import yaml

# uiautomator2 selector 키 중 허용하는 것
SELECTOR_KEYS = {
    "text", "textContains", "textMatches",
    "description", "descriptionContains",
    "resourceId", "className",
}

Selector = Dict[str, str]


class ConfigError(ValueError):
    pass


def _selector(raw: Any, where: str) -> Selector:
    if not isinstance(raw, dict) or not raw:
        raise ConfigError(f"{where}: selector 는 비어있지 않은 dict 여야 함 (예: {{textContains: '쿠폰 받기'}})")
    bad = set(raw) - SELECTOR_KEYS
    if bad:
        raise ConfigError(f"{where}: 지원하지 않는 selector 키 {sorted(bad)} (허용: {sorted(SELECTOR_KEYS)})")
    return {k: str(v) for k, v in raw.items()}


def _selectors(raw: Any, where: str) -> List[Selector]:
    return [_selector(x, f"{where}[{i}]") for i, x in enumerate(raw or [])]


def _texts(raw: Any) -> List[str]:
    return [str(x) for x in (raw or [])]


@dataclass
class Step:
    """pre_steps 한 단계. click / wait 중 하나 + optional sleep."""
    click: Optional[Selector] = None
    wait: Optional[Selector] = None
    optional: bool = False       # True 면 못 찾아도 계속 진행
    timeout: float = 10.0
    sleep: float = 0.0


@dataclass
class Event:
    name: str
    package: str
    target: Selector
    open_at: datetime
    tz: str = "Asia/Seoul"
    lead_ms: int = 0                    # 클릭 지연(ADB/HTTP 왕복) 보정: open_at 보다 이만큼 먼저 시작
    interval_ms: int = 150              # 재시도 간격
    max_attempts: int = 60
    result_wait_s: float = 3.0
    pre_steps: List[Step] = field(default_factory=list)
    popups: List[Selector] = field(default_factory=list)       # 광고/업데이트 팝업 닫기
    post_click: List[Selector] = field(default_factory=list)   # 클릭 후 확인 팝업 등
    success_texts: List[str] = field(default_factory=list)
    soldout_texts: List[str] = field(default_factory=list)
    already_texts: List[str] = field(default_factory=list)
    refresh_on_miss: bool = False       # 타겟 못 찾으면 pull-to-refresh

    @property
    def open_ts(self) -> float:
        return self.open_at.timestamp()

    def validate(self) -> None:
        if not self.package:
            raise ConfigError(f"{self.name}: package 가 비어있음. `python -m ticketing packages <키워드>` 로 확인해서 채우세요")
        if not self.target:
            raise ConfigError(f"{self.name}: target 이 비어있음")
        if self.max_attempts < 1:
            raise ConfigError(f"{self.name}: max_attempts 는 1 이상")


def _step(raw: Any, where: str) -> Step:
    if not isinstance(raw, dict):
        raise ConfigError(f"{where}: step 은 dict 여야 함")
    st = Step(
        click=_selector(raw["click"], f"{where}.click") if "click" in raw else None,
        wait=_selector(raw["wait"], f"{where}.wait") if "wait" in raw else None,
        optional=bool(raw.get("optional", False)),
        timeout=float(raw.get("timeout", 10.0)),
        sleep=float(raw.get("sleep", 0.0)),
    )
    if st.click is None and st.wait is None and st.sleep <= 0:
        raise ConfigError(f"{where}: click / wait / sleep 중 하나는 필요")
    return st


def _parse_open_at(raw: Any, tz: str, where: str) -> datetime:
    if isinstance(raw, datetime):
        dt = raw
    else:
        try:
            dt = datetime.fromisoformat(str(raw))
        except ValueError as e:
            raise ConfigError(f"{where}: open_at 형식 오류 ({raw!r}) — 예: '2026-11-05 10:00:00'") from e
    return dt if dt.tzinfo else dt.replace(tzinfo=ZoneInfo(tz))


def parse_events(doc: Dict[str, Any]) -> Dict[str, Event]:
    events: Dict[str, Event] = {}
    for name, raw in (doc.get("events") or {}).items():
        where = f"events.{name}"
        tz = raw.get("tz", "Asia/Seoul")
        events[name] = Event(
            name=name,
            package=str(raw.get("package") or ""),
            target=_selector(raw.get("target"), f"{where}.target") if raw.get("target") else {},
            open_at=_parse_open_at(raw.get("open_at"), tz, where),
            tz=tz,
            lead_ms=int(raw.get("lead_ms", 0)),
            interval_ms=int(raw.get("interval_ms", 150)),
            max_attempts=int(raw.get("max_attempts", 60)),
            result_wait_s=float(raw.get("result_wait_s", 3.0)),
            pre_steps=[_step(s, f"{where}.pre_steps[{i}]") for i, s in enumerate(raw.get("pre_steps") or [])],
            popups=_selectors(raw.get("popups"), f"{where}.popups"),
            post_click=_selectors(raw.get("post_click"), f"{where}.post_click"),
            success_texts=_texts(raw.get("success_texts")),
            soldout_texts=_texts(raw.get("soldout_texts")),
            already_texts=_texts(raw.get("already_texts")),
            refresh_on_miss=bool(raw.get("refresh_on_miss", False)),
        )
    return events


def load_events(path: str) -> Dict[str, Event]:
    with open(path, encoding="utf-8") as f:
        return parse_events(yaml.safe_load(f) or {})
