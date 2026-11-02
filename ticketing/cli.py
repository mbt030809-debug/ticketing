from __future__ import annotations

import argparse
import subprocess
import sys

from .clock import Clock, ntp_offset
from .config import ConfigError, load_events
from .device import U2Device, parse_hierarchy
from .notify import notify
from .runner import Runner


def cmd_ntp(args) -> int:
    off = ntp_offset(args.server)
    if off is None:
        print("NTP 응답 없음 (UDP 123 차단/네트워크 확인)")
        return 1
    print(f"로컬 시계 오차: {off * 1000:+.0f}ms (양수 = 로컬이 느림)")
    return 0


def cmd_packages(args) -> int:
    cmd = ["adb"] + (["-s", args.serial] if args.serial else []) + ["shell", "pm", "list", "packages"]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        print(f"adb 실행 실패: {e}")
        return 1
    for line in sorted(out.splitlines()):
        pkg = line.removeprefix("package:").strip()
        if not args.keyword or args.keyword.lower() in pkg.lower():
            print(pkg)
    return 0


def cmd_inspect(args) -> int:
    nodes = parse_hierarchy(U2Device(args.serial).dump())
    for n in nodes:
        if args.clickable and not n["clickable"]:
            continue
        print(f"text={n['text']!r:30} desc={n['description']!r:24} id={n['resourceId']!r:40} click={n['clickable']}")
    return 0


def cmd_run(args) -> int:
    try:
        events = load_events(args.config)
        if args.event not in events:
            print(f"이벤트 '{args.event}' 없음. 후보: {', '.join(events) or '(없음)'}")
            return 2
        ev = events[args.event]
        ev.validate()
    except (ConfigError, OSError) as e:
        print(f"설정 오류: {e}")
        return 2

    offset = 0.0
    if not (args.no_ntp or args.now or args.dry_run):
        off = ntp_offset()
        if off is None:
            print("NTP 실패 → 오프셋 0 으로 진행 (OS 시간 동기화 확인 권장)")
        else:
            offset = off
            print(f"NTP 오프셋: {off * 1000:+.0f}ms")

    runner = Runner(U2Device(args.serial), Clock(offset), shot_dir=args.shot_dir)
    result = runner.run(ev, dry_run=args.dry_run, immediate=args.now)
    notify(f"[{ev.name}] {result.status} ({result.attempts}회) {result.detail}")
    return 0 if result.status in ("success", "already", "clicked", "dry_run") else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="ticketing", description="안드로이드 앱 쿠폰 선착순 수령 매크로")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("ntp", help="로컬 시계 오차 확인")
    s.add_argument("--server", default="time.google.com")
    s.set_defaults(fn=cmd_ntp)

    s = sub.add_parser("packages", help="기기 설치 앱 패키지명 조회 (예: packages naver)")
    s.add_argument("keyword", nargs="?")
    s.add_argument("--serial")
    s.set_defaults(fn=cmd_packages)

    s = sub.add_parser("inspect", help="현재 화면 요소 출력 (selector 작성용)")
    s.add_argument("--serial")
    s.add_argument("--clickable", action="store_true")
    s.set_defaults(fn=cmd_inspect)

    s = sub.add_parser("run", help="이벤트 실행")
    s.add_argument("config")
    s.add_argument("--event", required=True)
    s.add_argument("--serial")
    s.add_argument("--dry-run", action="store_true", help="타겟 탐색만, 클릭/대기 없음")
    s.add_argument("--now", action="store_true", help="오픈 시각 대기 없이 즉시 시작 (리허설)")
    s.add_argument("--no-ntp", action="store_true")
    s.add_argument("--shot-dir", default="logs")
    s.set_defaults(fn=cmd_run)

    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
