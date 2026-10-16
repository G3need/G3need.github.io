import json, urllib.request, time, websocket, os

CDP = "http://127.0.0.1:9222"
OUT = os.path.dirname(os.path.abspath(__file__))

urls = {
    "canada_rounds": "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/rounds-invitations.html",
    "jobbank_sw": "https://www.jobbank.gc.ca/jobsearch?text=software%20engineer",
}

for name, url in urls.items():
    req = urllib.request.Request(f"{CDP}/json/new?{url}", method="PUT")
    with urllib.request.urlopen(req, timeout=15) as r:
        tab = json.loads(r.read())
    time.sleep(6)
    targets = json.loads(urllib.request.urlopen(f"{CDP}/json", timeout=10).read().decode())
    for t in targets:
        if t.get("id") == tab["id"]:
            ws = websocket.create_connection(t["webSocketDebuggerUrl"], timeout=15)
            ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": "document.body.innerText", "returnByValue": True}}))
            m = json.loads(ws.recv())
            txt = m.get("result", {}).get("result", {}).get("value", "")
            ws.close()
            out = os.path.join(OUT, f"pilot_docs", f"{name}_raw.txt")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "w", encoding="utf-8") as f:
                f.write(txt)
            print(f"{name}: {len(txt)} chars -> {out}")
            break
    urllib.request.urlopen(f"{CDP}/json/close/{tab['id']}", timeout=5)
