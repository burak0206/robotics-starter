#!/usr/bin/env python3
"""Exercise one door passage through REST -> Kotlin -> gRPC -> Rust simulation."""

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
        return request(args.base_url, "/api/door/" + path, method, body, expected)

    initial = api("status")
    assert initial["mode"] == "simulation", "Smoke test yalnızca simülasyonda çalışır."
    assert initial["state"] in ("IDLE", "COMPLETED"), "Devam eden geçişi önce arayüzde tamamlayın."
    assert not initial["doorOpen"] and not initial["robotInDoorway"], initial
    api("start-passage", "POST", expected=409)
    api("simulation/advance", "POST", {}, 400)
    api("simulation/advance", "POST", {"expectedState": "INVALID"}, 400)
    opening = api("request-passage", "POST")
    assert opening["state"] == "OPENING", opening
    assert not opening["doorOpen"] and not opening["passageAllowed"], opening
    api("request-passage", "POST", expected=409)
    api("start-passage", "POST", expected=409)
    api("simulation/advance", "POST", {"expectedState": "PASSING"}, 409)
    opened = api("simulation/advance", "POST", {"expectedState": "OPENING"})
    assert opened["state"] == "READY_TO_PASS" and opened["doorOpen"] and opened["passageAllowed"], opened
    assert not opened["robotInDoorway"], opened
    api("simulation/advance", "POST", {"expectedState": "OPENING"}, 409)
    api("simulation/advance", "POST", {"expectedState": "READY_TO_PASS"}, 409)
    passing = api("start-passage", "POST")
    assert passing["state"] == "PASSING" and passing["robotInDoorway"] and passing["doorOpen"], passing
    api("request-passage", "POST", expected=409)
    api("start-passage", "POST", expected=409)
    cleared = api("simulation/advance", "POST", {"expectedState": "PASSING"})
    assert cleared["state"] == "CLOSING" and cleared["doorOpen"], cleared
    assert not cleared["robotInDoorway"] and not cleared["passageAllowed"], cleared
    api("simulation/advance", "POST", {"expectedState": "PASSING"}, 409)
    completed = api("simulation/advance", "POST", {"expectedState": "CLOSING"})
    assert completed["state"] == "COMPLETED", completed
    assert not completed["doorOpen"] and not completed["robotInDoorway"] and not completed["passageAllowed"], completed
    api("simulation/advance", "POST", {"expectedState": "COMPLETED"}, 409)
    print("OK: geçiş isteği → kapı açılışı → geçiş → eşik boş → kapı kapanışı; erken/tekrarlanan işlemler reddedildi")


if __name__ == "__main__":
    main()
