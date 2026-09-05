# Source sanitization map — 0.1.42 / code 43

The v0.1.41 release carried a private staging notice. Its `first-party-source.tar.gz`
held owner-specific values in docs, tests, and fixtures. This file records what changed
and what stayed, so a reviewer can check the work.

Scan date: 2026-09-05.

## What was found in the v0.1.41 archive

- 18 private LAN addresses across 9 files.
- The owner's room nickname for the test box.
- **No** API keys, tokens, passwords, or credentials. Every credential-shaped match was a
  variable name in code, never a value.

## Address replacements

`192.0.2.0/24` is TEST-NET-1 from RFC 5737. It is reserved for documentation and can never
be a real host, so an example cannot point at anyone's equipment. The last number is kept
so cross-references between documents still line up.

Four private addresses on one home subnet were replaced, one per box, keeping the last
number. The literal before-and-after pairs are **not printed here**: this file ships inside
the public archive, so listing them would republish exactly what the sanitization removed.
The coordinator holds the full mapping out of band.

Files touched: `DECISIONS.md`, `HANDOVER.md`, `install-tv.sh`, `installer/serve.py`,
`scripts/verify-receivers.sh`, `scripts/verify-decoder-seek-recovery.sh`,
`tools/receiver_diag_analyzer.py`, `docs/playback-analysis-alpha-4kpro.md`,
`app/src/test/.../MpvStreamRelayTest.kt`.

## Name replacements

The owner's room nickname became neutral wording: "the test receiver",
"test-receiver synthetic", and similar. Files touched: `README.md`,
`docs/NATIVE_SOURCE_AUDIT.md`, `docs/PUBLIC_RELEASE.md`.

## Two tools no longer guess a receiver

`scripts/verify-decoder-seek-recovery.sh` and `tools/receiver_diag_analyzer.py` each had a
hardcoded fallback address. Swapping in a documentation address would have left them failing
in a confusing way, so both now require an explicit host instead:

- The shell probe takes an argument or `RECEIVER_HOST`, and exits 2 with usage text.
- The Python analyzer takes an argument, appends `:5555` when no port is given, and exits 2
  with usage text.

Both install to or read from a real box. Neither should ever choose one on its own.

## What was kept, on purpose

Hardware model names stay: `onn 4K Pro`, `AFTDCT31`, Fire TV, Hisense, NVIDIA Shield.

These are public facts about retail hardware, not private information.
`HARDWARE_COMPATIBILITY.md` exists to say which decoders and audio routes each model has, and
that document is worthless with the model names stripped out. Removing them would damage the
project for every reader and protect nobody.

## Verification

```bash
# Expect no output. Catches any private-range address, not just the four replaced,
# so a newly introduced one is caught too.
grep -rInE "(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)[0-9.]+" 4789TV | grep -v "/build/"
```

Run this against the unpacked `first-party-source.tar.gz` before publishing, not only against
the working tree. The archive is built from a commit, so an uncommitted fix is not in it.

Some hits are expected and correct. `LocalNetworkAddressTest` and
`TVSettingsPairingCoordinatorTest` use invented addresses such as `192.168.1.24`, `10.0.0.5`,
and `192.168.68.59`. A test for picking a local address needs real-looking private-range input
to prove anything. None of these belong to anyone's equipment, and they are kept. Judge a hit
by whether it identifies a real machine, not by whether it looks private.
