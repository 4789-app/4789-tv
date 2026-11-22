# Google Play submission package

This directory contains draft metadata and artwork for the two separate Play records.
It does not authorize publication and contains no signing key or Play Console credential.

## Phone — `com.fourseveneightnine.phone`

- Title: `4789 Media Player`
- Short description: `Browse your catalogs and play local or connected media privately.`
- Category: Entertainment
- Privacy policy: `https://4789library.com/privacy.html`
- Support: `https://4789library.com/support.html`

Long description:

> 4789 is a private media browser and player for catalogs, files, and Stremio-compatible sources
> you choose. Discover verified public catalog artwork or explore everything on the Wall. Open a
> title, select a video from Android's document picker, or connect your own HTTPS Stremio manifest
> and choose a fresh remote source.
>
> Playback uses Android Media3 with audio focus and lifecycle-safe teardown. Resume progress stores
> only a title identifier and position; local document capabilities and resolved stream URLs are
> never saved. Source configuration is encrypted with Android Keystore, excluded from backup, and
> never redisplayed after saving.
>
> 4789 has no ads, analytics, or 4789 account. It does not host, sell, index, or provide media.
> Users are responsible for connecting only files, services, and content they own or are authorized
> to access.

## TV — `com.fourseveneightnine.tv.play`

- Title: `4789 TV Receiver`
- Short description: `Browse your catalogs and play your media on Android TV.`
- Category: Entertainment
- Privacy policy: `https://4789library.com/privacy.html`
- Support: `https://4789library.com/support.html`

Long description:

> 4789 TV Receiver browses catalogs and collections, plays configured IPTV sources, and accepts
> playback from the 4789 phone app on the same local network. Local browsing does not require a
> connected phone. The remote controls provide play, pause, seek, stop, track selection, status,
> and explicit error recovery.
>
> The app does not host or sell media. Connect only content and
> services you own or are authorized to access. The Play TV package cannot install other applications.

## Data Safety draft

Console answers must be reviewed against the exact release binary:

- Data collection/sharing declarations: **Review required** against the exact binary and configured
  integrations. Provider requests and optional TypeSafe classification must be included in that review.
- Data encrypted in transit: **Source-dependent**. HTTPS sources use encrypted transport; HTTP
  sources and the receiver's local HTTP/WebSocket control traffic are unencrypted.
- Account creation: **No**.
- Delete-account URL: not applicable.
- Ads: **No**.
- Location permission: **No**. Approximate location is not derived or retained by the app.
- Credentials/configuration: stored on-device; phone manifests and TV settings use Android Keystore
  encryption. Provider requests use the credentials required by the configured source. Pair & Sync
  can transfer TV-compatible configuration from the phone.
- Optional TypeSafe classification sends category identifiers and sample channel/title text with the
  configured API key to `api.typesafe.ai`.
- Playback progress: phone progress and TV Room history use title identifiers and position/duration.
  TV interrupted-session resume also retains a URL and headers in encrypted storage. The client
  purges its obsolete plaintext recents cache at startup.
- SDK inventory: review the resolved dependencies for each exact phone and TV release.

## Required owner/Console gates

1. Supply the four `FOURSEVENEIGHTNINE_PLAY_*` upload-key variables and run
   `scripts/build-play-release.sh`.
2. Create both Play records, enroll Play App Signing, and upload the two release AABs.
3. Complete App Content, Data Safety, content rating, target audience, ads, App Access, and policy
   declarations using this draft and the final binary.
4. Run closed testing and Play pre-launch reports, then validate a physical API 26+ phone and an
   API 28+ Android TV before staged rollout.
