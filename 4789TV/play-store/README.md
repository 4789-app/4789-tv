# Google Play submission package

This directory is the checked, upload-ready metadata and artwork source for the two separate Play
records. It does not authorize publication and contains no signing key or Play Console credential.

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
- Short description: `Receive and control your authorized media on Android TV.`
- Category: Entertainment
- Privacy policy: `https://4789library.com/privacy.html`
- Support: `https://4789library.com/support.html`

Long description:

> 4789 TV Receiver turns an Android TV into a receiver for the 4789 phone app on the same local
> network. It provides a ten-foot, remote-controlled playback surface with play, pause, seek, stop,
> track controls, status, and explicit error recovery.
>
> The receiver does not include a catalog or media service. It plays only URLs sent by a paired
> client for content the user owns or is authorized to access. There are no ads, analytics, or
> accounts. The Play TV package cannot install other applications.

## Data Safety draft

Console answers must be reviewed against the exact release binary:

- Data collected by the developer: **No**.
- Data shared by the developer: **No**.
- Data encrypted in transit: **Yes** for Internet source traffic. TV receiver traffic is local-LAN
  control/media traffic disclosed by product function; no developer server receives it.
- Account creation: **No**.
- Delete-account URL: not applicable.
- Ads: **No**.
- Location permission: **No**. Approximate location is not derived or retained by the app.
- Credentials/configuration: device-local; phone manifest is Android-Keystore encrypted and excluded
  from backup. It is sent only to the user-selected provider as part of the URL they supplied.
- Playback progress: device-local title ID plus position/duration; no media URL.
- SDK inventory: AndroidX/Compose/Media3/Coil/OkHttp/Kotlin only in phone; no advertising or analytics SDK.

## Required owner/Console gates

1. Supply the four `FOURSEVENEIGHTNINE_PLAY_*` upload-key variables and run
   `scripts/build-play-release.sh`.
2. Create both Play records, enroll Play App Signing, and upload the two release AABs.
3. Complete App Content, Data Safety, content rating, target audience, ads, App Access, and policy
   declarations using this draft and the final binary.
4. Run closed testing and Play pre-launch reports, then validate a physical API 26+ phone and an
   API 28+ Android TV before staged rollout.
