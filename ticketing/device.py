"""안드로이드 기기 제어 추상화. 실기기는 uiautomator2, 테스트는 FakeDevice."""
from __future__ import annotations

import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional, Protocol

from .config import Selector


class Device(Protocol):
    def start_app(self, package: str) -> None: ...
    def exists(self, sel: Selector) -> bool: ...
    def click(self, sel: Selector) -> bool: ...          # 있으면 클릭하고 True
    def wait(self, sel: Selector, timeout: float) -> bool: ...
    def pull_refresh(self) -> None: ...
    def screenshot(self, path: str) -> None: ...
    def dump(self) -> str: ...                            # UI 계층 XML


class U2Device:
    """uiautomator2 기반. 폰에서 USB 디버깅(또는 무선 디버깅) 허용 필요."""

    def __init__(self, serial: Optional[str] = None):
        try:
            import uiautomator2 as u2
        except ImportError as e:  # pragma: no cover
            raise SystemExit("uiautomator2 미설치: pip install -r requirements.txt") from e
        self.d = u2.connect(serial)

    def start_app(self, package: str) -> None:
        self.d.app_start(package, stop=True)

    def exists(self, sel: Selector) -> bool:
        return bool(self.d(**sel).exists)

    def click(self, sel: Selector) -> bool:
        return bool(self.d(**sel).click_exists(timeout=0))

    def wait(self, sel: Selector, timeout: float) -> bool:
        return bool(self.d(**sel).wait(timeout=timeout))

    def pull_refresh(self) -> None:
        self.d.swipe(0.5, 0.3, 0.5, 0.8, 0.1)

    def screenshot(self, path: str) -> None:
        self.d.screenshot(path)

    def dump(self) -> str:
        return self.d.dump_hierarchy()


def parse_hierarchy(xml: str) -> List[Dict[str, Any]]:
    """dump_hierarchy XML 에서 text/desc/id 가 있는 노드만 추출 (selector 작성용)."""
    out: List[Dict[str, Any]] = []
    for node in ET.fromstring(xml).iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        rid = node.get("resource-id") or ""
        if not (text or desc or rid):
            continue
        out.append({
            "text": text,
            "description": desc,
            "resourceId": rid,
            "className": node.get("class") or "",
            "clickable": node.get("clickable") == "true",
            "bounds": node.get("bounds") or "",
        })
    return out


class FakeDevice:
    """테스트용. screen 은 호출 시점의 화면(selector 매칭 대상)을 반환하는 callable."""

    def __init__(self, screen):
        self.screen = screen                 # () -> List[Dict[str,str]]  각 노드: text/description/resourceId/className
        self.clicks: List[Selector] = []
        self.started: List[str] = []
        self.refreshes = 0
        self.shots: List[str] = []

    @staticmethod
    def _match(node: Dict[str, str], sel: Selector) -> bool:
        for k, v in sel.items():
            if k == "text" and node.get("text") != v:
                return False
            if k == "textContains" and v not in node.get("text", ""):
                return False
            if k == "description" and node.get("description") != v:
                return False
            if k == "descriptionContains" and v not in node.get("description", ""):
                return False
            if k == "resourceId" and node.get("resourceId") != v:
                return False
            if k == "className" and node.get("className") != v:
                return False
        return True

    def start_app(self, package: str) -> None:
        self.started.append(package)

    def exists(self, sel: Selector) -> bool:
        return any(self._match(n, sel) for n in self.screen())

    def click(self, sel: Selector) -> bool:
        if self.exists(sel):
            self.clicks.append(sel)
            return True
        return False

    def wait(self, sel: Selector, timeout: float) -> bool:
        return self.exists(sel)

    def pull_refresh(self) -> None:
        self.refreshes += 1

    def screenshot(self, path: str) -> None:
        self.shots.append(path)

    def dump(self) -> str:
        return "<hierarchy/>"
