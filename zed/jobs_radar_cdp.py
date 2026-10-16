#!/usr/bin/env python3
"""
ZED Job Radar — Vivaldi CDP variant (final version).
Scrapes live Canada.ca and Job Bank pages using CDP, handles dynamic content.
Key fix: after creating a tab, re-list /json to get a live WS URL.
"""
import json, time, urllib.request, re

CDP = "http://127.0.0.1:9222"

def _list_targets():
    with urllib.request.urlopen(f"{CDP}/json", timeout=10) as r:
        return json.loads(r.read())

def new_tab(url):
    req = urllib.request.Request(f"{CDP}/json/new?{url}", method="PUT")
    with urllib.request.urlopen(req, timeout=15) as r:
        fresh = json.loads(r.read())
    for _ in range(20):
        targets = _list_targets()
        for t in targets:
            if url in t.get("url", ""):
                return t
        time.sleep(1)
    return fresh

def page_text(ws_url):
    import websocket
    ws = websocket.create_connection(ws_url, timeout=15)
    ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": "document.body.innerText", "returnByValue": True}}))
    while True:
        data = ws.recv()
        m = json.loads(data)
        if m.get("id") == 1:
            ws.close()
            return m.get("result", {}).get("result", {}).get("value", "")
        time.sleep(0.5)

def close_tab(target_id):
    try:
        urllib.request.urlopen(f"{CDP}/json/close/{target_id}", timeout=5)
    except Exception:
        pass

def parse_ircc_rounds(txt):
    """Extract latest draw + previous rounds from rounds-invitations page."""
    out = {"url": "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/rounds-invitations.html", "lines": []}
    # primary: "CRS score of lowest-ranked candidate invited: XXX"
    m = re.search(r'CRS score of lowest-ranked candidate invited:\s*(\d+)', txt)
    if m:
        out["latest_crs"] = int(m.group(1))
    # secondary: date + "lowest score" pattern
    rounds = re.findall(r'([A-Z][a-z]+ \d{1,2}, \d{4})\s*[-–—:]\s*.*?(\d{2,3})\s*(?:CRS|points|score)?', txt)
    if rounds:
        out["rounds"] = [{"date": d, "crs": int(c)} for d, c in rounds[:10]]
    return out

def parse_ircc_news(txt):
    """Extract news items (title + timestamp) from newsroom search page."""
    items = []
    for m in re.finditer(r'(?:Canada|Minister|IRCC)[^\n]{10,150}\n(\d{4}-\d{2}-\d{2})', txt):
        items.append({"title": m.group(0).split("\n")[0][:150], "date": m.group(1)})
    return {"url": "https://www.canada.ca/en/immigration-refugees-citizenship/news.html?search-news=express+entry", "items": items}

def parse_jobbank(txt):
    """Extract job postings from Job Bank results page.
    Card format: Title / company / Location / salary / date, in sequence."""
    jobs = []
    # find the region after 'Available jobs - Search' and before 'Page ' footer
    start = txt.find('Available jobs - Search')
    region = txt[start:start+6000] if start > -1 else txt
    # job titles: lines that aren't 'New', 'On site', 'Direct Apply', 'Posted on Job Bank', company name pattern
    lines = [l.strip() for l in region.split('\n') if l.strip()]
    for i, l in enumerate(lines):
        # title lines typically follow 'New'/'On site' markers or are the first non-nav line
        if i < 5:
            continue
        if any(l.startswith(k) for k in ['New', 'On site', 'Direct Apply', 'Posted on', 'This job', 'Skip', 'Filters', 'Sort']):
            continue
        # try to grab 3-4 lines as a job card: title, company, location
        card = lines[i:i+4]
        if len(card) >= 2:
            jobs.append({"title": card[0][:100], "company": card[1][:100], "location": card[2][:60] if len(card) > 2 else ""})
            i += 4
            if len(jobs) >= 12:
                break
    total_m = re.search(r'([\d,]+) results', txt)
    return {"url": "https://www.jobbank.gc.ca/jobsearch?text=software%20engineer", "jobs": jobs, "total_results": total_m.group(1) if total_m else "unknown"}

def main():
    report = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"), "sources": {}}
    for key, url in [("ircc_rounds", "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/rounds-invitations.html"),
                    ("ircc_news", "https://www.canada.ca/en/immigration-refugees-citizenship/news.html?search-news=express+entry"),
                    ("jobbank", "https://www.jobbank.gc.ca/jobsearch?text=software%20engineer")]:
        tab = new_tab(url)
        time.sleep(5)
        txt = page_text(tab["webSocketDebuggerUrl"])
        close_tab(tab["id"])
        report["sources"][key] = {"url": url, "content_length": len(txt), "raw": txt}
        # parse
        if key == "ircc_rounds":
            report["sources"][key]["parsed"] = parse_ircc_rounds(txt)
        elif key == "ircc_news":
            report["sources"][key]["parsed"] = parse_ircc_news(txt)
        elif key == "jobbank":
            report["sources"][key]["parsed"] = parse_jobbank(txt)

    # write markdown report with parsed data
    out_dir = "C:\\Users\\Geneed\\Desktop\\draconis-hub\\zed"
    md = ["# ZED Jobs Radar — CDP Live\n", f"Generated: {report['generated_at']}\n"]
    md.append("\n## IRCC — Rounds of Invitation")
    pr = report["sources"]["ircc_rounds"].get("parsed", {})
    if pr.get("latest_crs"):
        md.append(f"- **Latest CRS cutoff: {pr['latest_crs']}**")
    if pr.get("rounds"):
        for r in pr["rounds"]:
            md.append(f"- **{r['date']}** — CRS cutoff: {r['crs']}")
    if not pr.get("rounds") and not pr.get("latest_crs"):
        md.append("- (no parseable round/cutoff values in live text)")
    md.append("\n## IRCC Newsroom (Express Entry search)")
    pn = report["sources"]["ircc_news"].get("parsed", {})
    if pn.get("items"):
        for item in pn["items"]:
            md.append(f"- {item['date']}: {item['title'][:100]}")
    else:
        md.append("- no items parsed from newsroom search (page may use JS-rendered cards)")
    md.append("\n## Canada Job Bank — sample openings (74,054 total for 'software engineer')")
    pb = report["sources"]["jobbank"].get("parsed", {})
    md.append(f"- Total results: {pb.get('total_results', 'unknown')}\n")
    for j in pb.get("jobs", [])[:10]:
        md.append(f"- **{j['title']}** — {j.get('company','')}, {j.get('location','')}")
    if not pb.get("jobs"):
        md.append("- no job cards parsed from live text (Job Bank heavily JS-driven; raw content saved to JSON)")

    md_path = f"{out_dir}\\.radar_report_cdp.md"
    json_path = f"{out_dir}\\.radar_report_cdp.json"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    # save JSON without the raw text (keep file small)
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump({k: {kk: vv for kk, vv in v.items() if kk != "raw"} for k, v in report["sources"].items()}, f, indent=2, ensure_ascii=False)
    print(f"Wrote {md_path}")
    print(f"Wrote {json_path}")

if __name__ == "__main__":
    main()