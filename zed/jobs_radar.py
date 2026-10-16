#!/usr/bin/env python3
"""
ZED — Job Radar (Canada pipeline, v1)

Scrapes three independent sources:
  1. IRCC Express Entry draws (official newsroom) — EEA cutoffs per category
  2. Canada Job Bank — foreign worker / LMIA-related signals
  3. German Positivliste placeholder (for later)

Output: zed/radar_report.md  (human-readable) + zed/radar_report.json (machine)

Run: python jobs_radar.py
No external deps beyond stdlib — works anywhere python3 exists.
"""
import json
import re
import urllib.request
import datetime
import os

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

def get(url, timeout=12, retries=1):
    last_err = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": UA,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-CA,en;q=0.9",
                },
            )
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode("utf-8", errors="ignore")
        except Exception as e:
            last_err = e
            if attempt < retries:
                import time
                time.sleep(1)
    raise last_err

def ircc_draws(limit=8):
    """Parse IRCC newsroom for Express Entry draw announcements.
    canada.ca sits behind Akamai bot detection — try two URLs, fall back
    to a known-good static seed so the report is never empty."""
    urls = [
        "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/news.html",
        "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/rounds-invitations.html",
    ]
    for url in urls:
        try:
            html = get(url)
        except Exception as e:
            print(f"      {url.split('/')[2]}: {e}")
            continue
        items = []
        for m in re.finditer(
            r'<article.*?</article>', html, re.DOTALL
        ):
            block = m.group(0)
            am = re.search(
                r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', block, re.DOTALL
            )
            if not am:
                continue
            title = re.sub(r"<[^>]+>", "", am.group(2)).strip()
            link = am.group(1)
            if "express-entry" not in link and "express entry" not in title.lower():
                continue
            date_m = re.search(
                r"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},\s+\d{4}",
                block,
            )
            items.append(
                {
                    "title": title,
                    "link": "https://www.canada.ca" + link if link.startswith("/") else link,
                    "date": date_m.group(0) if date_m else "unknown",
                }
            )
            if len(items) >= limit:
                break
        if items:
            return items
    # static seed — last-known Express Entry draw values (update manually when live fetch works)
    return [
        {
            "title": "Express Entry: draw cutoff (last known, verify on IRCC site)",
            "link": "https://www.canada.ca/en/immigration-refugees-citizenship/services/immigrate-canada/express-entry/news.html",
            "date": "see link",
            "note": "static fallback seed — live scrape blocked by bot detection; refresh values by opening the link manually",
        }
    ]

def ircc_draw_crs(draw_url, timeout=20):
    """Fetch a single draw page, extract category + lowest CRS if present."""
    try:
        html = get(draw_url, timeout=timeout)
    except Exception as e:
        return {"error": str(e)}
    text = re.sub(r"<[^>]+>", " ", html)
    text = re.sub(r"\s+", " ", text)
    result = {"url": draw_url}
    m = re.search(r"(\d{3})\s+points", text, re.I)
    if m:
        result["crs_cutoff"] = int(m.group(1))
    for cat in ["international students", "pharmacist", "french language", "health care", "tech draw", "LMIA", "category-based"]:
        if cat.lower() in text.lower():
            result.setdefault("category_keywords", []).append(cat)
    # extract date
    dm = re.search(
        r"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},\s+\d{4}",
        text,
    )
    if dm:
        result["date"] = dm.group(0)
    return result

def canada_jobbank(limit=5):
    """Canada Job Bank — search for 'software engineer' or similar.
    Real endpoint is a JS app; use the sitemap/print view instead."""
    # Job Bank search is heavily JS; fall back to a known stable search URL
    url = "https://www.jobbank.gc.ca/search/jobsearch?text=software%20engineer&location=&resultsRange=0&resultsRangeOption=5"
    try:
        html = get(url, timeout=25)
    except Exception as e:
        return [{"note": f"jobbank fetch failed: {e}"}]
    out = []
    for m in re.finditer(
        r'<a[^>]+href="(/search/jobdetails\?[^"]+|https://www\.jobbank\.gc\.ca/search/jobdetails\?[^"]+)"[^>]*>(.*?)</a>',
        html,
        re.DOTALL,
    ):
        out.append(
            {
                "title": re.sub(r"<[^>]+>", "", m.group(2)).strip(),
                "link": m.group(1)
                if m.group(1).startswith("http")
                else "https://www.jobbank.gc.ca" + m.group(1),
            }
        )
        if len(out) >= limit:
            break
    return out

def main():
    report = {
        "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "sources": {},
    }
    print("[1/3] Fetching IRCC Express Entry draws...")
    draws = ircc_draws(limit=5)
    report["sources"]["ircc_express_entry_draws"] = {
        "count": len(draws),
        "items": draws,
    }
    # enrich the newest draw with CRS
    valid_draws = [d for d in draws if "title" in d]
    if valid_draws:
        print(f"      Enriching newest draw: {valid_draws[0]['title'][:60]}...")
        report["sources"]["ircc_express_entry_draws"]["latest_detail"] = ircc_draw_crs(
            valid_draws[0]["link"]
        )

    print("[2/3] Fetching Canada Job Bank sample listings...")
    report["sources"]["canada_jobbank_sample"] = canada_jobbank(limit=5)

    print("[3/3] Writing report...")
    md = []
    md.append("# ZED Job Radar — Canada Pipeline\n")
    md.append(f"Generated: {report['generated_at']}\n")

    md.append("\n## IRCC Express Entry Draws (last 5)")
    valid_draws = [d for d in draws if "title" in d]
    for i, d in enumerate(valid_draws, 1):
        md.append(f"\n{i}. **{d['title']}** — {d['date']}")
        md.append(f"   {d['link']}")
    if not valid_draws:
        md.append("\n- no draws could be fetched (see JSON report for error details)")
    latest = report["sources"]["ircc_express_entry_draws"].get("latest_detail")
    if latest and "crs_cutoff" in latest:
        md.append(f"\n> Latest draw CRS cutoff: **{latest['crs_cutoff']}**")
        if latest.get("category_keywords"):
            md.append(f"> Detected category keywords: {', '.join(latest['category_keywords'])}")

    jb = report["sources"]["canada_jobbank_sample"]
    md.append("\n## Canada Job Bank — sample openings (software engineer)")
    for item in jb:
        if "note" in item:
            md.append(f"\n- note: {item['note']}")
        else:
            md.append(f"\n- **{item['title']}**\n  {item['link']}")

    md_path = os.path.join(OUT_DIR, "radar_report.md")
    json_path = os.path.join(OUT_DIR, "radar_report.json")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"Done. Wrote {md_path}")
    print(f"       Wrote {json_path}")

if __name__ == "__main__":
    main()
