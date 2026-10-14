# Melodia

Write lyrics, pick a style and a voice, and Melodia sings them as a real song (MP3), without a cloud service, an account or per-song fees.

Songs are made by **ACE-Step 1.5**, an open-source music model, running through **acestep.cpp**:

- **Phone AI**: runs on the phone itself, fully offline after a one-time download (about 2.5 GB for the Lite pack, plus about 2 GB for the recommended songwriter). Needs a 64-bit phone with 8 GB+ RAM; 12 GB+ is smoother. It is slow: about 5 minutes for a 30-second preview and roughly 9 minutes per minute of song (measured on phone-class ARM cores).
- **PC AI**: the same engine on your computer, reached over your home Wi-Fi. Much faster, and it can use the bigger models.
- **Termux engine**: the phone engine run inside the Termux app, for builds that don't include it.

## Install

1. On your phone, open this repository's **Releases** page (or `https://github.com/<you>/<repo>/releases/latest`).
2. Tap **Melodia.apk**, then open the download. Allow "install unknown apps" for your browser when Android asks.
3. Open Melodia and follow the welcome screen: **Set up Phone AI** downloads the models, or **Use my PC** explains the computer setup.

Every push to `main` builds a new APK automatically (GitHub Actions). New versions install over old ones and keep your songs.

## Using it

- **Create**: Custom mode for your own lyrics (tags like `[Verse]` and `[Chorus]` on their own lines), or Simple mode to describe a song and let the Songwriter brain write it. "Write for me" drafts lyrics in your chosen style and language; the songwriter model often ignores a specific topic, so for lyrics about something specific, write your own.
- **Songwriter brain** (recommended): plans each song before it is sung, so the singer follows your lyrics closely and in order.
- **Voice**: choose or design a voice: singer, range, tone, delivery, emotion, vibrato, techniques, singing style, mic sound, per-section delivery (soft verses, powerful chorus…), plus optional "sound like me" reference recording and LoRA voice adapters.
- **Pronunciation fixes**: if a word comes out wrong, add a respelling (e.g. `Dhaka → Dhaa-kaa`). Only the singer sees it.
- **Library**: play, save to Music, share, retry, make again, reuse settings.

## How it works

```
Compose UI ──► GenerationService (foreground) ──► AceClient (HTTP)
                                                   ├─ Phone AI: libaceserver.so started by the app on 127.0.0.1
                                                   ├─ Termux:   ace-server in Termux on 127.0.0.1:8085
                                                   └─ PC AI:    ace-server on your computer (LAN)
```

- `PromptBuilder` turns the Create screen and Voice Designer into an ACE-Step request: a style caption, tagged lyrics, language and metadata. It is pure Kotlin and unit-tested.
- The engine API is asynchronous: `POST /synth` (and optionally `POST /lm` for the songwriter), poll `GET /job?id=`, download the multipart MP3 result.
- The on-phone engine is the `ace-server` executable cross-compiled with the NDK by `native/build_android.sh` and shipped as `lib/arm64-v8a/libaceserver.so`. Models are downloaded with Android's DownloadManager from [Serveurperso/ACE-Step-1.5-GGUF](https://huggingface.co/Serveurperso/ACE-Step-1.5-GGUF).

## What has been tested

Every push runs two checks against a real ACE-Step engine on GitHub's machines (see `.github/workflows/check.yml`):

- **Engine check**: the app's own request builder and engine client make real songs (custom lyrics, two versions at once, songwriter plan, Simple mode, "Write for me" in English and Bangla, voice reference recording).
- **App check**: the APK runs on an Android emulator, connects to the engine as "PC AI", makes a 30-second song from typed lyrics, plays it, saves it to Music, and opens the other screens. Screenshots and logs go to the `ci-check-app` branch.
- Songs are checked with a speech recognizer. With the 1.7B songwriter planning the song, it heard 41 of 42 lyric words in a 60-second English song, and 22 of 27 in a Bangla song written in Bengali script.

## Build it yourself

Requirements: JDK 17, Android SDK (API 35), Android NDK, CMake.

```
bash native/build_android.sh        # builds the on-phone engine (optional)
./gradlew :app:assembleRelease      # APK in app/build/outputs/apk/release/
```

The repository contains a signing key (`app/melodia-release.jks`) so that every build can update the previous one. Replace it before distributing the app publicly.

## Limits

- On-phone generation is slow (minutes per song) and makes the phone warm. Keep it plugged in for long songs.
- Pronunciation quality depends on the language; widely sung languages work best. Bangla works, best when lyrics are written in Bengali script. Try a 30-second preview first, and use Pronunciation fixes for words that come out wrong.
- The songwriter writes in the style and language you ask for but rarely follows a specific topic.
- Android 12+ may stop long background work on some phones. Melodia keeps the screen on while a song is being made (can be turned off in Settings).
- The engine has no password. Only use PC AI on your own network (or through a private network like Tailscale).

## Credits and licences

- ACE-Step 1.5, MIT, by ACE Studio and StepFun: https://github.com/ace-step/ACE-Step-1.5
- acestep.cpp, MIT: https://github.com/ServeurpersoCom/acestep.cpp
- GGML, MIT: https://github.com/ggml-org/ggml
- AndroidX, Jetpack Compose, Media3, OkHttp, kotlinx: Apache 2.0

Make original music, don't imitate real singers without permission, and say a song was made with AI when you share it.
