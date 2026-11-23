## What and why

<!-- One or two sentences. Link the issue: Fixes #123 -->

## How it was tested

<!-- Automatic checks, and on which devices / simulator if any. Test numbers +10000000001…9 only. -->

## Checklist

- [ ] `tools/test-all.sh` passes (or `--quick` / `--no-ios`, saying which)
- [ ] Wire change: `protocol/PROTOCOL.md`, both implementations, `protocol/fixtures/` (and vectors for crypto or framing) updated — or no wire change
- [ ] User-facing text in English **and** Persian
- [ ] No personal data: phone numbers, names, device ids, team ids, keys, tokens or logs from real phones (`tools/check-private.sh`)
- [ ] `CHANGELOG.md` (Unreleased) updated for user-visible changes
- [ ] Docs updated where behaviour changed
