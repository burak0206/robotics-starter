#!/usr/bin/env python3
"""Exercise alarm cancellation and explicit recovery across both building cases."""

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
        return request(args.base_url, "/api/" + path, method, body, expected)

    def post(path, body=None, expected=200):
        return api(path, "POST", body, expected)

    def advance(case, state):
        return post(case + "/simulation/advance", {"expectedState": state})

    initial = api("fire-alarm/status")
    assert initial["mode"] == "simulation", "Test yalnızca simülasyonda çalışır."
    assert not initial["active"] and not initial["recoveryRequired"], "Önce alarmı temizleyip iptal edilen simülasyonları sıfırlayın."
    for case in ("door", "elevator"):
        status = api(case + "/status")
        assert status["mode"] == "simulation" and status["state"] in ("IDLE", "COMPLETED"), status

    post("door/request-passage")
    advance("door", "OPENING")
    door_before = post("door/start-passage")
    post("elevator/call", {"pickupFloor": 0})
    advance("elevator", "CALLING")
    advance("elevator", "WAITING_FOR_BOARDING")
    elevator_before = post("elevator/select-floor", {"destinationFloor": 2})

    alarm = post("fire-alarm/simulation/activate")
    assert alarm["active"] and alarm["recoveryRequired"] and alarm["doorCancelled"] and alarm["elevatorCancelled"], alarm
    repeated = post("fire-alarm/simulation/activate")
    assert repeated["active"] and repeated["doorCancelled"] and repeated["elevatorCancelled"], repeated

    def check_cancelled(active):
        door = api("door/status")
        elevator = api("elevator/status")
        for status in (door, elevator):
            assert status["state"] == "CANCELLED" and status["recoveryRequired"], status
            assert status["fireAlarmActive"] is active, status
        for field in ("doorOpen", "robotInDoorway"):
            assert door[field] == door_before[field], (field, door)
        assert not door["passageAllowed"], door
        for field in ("currentFloor", "doorsOpen", "robotInCabin", "pickupFloor", "destinationFloor"):
            assert elevator[field] == elevator_before[field], (field, elevator)

    check_cancelled(True)
    for path, body in (
        ("door/request-passage", None),
        ("door/start-passage", None),
        ("door/simulation/advance", {"expectedState": "PASSING"}),
        ("elevator/call", {"pickupFloor": 1}),
        ("elevator/select-floor", {"destinationFloor": 3}),
        ("elevator/simulation/advance", {"expectedState": "MOVING"}),
    ):
        post(path, body, 409)
    post("fire-alarm/simulation/reset-cancelled", expected=409)
    check_cancelled(True)

    cleared = post("fire-alarm/simulation/clear")
    assert not cleared["active"] and cleared["recoveryRequired"], cleared
    post("fire-alarm/simulation/clear")
    check_cancelled(False)
    post("door/request-passage", expected=409)
    post("elevator/call", {"pickupFloor": 0}, 409)

    reset = post("fire-alarm/simulation/reset-cancelled")
    assert not any(reset[field] for field in ("active", "recoveryRequired", "doorCancelled", "elevatorCancelled")), reset
    door = api("door/status")
    elevator = api("elevator/status")
    assert door["state"] == "IDLE" and not door["doorOpen"] and not door["robotInDoorway"], door
    assert elevator["state"] == "IDLE" and not elevator["doorsOpen"] and not elevator["robotInCabin"], elevator
    assert elevator["currentFloor"] == elevator_before["currentFloor"], elevator

    # Recovery must not erase a new journey in an unaffected elevator.
    post("door/request-passage")
    alarm = post("fire-alarm/simulation/activate")
    assert alarm["doorCancelled"] and not alarm["elevatorCancelled"], alarm
    post("fire-alarm/simulation/clear")
    post("elevator/call", {"pickupFloor": 1})
    post("fire-alarm/simulation/reset-cancelled")
    assert api("door/status")["state"] == "IDLE"
    assert api("elevator/status")["state"] == "CALLING"
    advance("elevator", "CALLING")
    advance("elevator", "WAITING_FOR_BOARDING")
    post("elevator/select-floor", {"destinationFloor": 3})
    advance("elevator", "MOVING")
    advance("elevator", "WAITING_FOR_EXIT")
    print("OK: alarm → iki akış iptali/komut kilidi → konum korunması → temizle/otomatik devam yok → yalnızca iptal edilenleri sıfırla")


if __name__ == "__main__":
    main()
