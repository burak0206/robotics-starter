#!/usr/bin/env python3
"""Exercise the running simulation through REST, optionally via the web proxy."""

import argparse
import json
import time
import urllib.error
import urllib.request


def request(base, path, method="GET"):
    req = urllib.request.Request(base.rstrip("/") + path, method=method)
    with urllib.request.urlopen(req, timeout=8) as response:
        assert response.status == 200, response.status
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()

    for attempt in range(30):
        try:
            status = request(args.base_url, "/api/robot/status")
            break
        except (urllib.error.URLError, TimeoutError):
            if attempt == 29:
                raise
            time.sleep(1)

    assert status["mode"] == "simulation", "Smoke test sadece simülasyonda çalışır."
    assert status["robotId"] == "robot-01", status
    assert status["batteryPercent"] == 84, status
    assert status["linearVelocityMps"] == 0, status
    assert status["state"] in ("IDLE", "STOPPED"), status
    stopped = request(args.base_url, "/api/robot/stop", "POST")
    assert stopped["accepted"] is True, stopped
    assert stopped["status"]["state"] == "STOPPED", stopped
    again = request(args.base_url, "/api/robot/stop", "POST")
    assert again["accepted"] is True, again
    final_status = request(args.base_url, "/api/robot/status")
    assert final_status["state"] == "STOPPED", final_status
    assert final_status["observedAtUnixMs"] >= status["observedAtUnixMs"], final_status
    print("OK: durum → durdur → tekrar durdur → durum (REST → gRPC → Rust)")


if __name__ == "__main__":
    main()

