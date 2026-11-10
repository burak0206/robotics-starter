#!/usr/bin/env python3
"""Run the elevator scenario through REST -> Kotlin -> gRPC -> Rust simulation."""

import argparse
import json
import urllib.error
import urllib.request


def request(base, path, method="GET", body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    headers = {} if body is None else {"Content-Type": "application/json"}
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (response.status, expected, result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()

    def api(path, method="GET", body=None, expected=200):
        return request(args.base_url, "/api/elevator/" + path, method, body, expected)

    initial = api("status")
    assert initial["mode"] == "simulation", "Smoke test yalnızca simülasyonda çalışır."
    assert initial["state"] in ("IDLE", "COMPLETED"), "Devam eden case'i önce arayüzde tamamlayın."
    api("call", "POST", {}, 400)
    api("call", "POST", {"pickupFloor": 6}, 400)
    api("select-floor", "POST", {"destinationFloor": 2}, 409)
    api("simulation/advance", "POST", {}, 400)
    called = api("call", "POST", {"pickupFloor": 0})
    assert called["state"] == "CALLING", called
    api("call", "POST", {"pickupFloor": 1}, 409)
    arrived = api("simulation/advance", "POST", {"expectedState": "CALLING"})
    assert arrived["state"] == "WAITING_FOR_BOARDING" and arrived["doorsOpen"], arrived
    assert arrived["currentFloor"] == 0 and not arrived["robotInCabin"], arrived
    api("simulation/advance", "POST", {"expectedState": "CALLING"}, 409)
    api("select-floor", "POST", {"destinationFloor": 2}, 409)
    boarded = api("simulation/advance", "POST", {"expectedState": "WAITING_FOR_BOARDING"})
    assert boarded["state"] == "READY_FOR_DESTINATION" and boarded["robotInCabin"], boarded
    api("select-floor", "POST", {"destinationFloor": 0}, 400)
    moving = api("select-floor", "POST", {"destinationFloor": 2})
    assert moving["state"] == "MOVING" and not moving["doorsOpen"] and moving["robotInCabin"], moving
    destination = api("simulation/advance", "POST", {"expectedState": "MOVING"})
    assert destination["state"] == "WAITING_FOR_EXIT" and destination["currentFloor"] == 2, destination
    assert destination["doorsOpen"] and destination["robotInCabin"], destination
    completed = api("simulation/advance", "POST", {"expectedState": "WAITING_FOR_EXIT"})
    assert completed["state"] == "COMPLETED", completed
    assert not completed["robotInCabin"] and not completed["doorsOpen"], completed
    api("simulation/advance", "POST", {"expectedState": "COMPLETED"}, 409)
    print("OK: çağrı → kapı → kabine giriş → kat seçimi → varış → çıkış; geçersiz sıra reddedildi")


if __name__ == "__main__":
    main()
