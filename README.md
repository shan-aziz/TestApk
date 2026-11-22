# Fire TV Web App

A Fire TV app that opens your website full-screen, plays its videos (including full-screen video) and works with the Fire TV remote.

## 0. Check your stick first

On the Fire TV go to **Settings → My Fire TV → About**.
If the software version starts with **"1."**, the stick runs Vega OS. Vega OS **can't run this app**, and nothing can be installed on it from outside Amazon's store.
That applies to the *Fire TV Stick 4K Select* and the 2026 *Fire TV Stick HD*. Every other Fire TV Stick (Fire OS) is fine.

## 1. Set your website

Open `app/src/main/res/values/config.xml` and change:

| Setting | What it does |
|---|---|
| `start_url` | The website the app opens (must be `https://`) |
| `app_name` | Name on the Fire TV home screen |
| `start_in_pointer_mode` | `true` shows an on-screen pointer you move with the arrows, which works with any site. `false` if your site already handles arrow keys. |

Optional: replace `app/src/main/res/drawable/app_banner.png` (320×180) and `app_icon.png` (512×512) with your own artwork.

## 2. Build it

1. Install **Android Studio** (free): https://developer.android.com/studio
2. **File → Open** and choose this `FireTvWebApp` folder. Wait for the first sync to finish (it downloads what it needs).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   The file appears at `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Put it on the Fire TV Stick

**Turn on developer mode (one time):**
1. **Settings → My Fire TV → About**. Highlight your device name (e.g. "Fire TV Stick") and press **OK 7 times** until it says you're a developer.
2. Go back to **My Fire TV → Developer Options** and turn on **ADB debugging**.
3. Note the stick's IP address: **About → Network**.

**Option A: install straight from Android Studio (easiest)**
1. On your computer, open a terminal (Android Studio has one at the bottom) and run:
   `adb connect 192.168.x.x:5555` (use your stick's IP)
2. On the TV, choose **Allow** when it asks.
3. In Android Studio, pick the Fire TV in the device list at the top and press **Run ▶**. The app installs and opens.

**Option B: the Downloader app**
1. Upload `app-debug.apk` somewhere with a direct download link.
2. On the Fire TV, install **Downloader** from the Amazon Appstore. In **Developer Options → Install unknown apps**, allow Downloader.
3. Open Downloader, enter the link, and install.

The app then shows up under **Your Apps & Channels** on the home screen.

## Remote controls

| Button | Action |
|---|---|
| Arrows | Move the pointer (hold to go faster). Pushing past an edge scrolls whatever is under the pointer. |
| OK (center) | Click |
| ☰ Menu | Turn the pointer on/off |
| Back | Exit full-screen video, then go back a page, then close the app |
| ⏯ Play/Pause | Play/pause the video on the page |
| ⏪ / ⏩ | Skip back/forward 10 seconds |

## Making the website TV-friendly

- **Video:** HLS (`.m3u8`) or DASH with several quality levels streams best. A `<video>` tag with an MP4 works too. For HLS, use **hls.js** on the page for the most consistent playback.
- **Protected video (DRM):** Widevine is supported. The app grants the permission automatically.
- **Readability:** Design for a TV viewed from a couch: big text and big buttons. The page is about 960 CSS pixels wide on a 1080p Fire TV.
- **Pointer-free navigation (optional):** If you want arrow keys to jump between buttons the way Netflix does, add arrow-key focus handling to the site (e.g. the *Norigin Spatial Navigation* library) and set `start_in_pointer_mode` to `false`.
- **Plain `http://` sites are blocked.** Use `https://`. For local testing only, you can set `android:usesCleartextTraffic="true"` in `AndroidManifest.xml`.

## Debugging

With the stick connected over `adb`, open `chrome://inspect` in Chrome on your computer to see the page's console and inspect elements while it runs on the TV.

## Publishing to the Amazon Appstore (later)

1. Change `applicationId` in `app/build.gradle.kts` to something unique (e.g. `com.yourname.tvapp`).
2. Build a signed release: **Build → Generate Signed App Bundle / APK**.
3. Submit at https://developer.amazon.com. Amazon reviews TV apps for remote-control support and a 10-foot layout, so a site that is really built for TV is far more likely to be approved than a plain website wrapper.
