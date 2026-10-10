# Reshet Patcher

Removes the ads from the Reshet 13+ Android app (`com.applicaster.iReshet`).

## Features

- No video ads in VOD or live: prerolls, midrolls, and the server-side (G-Mana) ads stitched into the live stream
- No interstitial, app-open, rewarded or banner ads
- No web ads (GPT, AdSense, Taboola) inside the app's WebViews
- Works on both versions of the app: the new player app and the older web-based one
- Patches apk, apkm and xapk files, and finds obfuscated classes at patch time, so it should survive app updates

During a live commercial break you will see the channel's own TV commercials, exactly as on TV.

## Requirements

1. Python 3.11+
2. JDK 17+
3. Android SDK (`ANDROID_HOME`, or `sdk.dir` in `smali_generator/local.properties`)

## Installation

```bash
git clone git@github.com:Schwartzblat/ReshetPatcher.git
cd ReshetPatcher
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Usage

```bash
.venv/bin/python main.py -p ./reshet.xapk -o ./output.xapk

unzip -o output.xapk -d out && adb install-multiple out/*.apk
```

Use `--arch x86_64` for an emulator (the default is `arm64-v8a`). To sign with your own key, set the
variables from `.env.example`. Patch logs on the device: `adb logcat -s PATCH ArtHooks`.

## Disclaimer

For educational purpose only or something like that. I am not responsible for any misuse of this software.
