# Subtitle source notes, distilled 2026-11-23

Purpose: pick sources for MoonCorn's auto-subtitle lane without guessing. Facts below were read live today, not remembered.

## Hard facts
- OpenSubtitles.org closed its free API. The replacement is opensubtitles.com, which needs a logged-in account plus an API Consumer entry in the account dashboard before any call works. Anonymous calls return HTTP 415 unless the caller sends a proper User-Agent AND auth. A key is mandatory.
- SubDL (subdl.com) publishes a real API at subdl.com/api-doc and developers' page. Search and download work on a FREE key. Paid tier only unlocks AI translation, AI filename search, 30,000 requests/day and 2,000 downloads/day.
- Addic7ed has no public API. Scraping only, last resort.
- YIFY subtitles are direct file links, no search API.

## Chain order for MoonCorn relay
1. Player's own built-in tracks first, they already carry opensubtitle_id and language_id and match the exact release.
2. SubDL free key, language aware, returns SRT or VTT.
3. OpenSubtitles.com with OS_API_KEY, only if SubDL misses.
4. Manual URL upload, always available.

## Status 2026-11-23, LIVE
- Free SubDL key is stored in `Desktop/career/CREDENTIALS.txt` as `subdl_key`, loaded by the relay.
- Search verified locally and through the public tunnel: query "Dune" returned 7 candidates in under 3 seconds.
- Fetch verified end to end: first candidate converted to WEBVTT, 122,490 bytes, 1,730 cues.
- The key never reaches the browser. Search strips `api_key` from upstream links and the relay re-attaches it only on the server side call.
- Free tier limits from the developer docs: 2,000 searches per day and 50 downloads per day. Watch the quota before bulk use.
- OpenSubtitles.com remains the optional second provider (needs an account plus API consumer entry). Not needed while SubDL covers the lane.

## What blocks us today
Nothing. SubDL is wired and live. Only an optional OpenSubtitles key remains if we ever want a second fallback provider.

## Action
Done. Relay runs SRT to VTT server side, strips BOM, serves SubDL first in the chain and keeps the key server side. Next possible step, only if wanted: add OpenSubtitles.com as a second provider behind OS_API_KEY.
