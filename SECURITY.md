# Security

## Reporting a vulnerability

Open a [private security advisory](https://github.com/4789-app/8-tree-player/security/advisories/new)
rather than a public issue. Please include what you did, what happened, and what you expected.
Expect a first reply within a week; this is a spare-time project, not a staffed one.

## What this software does, stated plainly

The receiver opens **two listening ports on your television**: HTTP on `8791` and a WebSocket on
`9791`. Anything on the same network can reach them. There is **no authentication on the wire** —
the protocol is Kodi JSON-RPC, and the design assumes a home network you control.

Do not run the receiver on a network you do not trust: a café, a hotel, a dormitory, or a
corporate guest VLAN. On such a network, any other device can tell your television what to play.

The Mac-side installer in `installer/` is a separate and sharper tool. It runs `adb` commands, so
whoever can reach it can install software on any television it can reach. It defends itself in
three ways, and you should understand all three before using `--lan` or exposing it:

1. It binds to `127.0.0.1` unless you pass `--lan`.
2. In `--lan` mode every request must carry a 192-bit key, kept in `~/.4789-installer-token`
   with mode `600`. The phone bookmark holds that key in its URL.
3. Requests arriving through a reverse proxy — a tunnel, for instance — are treated as remote and
   must present the key even though they connect from loopback.

If you put the installer behind a public tunnel, the key in the URL is the only thing between the
internet and your televisions. Treat that URL as a password. Rotate it by deleting
`~/.4789-installer-token` and restarting, which invalidates every saved bookmark.

## What ships in a release build

Releases are currently **debug-signed**. A debug signature is not a security boundary: it proves
nothing about who built the APK. Verify what you install, and prefer building from source if that
matters to you.

## Secret scanning

Every push and pull request runs `gitleaks` over the tree and fails the build on a finding — see
[`.github/workflows/secret-scan.yml`](.github/workflows/secret-scan.yml). Run the same check
locally before opening a pull request:

```bash
gitleaks detect --no-git --redact
```

Nothing in this repository should ever contain a credential. Configuration that needs one reads
it from the environment or from the device's own secure storage at runtime.
