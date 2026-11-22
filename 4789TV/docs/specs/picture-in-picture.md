# Picture-in-picture for 4789 TV

Receiver and launcher behavior · Updated 18 November 2026

## Movie and series corner controls

During a movie or episode, **Guide** or the player’s **PiP** icon immediately puts the current video in the corner and returns to browsing. Choose **Go to video** in the Guide menu to expand the same session. **Close picture** in PiP options stops playback and saves progress. Choose a live channel while browsing with a pinned movie or episode to play the channel full-screen and keep the title in the corner. From the full-screen movie, open **More → Add live channel**. The existing paired controls still swap pictures, switch sound, move and close the small picture.

The alternate on-demand shortcut uses Channel Up. If it already has a video assignment, 4789 uses a free Channel Down. When the extra-button service is active, a free Star or Free TV shortcut can also be used. If every available shortcut already has a video action, hold Channel Up for PiP. A short press runs its saved action. An explicit **Picture in picture** assignment takes priority. **Settings → Playback → Remote buttons** shows the chosen button. The guide and the corner use the same 32-unit radius on the 1920×1080 design canvas. The in-app 16:9 corner defaults to 42% of the screen width.


## Earlier releases

Receiver 0.2.33-den-pip (132) and Den TV Home 0.8.5 (14) use a 16:9 corner picture at 40% of the screen width. When browsing with live TV, select **Return to live TV** under the picture. **Menu**, then **OK**, also returns to that channel. Back from the receiver Home screen with the rail closed returns to the player. Pinning a playing channel retains its stream. When a movie starts, the receiver transfers the already-open live player to the second picture.

Build 133 adds a **PiP** icon at the left of the receiver's player controls. Select it during a movie to add a live channel, or during full-screen live TV to browse with the channel pinned. With both pictures playing, the icon opens picture swap, sound, position and close actions. Back from the paired movie returns to browsing with the match controls. Explicit Stop ends both streams. See the [in-app Den test](../../../docs/testing/tv-in-app-pip-b133.md).

The launcher lends the receiver a rendering surface. The receiver retains the source and enforces the existing household lock policy. Launcher playback is available for a single profile without a PIN. Both apps use native video surfaces. Official YouTube remains a separate optional story.

See the [physical onn measurements](../../../docs/testing/tv-pip-onn-2026-11-15.md) and [initial emulator results](../../../docs/testing/tv-pip-2026-11-15.md).

## Floating video on Google TV

Press **PiP** in the player to keep one local movie, episode or live channel
in the corner while browsing 4789. On the direct Google TV build, the same
picture follows the viewer after pressing **Home** or opening another app.
The video and sound continue from the same player. The floating window draws
video and controls together in a hardware-accelerated view. 4789 reveals it when
the player renders the first frame to that window's video output. The visible
picture is opaque. When the screen underneath has remote focus, its arrows work
and touches outside the picture still reach it. **Browse X** in the pinned picture's menu
opens X.com, where X handles sign-in and **Search posts** searches the site.

Use **Settings → Playback → Remote buttons → Extra buttons** to enable the
Guide and Profile shortcuts. **Guide** opens a compact popup beside the picture
in 4789 and inside the floating window over other apps. The controls stay hidden
while the viewer browses. Press **Guide** again to close the popup and restore focus.
**Profile** hides and mutes the picture or shows it and restores its sound.
The popup offers Small (33%), Large (42%), Extra large (52%), **Move picture**,
and **Go to video**. In 4789 it also offers **Close picture**. Use arrows to move
between corners, **OK** to save, or **Back** to cancel. 4789 remembers the size and
corner in both places. **Go to video** expands the same playback session.

Enable **Display over other apps** from **Allow PiP over other apps** in the
pinned picture menu. Floating playback requires Android TV 14 or later and one
profile without a PIN. Group locks apply to IPTV channels and titles.
The window closes when playback ends or the TV turns off. Apps that hide overlays
can cover the picture.

The receiver moves the existing decoder to the window and removes its phone
discovery and remote playback ports while the receiver activity is hidden.
It uses the Android application overlay permission available in the direct
Google TV flavor. Google TV system PiP has a different set of permitted uses.
[Google TV multitasking](https://developer.android.com/training/tv/get-started/multitasking)
· [Overlay permission](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))

## What the viewer wants

1. Watch a 4789 movie or episode and a live match from 4789 on the same screen.
2. Keep a small playing picture on the launcher.
3. If the Den box supports it, watch a movie in the official YouTube app with the match still playing in the corner. Starting the match first and adding it while YouTube is already playing are separate tests.

The first story succeeds only if both pictures advance. The second requires visible motion while navigating Home. Each can ship as soon as it passes its own acceptance checks. YouTube support is optional.

## Original feasibility notes

| Where the viewer is | Who draws the small picture | Current finding |
| --- | --- | --- |
| Inside 4789 | The receiver | Feasible in principle. The receiver already draws multiple live channels. A movie and live channel use different playback paths. Their combination still needs a physical two-decoder test. |
| Den TV Home | The custom launcher can host a player in its own window | Candidate for a real playing tile. The current integration supplies rows, artwork and navigation targets. It needs a new, private playback handoff and changes to both apps. |
| Official YouTube app on Google TV | The system or a device-specific multitasking feature | Google's Android TV rules exclude media playback from system PiP. The sports ticker category covers scores. A launcher tile disappears when YouTube takes the foreground. The standard Google TV path cannot deliver this story. |

The owner selected the Den onn 4K Pro with Den TV Home as the first target. The project handover records Android 14 on that box. Record the installed OS and launcher versions when testing. Only claim official YouTube support if a documented device feature passes the two-video test on this box.

[Google’s TV multitasking rules](https://developer.android.com/training/tv/get-started/multitasking) · [Android’s guidance on PiP and alert windows](https://developer.android.com/develop/ui/views/picture-in-picture) · [Den box handover](../../HANDOVER.md)

## In-app viewing behavior

| Viewer action | Behavior |
| --- | --- |
| Start from a movie or episode | Add a 4789 live channel from the player. Keep the title playing while selecting the channel. |
| Start from a match | Keep the match visible while browsing 4789. Choose a movie or episode and watch both. |
| Enter two-picture playback | Hear the large picture by default. Keep the other stream muted. |
| Choose **Swap pictures** | Exchange large and small pictures. Keep playing both streams and preserve the current sound choice. |
| Choose **Switch sound** | Hear the other stream. Keep both pictures in place. |

The owner confirmed both entry orders, initial sound from the large picture, and independent picture and sound controls. Proposed remote details place the actions in the existing player controls, including **Move to corner** and **Close small picture**. Back closes the open picker or panel first. The playback scope in the examples is a locally selected 4789 title plus a 4789 live channel. Existing phone casting and external-player handoffs are regression checks.

## Pressing Home and returning to 4789

A single pinned picture follows the viewer to Home as described above. The Den TV Home tile also handles departure from the full-screen player and two-picture playback. The viewer can keep navigating the launcher with the remote.

| Playing in 4789 at the Home press | While browsing Den TV Home | On reopening 4789 in the same viewing session |
| --- | --- | --- |
| One movie or episode | The same title continues in the tile from its current position. | Continue the title from the position reached on Home. |
| One live channel | The same live channel continues in the tile. | Continue watching that channel live. |
| A movie or episode and a live channel | Always carry the live channel, regardless of its size or which stream had sound. Pause the title and save its position. | Restore both pictures in their previous positions and sizes. Resume the title at the saved position, show the same channel live and restore the viewer’s sound choice. |

Reopening from the 4789 app icon and selecting the playing tile should both use the same return behavior. A second Home press should leave the existing tile and saved movie position intact. The launcher tile belongs to the active viewing session. A permanent favorite-channel widget would be a separate feature.

## Receiver implementation plan

1. **Prove the movie-plus-match pair.** On the selected box, play a known movie or episode with the current player. Add one muted live stream on a small surface. Verify both pictures advance and the movie can still seek. Repeat with the actual codec and resolution combinations the owner uses. The live grid currently checks advertised AVC decoder instances. That number alone cannot establish support for a 4K HEVC movie plus an HD match.
2. **Keep playback ownership explicit.** Retain the existing on-demand session for seek, tracks, subtitles, episode advancement and saved progress. Give the pinned live channel its own lifetime. Reuse live-channel resolution, access checks and player setup. Keep the two sessions when changing picture sizes. The current Multiview route explicitly stops the activity-owned player on entry, so opening it directly would stop the movie.
3. **Make selection safe while the movie runs.** Use the guide’s channel data and access rules. Highlighting rows in the picker must leave the current streams alone. Selecting a channel tunes once. Cancelling restores the originating control. A failed or delayed tune must not replace the last good selection or interrupt the movie. Keep the existing channel-preview behavior in ordinary single-stream browsing.
4. **Implement the chosen remote and audio rules.** Keep controls, captions and the live score visible where possible. Identify the audible stream when player controls are open. Make each action reachable using a standard D-pad and OK. Pinning, moving and swapping must preserve the movie playhead and selected tracks. Check picture stacking and subtitle visibility on the physical box.
5. **Handle competing streams and exits.** Count connections for the provider that serves each stream, including active recordings where known. A movie from a separate source does not consume a second live-provider slot. Handle unknown limits and actual decoder/provider refusal without stopping the primary picture. Recheck locked channels and active profile on every handoff. Release the secondary stream on close, sleep or profile change. Keep existing phone/Kodi command ownership deterministic when a new play request arrives.

Code entry points are [PlaybackSession](../../app/src/main/java/com/fourseveneightnine/tv/client/playback/PlaybackSession.kt), [Live Multiview](../../app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/live/LiveMultiviewScreen.kt), [Live TV guide](../../app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/live/LiveTvScreen.kt) and [player controls](../../app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/player/PlayerScreen.kt).

## Den TV Home implementation plan

1. Prototype one small video surface in the visible launcher. Exercise it with a seekable movie and a live channel. Keep source resolution and profile access checks in the receiver. Keep stream URLs, request headers and provider credentials out of launcher storage, intents, artwork rows and logs.
2. Test a round trip before choosing the final transport. Den TV Home should request the active handoff when it becomes visible. A receiver stop can also mean sleep or opening another app. Treating every stop as a Home press would start the tile at the wrong time. For two-picture playback, capture the title or episode ID, movie playhead, channel ID, layout and audible stream before the receiver detaches its surface. Associate these values with the active profile and viewing session.
3. Give exactly one surface ownership of each stream during transfer. Track movie progress while a solo movie plays on Home. When the match is on Home, preserve the saved movie playhead until return. Ignore late callbacks from a previous handoff. Recheck access before restoring and use the existing resume flow if a source expires. If restoration fails for one picture, retain the playable picture and saved position and identify which stream can be retried.
4. Verify D-pad navigation around the playing tile and return through the tile and app icon. Exercise repeated Home presses, either process dying during transfer, an older launcher, and opening another app. A one-connection live account must never open two copies of the same channel. Release the launcher surface when Home is hidden. Only the optional, separately verified cross-app mode could continue drawing over another app.

The existing contracts are visible in [launcher rows](../../app/src/main/java/com/fourseveneightnine/tv/client/home/LauncherRails.kt) and [receiver lifecycle](../../app/src/main/java/com/fourseveneightnine/tv/client/playback/ReceiverHost.kt). The prototype must respect existing visibility rules for phone discovery and pairing.

## Acceptance and release decision

| Test on the selected box | Passing result |
| --- | --- |
| Movie first, then match | Both pictures advance. Movie seek and captions work. Closing the match preserves the movie position. |
| Match first, then episode | The match remains visible through title selection. Starting the episode adds the second picture. Starting the next episode preserves the pinned channel. |
| Audio and D-pad | Initially only the large picture is audible. Switch sound and verify neither picture moves. Swap pictures and verify the chosen stream stays audible. Both videos continue. Closing the picker restores focus to its opener. |
| Provider or decoder limit; channel tune fails | The viewer gets a reason or a retry action. The existing picture continues. Repeated attempts leave no extra players, connections or audio. |
| Home with a solo movie or episode | Press Home, browse and let the title play. Reopen 4789. The playhead includes time watched in the tile. |
| Home with a solo match | Press Home and navigate between launcher items. The channel keeps advancing. Return to the same live channel. Repeat with a one-connection live account. |
| Home with both pictures | Try all four combinations of large picture and audible stream. Press Home twice. Each time, only the match plays on Home. Return through the tile and through the app icon in separate runs. Both pictures resume in the previous layout with the previous sound choice. Movie time excludes the time spent on Home. |
| Optional official YouTube test | Test each starting order separately if a documented mechanism exists. The official app stays in the foreground and both videos advance. Record which orders work. |
| Interruption and privacy | Test Back, Home twice, screen off, process death, profile switch, a locked channel, an active recording and a phone play request. Verify cleanup, saved progress and which player gets each command. |
| Existing playback | Single-player movies, normal live-guide previews, the existing live grid, phone casting and ordinary Home behavior still work. |

Run the [Android foundation gate](../../scripts/check-android-foundation.sh) after implementation. For remote-input changes, also run the [D-pad probe](../../scripts/verify-decoder-seek-recovery.sh) on an explicitly selected test receiver and capture focus evidence. Use an emulator for repeatable navigation and failure tests. Verify simultaneous decode, audio, captions and launcher transitions on the Den box. Report independent results for in-app viewing, Den TV Home and optional YouTube support.

## Guide shortcut

| Where you press Guide | What happens |
| --- | --- |
| A full-screen movie, episode, or live channel is playing in 4789 | The video moves to the corner and the app returns to browsing. |
| One picture is pinned while browsing or over another app | A compact popup opens with focus on the current size. Press Guide again to close it and restore focus. |
| A movie or episode and a live channel are playing together | PiP options open for swapping pictures, switching sound, moving the small picture, or closing it. Press Guide again to close them. |

Use the D-pad and **OK** to choose an option. **Go to video** returns to full-screen playback.
Guide takes priority over saved button assignments while these PiP actions are available.
The **4789 Remote buttons** accessibility service remains opt-in. It handles Guide on
devices that reserve the key and while other apps are in front.
