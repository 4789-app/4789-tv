# Contributing

## Before you build

You need JDK 17. Not 21, not 24 — the legacy receiver targets the Fire OS 7 runtime, and a newer
JDK changes Gradle's toolchain resolution until the build stops matching what ships. The same
toolchain also builds the shared contract, phone app, and Play TV bundle.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./scripts/check-android-foundation.sh --clean
```

That command is the gate. It runs frozen-contract tests, both TV test/lint gates, phone lint and
assembly, the legacy sideload APK, and the Play TV AAB. CI runs exactly it. These are QA artifacts;
Play publication still requires owner-controlled release signing and Play Console authority.

## Read these two files first

- [`CONTEXT.md`](CONTEXT.md) defines the vocabulary — *receiver*, *phone*, *box*, *panel*. The
  distinction between a **box** and a **panel** is not pedantry: a box can decode HDR that the
  panel cannot display, and confusing them produces real bugs.
- [`DECISIONS.md`](DECISIONS.md) records why things are the way they are, including the port
  history. The receiver has moved ports twice because of collisions with Amazon system services
  and with Kodi. Do not change `8791` or `9791` without reading it.

## Rules that are not style preferences

**Every new protocol field is optional.** An older phone and an older receiver must keep working
against a newer counterpart. Say so in a comment when you add one.

**Stock Kodi clients must keep working.** The wire protocol is Kodi JSON-RPC. Anything specific
to this project goes behind the `X4789.` prefix, and a client that has never heard of it must
still function.

**The compatibility floor is a 2022 Insignia Fire TV (`AFTDCT31`).** Most of the awkward code in
the player exists because of that one box: synchronous MediaCodec instead of async, software
decode as the verified default, the Android Surface attached before libmpv initialises. If you
are about to remove one of those workarounds because it looks unnecessary, it is not — read
[`HARDWARE_COMPATIBILITY.md`](HARDWARE_COMPATIBILITY.md) first, and test on hardware that old.

**No APK aggregators.** The installer downloads applications only from a developer's own release
channel or F-Droid. A video player installed from a random mirror is a rootkit with a play button.

## Licensing of contributions

This project is GPL-3.0, because it links GPL-3.0 components — see [`NOTICE.md`](NOTICE.md).
Contributions are accepted under the same licence. Do not paste in code you are not entitled to
relicense that way.

## Security

Do not open a public issue for a vulnerability. [`SECURITY.md`](SECURITY.md) explains where it
goes instead, and describes the receiver's actual threat model — which assumes a trusted home
network and has no authentication on the wire.
