#!/usr/bin/env python3
"""
ZED UK Sponsor Radar — filters the official UKVI Worker register for
high-fit AI/software sponsors matching Ahmed Geneed's profile.
Source: GOV.UK Worker and Temporary Worker register CSV (daily-updated).
Output: zed/uk_sponsor_targets.md
"""
import csv, re, datetime, os

OUT = os.path.dirname(os.path.abspath(__file__))
CSV = os.path.join(OUT, "ukvi_sponsors.csv")

# Profile fit keywords — weighted by relevance to AI/software architecture roles
KEYWORD_TIERS = {
    "tier1": ["artificial intelligence", "machine learning", "deep learning", "neural",
              "computer vision", "nlp", "language model", "robotics ai", "autonomous"],
    "tier2": ["software", "technology", "technologies", "tech", "digital", "data",
              "cloud", "systems", "platform", "labs", "computing", "engineer"],
    "tier3": ["research", "innovation", "quantum", "aerospace", "space", "semiconductor",
              "signal", "communications", "telecom"],
}
CITIES = {"London": 5, "Cambridge": 4, "Oxford": 4, "Manchester": 3, "Edinburgh": 3,
          "Bristol": 3, "Reading": 2, "Leeds": 2, "Glasgow": 2, "Birmingham": 2}

def score(name):
    n = name.lower()
    s = 0
    for k in KEYWORD_TIERS["tier1"]:
        if k in n: s += 10
    for k in KEYWORD_TIERS["tier2"]:
        if k in n: s += 3
    for k in KEYWORD_TIERS["tier3"]:
        if k in n: s += 2
    return s

def main():
    if not os.path.exists(CSV):
        print("ukvi_sponsors.csv not found — run the download step first")
        return
    with open(CSV, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    # unique by name (register has duplicates for multiple routes)
    seen = set()
    uniq = []
    for r in rows:
        key = r["Organisation Name"].lower()
        if key in seen: continue
        seen.add(key)
        if "Worker" not in r["Route"] or "A" not in r["Type & Rating"]:
            continue
        uniq.append(r)
    scored = []
    for r in uniq:
        s = score(r["Organisation Name"])
        city_bonus = 0
        for c, b in CITIES.items():
            if c in r["Town/City"]:
                city_bonus = b
                break
        total = s + city_bonus
        if s >= 3:  # at least one tier2 keyword hit
            scored.append((total, r))
    scored.sort(key=lambda x: -x[0])
    top = scored[:120]
    md = []
    md.append("# ZED UK Sponsor Targets — AI/Software fit shortlist\n")
    md.append(f"Generated: {datetime.date.today().isoformat()} from official UKVI Worker register ({len(uniq):,} A-rated Worker-route sponsors)\n")
    md.append(f"Filtered for AI/ML/software keywords + tech-hub cities. Top {len(top)} shown.\n")
    md.append("| # | Organisation | City | Fit |")
    md.append("|---|---|---|---|")
    for i, (s, r) in enumerate(top[:60], 1):
        md.append(f"| {i} | {r['Organisation Name']} | {r['Town/City']} | {s} |")
    md.append("\n## Next step")
    md.append("Cross-reference top 30 with current job openings (Careers pages / LinkedIn) for roles matching SOC 2134.")
    md.append("Each application = tailored CV + CoS-ready cover letter (UK dossier Section 2 template).")
    out = os.path.join(OUT, "uk_sponsor_targets.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print(f"Wrote {out} — {len(top)} targets (top 60 listed)")

if __name__ == "__main__":
    main()
