"""알림: 터미널 벨 + (선택) 텔레그램. TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID 환경변수."""
from __future__ import annotations

import json
import os
import urllib.request


def notify(message: str) -> None:
    print(f"\a[알림] {message}", flush=True)
    token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT_ID")
    if not (token and chat):
        return
    try:
        req = urllib.request.Request(
            f"https://api.telegram.org/bot{token}/sendMessage",
            data=json.dumps({"chat_id": chat, "text": message}).encode(),
            headers={"Content-Type": "application/json"},
        )
        urllib.request.urlopen(req, timeout=5).read()
    except Exception as e:  # 알림 실패가 본 작업을 막으면 안 됨
        print(f"[알림] 텔레그램 전송 실패: {e}", flush=True)
