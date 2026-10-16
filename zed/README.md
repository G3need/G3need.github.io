# ZED — Zero Error Docs

Operational tooling for the ZED immigration advisory service (dad's Master Operating Manual, made real).

## Live pages (g3need.github.io/zed/)
- `eligibility.html` — Free Express Entry CRS estimate + document checklist, EN/AR. Lead magnet. Shows live IRCC cutoff (435, verified 2026-10-16).
- `intake.html` — Stage-2 intake dispatch message builder (EN/AR), WhatsApp copy-paste ready.

## Radars (run locally; CDP on 127.0.0.1:9222 must be up)
- `jobs_radar_cdp.py` — Live IRCC Express Entry rounds (latest CRS cutoff) + newsroom + Job Bank via Vivaldi CDP. Writes `.radar_report_cdp.md` + `.json`.
- `uk_sponsor_radar.py` — Filters official UKVI Worker register (143k sponsors) into a 120-name AI/software shortlist. Writes `uk_sponsor_targets.md`.
- `capture_raw.py` — Dumps raw page text for pilot docs.

## Dossier fixes (pilot client: Ahmed Geneed)
- `fix_uk_dossier.py` → `ZED_UK_Dossier_Ahmed_Geneed_CORRECTED.docx` — real contact info, GitHub links, pre-CoS reframing of Section 2.
- `fix_canada_dossier.py` → `ZED_Canada_Dossier_Ahmed_Geneed_CORRECTED.docx` — email, "Engineerd" typo, LOE/IRCC-compliance note.
- `fix_germany_dossier.py` → `ZED_Germany_Dossier_Ahmed_Geneed_CORRECTED.docx` — contact placeholders + §20a points self-check appendix (currently passing 7/6).

## Ops notes
- Vivaldi taskbar shortcut now includes `--remote-allow-origins=*` (fixed 2026-10-16). CDP WS was 403 before.
- canada.ca blocks urllib — always fetch via CDP, never plain requests.
- Pre-launch (no business number): receipts say "ZED — Zero Error Docs" only.
- ponytail cutoff: eligibility.html's LIVE_CUTOFF is a constant (manual update) — upgrade path: radar cron writes cutoff.json, page fetch()es it.
