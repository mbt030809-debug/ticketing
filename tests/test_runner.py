import shutil
import tempfile
import unittest
from datetime import datetime

from ticketing.clock import Clock
from ticketing.config import ConfigError, parse_events
from ticketing.device import FakeDevice, parse_hierarchy
from ticketing.runner import Runner


class FakeTime:
    """sleep 하면 시간이 흐르는 가짜 시계."""

    def __init__(self, start=1_000_000.0):
        self.t = start

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s


def make_event(**over):
    raw = {
        "package": "com.example.app",
        "open_at": "2026-11-05 10:00:00",
        "target": {"textContains": "쿠폰 받기"},
        "success_texts": ["발급되었습니다"],
        "soldout_texts": ["소진"],
        "already_texts": ["이미 받은"],
        "interval_ms": 100,
        "max_attempts": 10,
        "result_wait_s": 1.0,
    }
    raw.update(over)
    return parse_events({"events": {"e": raw}})["e"]


def node(text="", **kw):
    return {"text": text, "description": kw.get("description", ""), "resourceId": kw.get("resourceId", ""), "className": ""}


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.ft = FakeTime()
        self.clock = Clock(0.0, now=self.ft.now, sleep=self.ft.sleep)
        self.logs = []
        self.stamp = lambda: datetime(2026, 11, 5, 10, 0, 0)

    def runner(self, dev):
        shot_dir = tempfile.mkdtemp(prefix="tk_shots_")
        self.addCleanup(shutil.rmtree, shot_dir, True)
        return Runner(dev, self.clock, log=self.logs.append, shot_dir=shot_dir, stamp=self.stamp)

    def test_success_first_try(self):
        clicked = []
        dev = FakeDevice(lambda: [node("쿠폰 받기")] + ([node("쿠폰이 발급되었습니다")] if clicked else []))
        orig = dev.click
        dev.click = lambda sel: (clicked.append(1) or orig(sel))
        ev = make_event()
        res = self.runner(dev).run(ev, immediate=True)
        self.assertEqual(res.status, "success")
        self.assertEqual(res.attempts, 1)
        self.assertEqual(dev.started, ["com.example.app"])
        self.assertTrue(dev.shots[0].endswith("e_success_20261105_100000.png"))

    def test_soldout(self):
        dev = FakeDevice(lambda: [node("쿠폰 받기"), node("선착순 마감, 모두 소진")])
        res = self.runner(dev).run(make_event(), immediate=True)
        self.assertEqual(res.status, "soldout")

    def test_retry_until_target_appears(self):
        n = {"calls": 0}

        def screen():
            n["calls"] += 1
            return [node("쿠폰 받기")] if n["calls"] > 6 else [node("오픈 예정")]

        dev = FakeDevice(screen)
        res = self.runner(dev).run(make_event(success_texts=[], soldout_texts=[], already_texts=[]), immediate=True)
        self.assertEqual(res.status, "clicked")
        self.assertGreater(res.attempts, 1)

    def test_timeout_when_never_found(self):
        dev = FakeDevice(lambda: [node("오픈 예정")])
        res = self.runner(dev).run(make_event(max_attempts=3, refresh_on_miss=True), immediate=True)
        self.assertEqual(res.status, "timeout")
        self.assertEqual(res.attempts, 3)

    def test_dry_run_never_clicks(self):
        dev = FakeDevice(lambda: [node("쿠폰 받기")])
        res = self.runner(dev).run(make_event(), dry_run=True)
        self.assertEqual(res.status, "dry_run")
        self.assertEqual(dev.clicks, [])

    def test_waits_until_open_time(self):
        ev = make_event(lead_ms=100)
        self.ft.t = ev.open_ts - 5.0
        seen = []
        dev = FakeDevice(lambda: [node("쿠폰 받기")])
        orig = dev.click
        dev.click = lambda sel: (seen.append(self.ft.t) or orig(sel))
        self.runner(dev).run(make_event(lead_ms=100, success_texts=[], soldout_texts=[], already_texts=[]))
        self.assertGreaterEqual(seen[0], ev.open_ts - 0.1 - 1e-6)
        self.assertLess(seen[0], ev.open_ts)

    def test_pre_step_failure_returns_error(self):
        dev = FakeDevice(lambda: [node("홈")])
        ev = make_event(pre_steps=[{"click": {"text": "없는버튼"}, "timeout": 1}])
        res = self.runner(dev).run(ev, immediate=True)
        self.assertEqual(res.status, "error")

    def test_optional_pre_step_skipped(self):
        dev = FakeDevice(lambda: [node("쿠폰 받기")])
        ev = make_event(pre_steps=[{"click": {"text": "없는버튼"}, "optional": True}], success_texts=[], soldout_texts=[], already_texts=[])
        res = self.runner(dev).run(ev, immediate=True)
        self.assertEqual(res.status, "clicked")

    def test_popup_dismissed_on_miss(self):
        state = {"popup": True}

        def screen():
            return [node("닫기")] if state["popup"] else [node("쿠폰 받기")]

        dev = FakeDevice(screen)
        orig = dev.click

        def click(sel):
            ok = orig(sel)
            if ok and sel.get("text") == "닫기":
                state["popup"] = False
            return ok

        dev.click = click
        ev = make_event(popups=[{"text": "닫기"}], success_texts=[], soldout_texts=[], already_texts=[])
        res = self.runner(dev).run(ev, immediate=True)
        self.assertEqual(res.status, "clicked")


class ConfigTest(unittest.TestCase):
    def test_open_at_has_kst(self):
        ev = make_event()
        self.assertEqual(ev.open_at.utcoffset().total_seconds(), 9 * 3600)

    def test_bad_selector_key(self):
        with self.assertRaises(ConfigError):
            make_event(target={"bogus": "x"})

    def test_empty_package_rejected(self):
        with self.assertRaises(ConfigError):
            make_event(package="").validate()

    def test_sample_config_loads(self):
        import os
        from ticketing.config import load_events

        path = os.path.join(os.path.dirname(__file__), "..", "config", "events_20261102.yaml")
        evs = load_events(path)
        self.assertEqual(set(evs), {"coupang_wowday", "naver_plus_nepdasale"})
        evs["coupang_wowday"].validate()


class ClockTest(unittest.TestCase):
    def test_wait_until_does_not_return_early(self):
        ft = FakeTime()
        c = Clock(0.0, now=ft.now, sleep=ft.sleep)
        c.wait_until(ft.t + 3.0)
        self.assertGreaterEqual(ft.t, 1_000_003.0)
        self.assertLess(ft.t, 1_000_003.01)

    def test_offset_applied(self):
        ft = FakeTime()
        c = Clock(2.0, now=ft.now, sleep=ft.sleep)
        self.assertEqual(c.now(), ft.t + 2.0)


class HierarchyTest(unittest.TestCase):
    def test_parse(self):
        xml = (
            '<hierarchy><node text="쿠폰 받기" resource-id="a:id/btn" content-desc="" class="android.widget.Button" '
            'clickable="true" bounds="[0,0][10,10]"/><node text="" resource-id="" content-desc="" class="x" clickable="false" bounds=""/></hierarchy>'
        )
        nodes = parse_hierarchy(xml)
        self.assertEqual(len(nodes), 1)
        self.assertEqual(nodes[0]["resourceId"], "a:id/btn")
        self.assertTrue(nodes[0]["clickable"])


if __name__ == "__main__":
    unittest.main()
