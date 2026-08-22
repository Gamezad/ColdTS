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
- 🔖 **Bookmarks** — save and manage your servers
- 🫧 **Whisper lists**, floating window overlay, auto-reconnect, in-app updater

---

## 📱 Downloads / دانلود

Prebuilt APKs are produced by **GitHub Actions** on every push:

- **Actions → latest successful run → Artifacts → `ColdTs-Client-debug-apk`**
- Tagged releases (`v*`) publish an APK under [Releases](../../releases)

فایل APK از بخش **Actions** (آخرین بیلد موفق → Artifacts) یا از بخش **Releases** برای نسخه‌های تگ‌شده قابل دانلود است.

---

## 🛠 Building from source / کامپایل سورس

The native `tslib` `.so` libraries for all ABIs are committed under `app/src/main/jniLibs`, so no Rust toolchain is required:

```bash
./gradlew :app:assembleDebug -x buildRustLibs
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions builds automatically on push to `main` / build branches and on `v*` tags (see `.github/workflows/android-build.yml`).

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
