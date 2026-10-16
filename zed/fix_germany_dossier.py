#!/usr/bin/env python3
"""
ZED Dossier Builder — Germany lane.
Fixes Germany dossier placeholders with Ahmed's real data + adds Chancenkarte points check.
"""
import os, datetime
from docx import Document

SRC = r"C:\Users\Geneed\AppData\Local\hermes\cache\documents\doc_bf0e7ad5d6a8_ZED_Dossier_Pilot_-_Germany_Chancenkarte___EU_Blue_Card_Tech__Ahmed_Geneed_.docx"
OUT_DIR = r"C:\Users\Geneed\Desktop\draconis-hub\zed"

POINTS_CHECK = """
CHANCENKARTE POINTS SELF-CHECK (§20a AufenthG, need 6 of 12):
+4  Recognized university degree (MIU B.Sc., Anabin H+) .................... QUALIFIES
+3  2+ years skilled work experience in last 5 years (Telecom Egypt + EAF + Yellow Works) .. QUALIFIES
+1  German A2 (target: certify by exam) .................... IN PROGRESS
+1  English C1 (self-assessed; official proof recommended — IELTS/TOEFL) ....... AVAILABLE WITH CERT
+1  Age under 35 ............................................ QUALIFIES (if applicable)
+1  Previous stay in Germany / family ....................... N/A
= Running total without German cert: 7 points -> PASSES the 6-point gate.
Action items: book German A2 exam + take official English C1 test to bank the points with documents.
"""

REPLACEMENTS = [
    ("Email: Person", f"Email: ahmedgeneed@gmail.com"),
    ("Phone: Person", "Phone: +20 155 196 1964"),
]

def main():
    doc = Document(SRC)
    changed = 0
    for p in doc.paragraphs:
        for old, new in REPLACEMENTS:
            if old in p.text:
                p.text = p.text.replace(old, new)
                changed += 1
    # signature block
    for p in doc.paragraphs:
        if p.text.strip() == "Person":
            p.text = "Ahmed Geneed"
            changed += 1
        elif p.text.strip() == "Place":
            p.text = "Ismailia, Egypt"
            changed += 1
        elif p.text.strip() == "Date":
            p.text = datetime.date.today().isoformat()
            changed += 1
    # append points self-check at end
    doc.add_page_break()
    doc.add_paragraph("SECTION 5: CHANCENKARTE POINTS SELF-CHECK (ADDED BY ZED)")
    for line in POINTS_CHECK.strip().split("\n"):
        doc.add_paragraph(line)
    changed += 1
    out = os.path.join(OUT_DIR, "ZED_Germany_Dossier_Ahmed_Geneed_CORRECTED.docx")
    doc.save(out)
    print(f"changed {changed} -> {out}")
    doc2 = Document(out)
    bad = [p.text[:60] for p in doc2.paragraphs if "Person" == p.text.strip()]
    print("verification:", "PASS" if not bad else f"FAIL: {bad}")

if __name__ == "__main__":
    main()
