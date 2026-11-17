# MoonCorn fixes and verification - 2026-11-17

## Commits
- `ba7c4ce` fix(mooncorn): auto-DJ fader direction and echo tail; bookmarklet private room
- `2ef18b2` test(mooncorn): expose djWatchAuto through the gated debug hook for fader verification

Deployed to `https://g3need.github.io/mooncorn/` and confirmed live (size 147219, marker present).

## Bug 1: auto-DJ deck mapping was inverted
Symptom: with a track live on Deck B, Auto-DJ moved the crossfader toward A while the outgoing audio stayed on B. The fade ran from the silent side to the live side.

Root cause: `djVolFor()` maps fader 0 = full A, 100 = full B, but `djWatchAuto()` started the fade at the opposite end (`A -> 100`, `B -> 0`). Both branches were reversed.

Fix: fade from the live deck's end toward the incoming deck (`xfFrom = deck === 'A' ? 0 : 100`).
Also fixed the echo mode ordering: the outgoing volume override was applied before `djApplyVolumes()`, which immediately clobbered it. It is now applied after. Outgoing deck restores `djVolFor(deck)` instead of a hardcoded 100.

Verification (`_mc_dj_fader_stub.py`, stubbed players, real app code, gated `?mcdebug=1`):
- Deck B live at xf=100: samples `[87, 87, 53, 20, 20, 0]`, monotonic down. Final volA=100, volB=0. PASS.
- Deck A live at xf=0: samples `[0, 26, 60, 60, 93, 93, 100]`, monotonic up. Final volA=0, volB=100. PASS.

## Bug 2: bookmarklet reused the shared moon-crown room
Symptom: host saw the movie in MoonCorn, guest (Tessa) saw a gray/buffering screen.

Root cause: every bookmarklet click put the new host tab into the default `moon-crown` room. Any other MoonCorn tab already in that room answers `REQUEST_STATE` with its own `activeMedia`, so a joining guest could adopt a stale or dead stream and never get the live one. The tip text claimed a "private room" but the code did not make one.

Fix: mint a fresh `mc-<random>` room per bookmarklet click unless `window.MOONCORN_ROOM` is set. The invite link carries the room, so the guest still lands in the right place.

Verification (`_mc_bookmarklet_test.py`, simulated LookMovie resource entries):
- Extracted stream: `.../c41.mp4/index.m3u8`. PASS.
- Room pattern `mc-[a-z0-9]{6}`. PASS.
- Two clicks produced two different rooms (`mc-4fikx9`, `mc-1olxh4`). PASS.

## Live environment evidence (Vivaldi CDP 9222, 2026-11-17 02:0x)
Before the fix, the Chairman's browser already carried the cross-talk condition:
- Two MoonCorn creator tabs open in `room=moon-crown`, playing DIFFERENT Wayward Pines episodes (S1-E6 on srv319, S1-E5 on srv317).
- One stale MoonCorn guest tab `room=synctest-div-1794814382&guest=true&stream=srv317...` still holding media state.
Any of those tabs answers a new joiner's REQUEST_STATE with its own activeMedia, so a guest could land on a stale or wrong stream. Personal tabs were left untouched; this was read-only inspection via location.href.

## Negative control: two creators in one shared room
`_mc_negcontrol_test.py` (dedicated headless Chrome, port 9245, 2026-11-17 02:22):
- Round B placed two creator tabs in `room=negshare-b`, one on Mux HLS and one on Apple bipbop HLS, then opened a bare guest tab.
- Guest result: `readyState 0`, `paused true`, body text `Connecting to "negshare-b"...` while both hosts were already playing. This reproduces Tessa's gray/buffering screen in the old shared-room condition.
- Round A did not fail, which is expected for a race: whichever stale peer answers REQUEST_STATE first wins.

`_mc_negcontrol3.py` (port 9247, 02:27), same setup with CDP Network instrumentation:
- Two shared-room rounds: guest resolved to the x36xhzz stream both times even though the other host carried bipbop and was also live. Ambiguous state confirmed.
- Positive control, fresh private room: guest resolved the intended stream, `readyState 4`, playing, drift 1.7s. PASS.

Conclusion: multiple creator tabs sharing one room makes guest media ambiguous and can stall the join entirely. Minting a fresh `mc-<random>` per bookmarklet click removes the ambiguity because only the new creator tab ever joins that room.

## Two-player playback verification
`_mc_invite_test.py`: headless dedicated Chrome, fresh random room, Mux public HLS.
- Host opened creator link with `?stream=`; guest opened bare `?room=&guest=true` (the REQUEST_STATE/LOAD_MEDIA path).
- Host: readyState 4, playing, t=12.3s. Guest: readyState 4, playing, t=12.0s. Drift 0.3s. PASS.
- Earlier direct test with both tabs carrying `?stream=` also showed both ends playing (host t=11.8s, guest t=15.0s).

Residual risk: if the LookMovie CDN itself rejects the far viewer (IP-bound token or geo-fence), no app-side change can force it. The unique-room fix removes the stale-peer failure mode that matched the report. If Tessa still sees gray after this deploy, capture her toast text or browser console, because it will now say whether the channel reset or the connection failed.

## Test harnesses
All under `C:/Users/Geneed/AppData/Local/hermes/cache/scratch/`:
`_mc_hls_test.py`, `_mc_invite_test.py`, `_mc_bookmarklet_test.py`, `_mc_dj_fader_stub.py`.
