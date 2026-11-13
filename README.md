# ❄ ColdTs Client

**ColdTs Client** is a free, lightweight TeamSpeak 3/6 client for Android built with Jetpack Compose, powered by the Rust `tslib` engine. It is based on the open-source [TS6_Droid](https://github.com/flamme-demon/TS6_Droid) project by flammedev and its community derivatives.

**کلاینت ColdTs** یک کلاینت آزاد و سبک تیم‌اسپیک ۳/۶ برای اندروید است که با Jetpack Compose ساخته شده و موتور آن کتابخانه Rust به نام `tslib` است. این پروژه بر پایه پروژه متن‌باز TS6_Droid توسعه یافته است.

---

## ✨ Features / ویژگی‌ها

- 🌐 **English + فارسی (and 简体中文 / Français)** — the UI follows your **phone's language** automatically, with an in-app override
- 🧊 **Icy blue Material 3 theme** — frost-blue palette for light & dark mode, snowflake launcher icon
- 🖼 **Custom wallpaper** — pick any image from your phone as the background, with an adjustable transparency slider (no images are downloaded automatically)
- 👑 **Server & channel groups** — group chips in client info, Server Admin / Channel Admin badges in the user list
- 🗣 **Voice chat** — voice-activity & push-to-talk, mic/speaker mute, audio gain, noise suppression
- 🌳 **Full channel tree** — tap to switch channels, long-press for channel info
- 👤 **Client info** — tap a user for the classic TeamSpeak client details (unique ID, groups, platform, talk power…) with quick actions: private chat, local mute, whisper
- 🖥 **Server info** — name, version, uptime, clients online, welcome message
- 💬 **Chat** — channel + private messages, file attachments, image previews, video link thumbnails
- 📁 **File browser** — upload / download / rename / delete / create folders
- 🔖 **Bookmarks** — save and manage your servers (ColdGame `ts.coldgame.ir` comes preloaded) with a remembered default nickname
- 💛 **Friends & text whisper** — mark friends (persisted), send private text to whisper lists
- 🫧 Floating window overlay, auto-reconnect, in-app updater

Voice whisper is not wired up in the bundled native library. While text whisper is active, ordinary channel voice is paused to avoid broadcasting it by mistake; the microphone stays muted when whisper ends.

---

## 📱 Downloads / دانلود

- Debug builds from pull requests, pushes to `main` / `master`, and manual runs are available under **Actions → successful run → Artifacts → `TS6-Han-Android-App`**.
- Once Android signing secrets are configured, a version tag such as `v1.0.2` builds a signed, non-debuggable `ColdTs-Client-v1.0.2.apk` and attaches it directly to [Releases](../../releases). Publishing a GitHub release for such a tag also triggers the build. Tags must point to a commit containing this release workflow.

فایل آزمایشی از بخش **Actions → Artifacts** و نسخهٔ امضاشده پس از تنظیم کلید اندروید و ساخت تگ نسخه از بخش **Releases** قابل دانلود است.

---

## 🛠 Building from source / کامپایل سورس

Prebuilt `tslib` libraries for `arm64-v8a` and `x86_64` are committed under `app/src/main/jniLibs`, so building with them does not require a Rust toolchain:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug -x buildRustLibs
```

The checked-in `x86_64` native library is older and does not export the file-management or channel-permission APIs provided by the `arm64-v8a` library. Those features need matching native libraries rebuilt from a compatible `tslib` source before they can work on that ABI.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Publishing signed APK releases

1. Use the **same Android signing key for every version**. If an earlier APK was distributed, use its original keystore; a different key cannot update that installation. If you need a new key, create one locally and keep a secure backup, for example:

   ```bash
   keytool -genkeypair -keystore coldts-release.jks -storetype PKCS12 \
     -alias coldts-release -keyalg RSA -keysize 4096 -validity 10000
   ```

   `keytool` prompts for the passwords. Never commit the keystore or share passwords in chat. Losing the key or changing it prevents in-place updates. Debug APKs from CI may have different, temporary signatures and may require uninstalling the old app before installing a release (back up app data first).

2. In **GitHub → Settings → Secrets and variables → Actions**, add these repository secrets:

   | Secret | Value |
   | --- | --- |
   | `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the entire keystore file |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
   | `ANDROID_KEY_ALIAS` | Signing-key alias |
   | `ANDROID_KEY_PASSWORD` | Signing-key password |

   For example, with GitHub CLI on your own machine, the file can be passed without printing it with `base64 < coldts-release.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 -R Gamezad/ColdTS`. The remaining `gh secret set NAME -R Gamezad/ColdTS` commands prompt for their values interactively. Repository Actions must be allowed to write repository contents for the publish job.

3. After the changes are merged, push a new tag pointing at a commit that contains them (or publish a GitHub release for that tag):

   ```bash
   git tag v1.0.2
   git push origin v1.0.2
   ```

   Tags must be `vMAJOR.MINOR.PATCH` with no suffix or leading zeroes; minor and patch are at most 999, major at most 2099, and `v0.0.0` is not valid. CI derives a monotonically increasing Android `versionCode` as `major * 1000000 + minor * 1000 + patch`, sets `versionName` from the tag, verifies the signature and metadata, then uploads the APK. Missing or invalid signing secrets fail the release build instead of publishing an unsigned or temporarily signed APK.

   If a release already exists, the APK is attached without changing its notes or publishing an existing draft. Existing assets with the same name are left unchanged. Previous tags and releases are not backfilled automatically; to rebuild a release, its tag must include the new workflow and signing configuration.

---

## 🌍 Language / زبان

The app language follows the **system (phone) language** by default: English for `en`, **فارسی** for `fa`, 简体中文 for `zh`, Français for `fr`. You can also override it manually in **Settings → Language** (a manual choice restarts the app).

زبان برنامه به‌صورت پیش‌فرض از **زبان گوشی** پیروی می‌کند؛ فارسی، انگلیسی، چینی و فرانسوی پشتیبانی می‌شوند و می‌توانید از «تنظیمات → زبان» زبان را دستی هم عوض کنید.

---

## 🧾 Credits & License

- Original project: [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid) (Grégory D / flammedev)
- Community derivative: [YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN)

This project is licensed under the **GNU GPLv3** — see [LICENSE](LICENSE). Derivatives must remain fully open-source under GPLv3 with attribution retained.

این پروژه تحت مجوز متن‌باز GNU GPLv3 منتشر می‌شود؛ هر نسخه مشتق‌شده باید کاملاً متن‌باز بماند و نام سازندگان اصلی حفظ شود.

⚠️ This app is completely **free**. If you paid for it, you were scammed — request a refund. / این برنامه کاملاً **رایگان** است؛ اگر بابت آن پول داده‌اید کلاهبرداری شده‌اید.
