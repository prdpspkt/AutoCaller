# AI Call Responder (Android)

An Android app that **auto-answers incoming cellular calls**, greets the caller with a
**pre-recorded clip or text-to-speech**, transcribes what the caller says with on-device
**speech recognition**, sends it to **DeepSeek**, and **speaks the reply back** — all over the
phone's loudspeaker.

> **Architecture chosen:** cellular + speakerphone, hybrid speech stack (on-device STT/TTS,
> DeepSeek for the replies). See the honest limitations below before you rely on this.

---

## ⚠️ Read this first — real limitations

This uses the only approach that works on a **non-rooted, stock** phone: it turns on the
loudspeaker, plays audio out of it (the caller hears it through the phone's mic), and listens
through that same mic. Consequences you cannot fully engineer away:

- **Echo & feedback** — the mic hears the TTS coming out of the speaker.
- **Half-duplex** — it either talks or listens, never both. Turn-taking feels slow.
- **Fragile capture** — capturing the caller means picking their voice back off the loudspeaker
  through the mic. The on-device Whisper path uses `AudioRecord` directly (with AEC/NS), which is
  more robust than `SpeechRecognizer` during a call, but it's still an acoustic loop: echo of our
  own TTS and background noise degrade transcription.
- **No true in-call audio — even as Device Owner** — stock Android forbids apps from tapping the
  call's audio stream or injecting into the uplink directly. This needs the signature/privileged
  permission `CAPTURE_AUDIO_OUTPUT`, which Device Owner **cannot** grant (it only grants *runtime*
  permissions). Unlocking real in-call audio requires a system/privileged build or the **VoIP**
  approach (a SIP/telephony provider) instead of the cellular radio.
- **Hang-up** — supported: with `ANSWER_PHONE_CALLS` granted, the app ends the call via
  `TelecomManager.endCall()` (API 28+) when the assistant decides to stop.

Treat this as a working proof-of-concept of the speakerphone approach, not a production robocall
system.

## ⚖️ Legal

Auto-answering and processing call audio may require **consent from the caller** (one-/two-party
consent laws vary by country and US state). You are responsible for complying. Don't ship this
without appropriate disclosure.

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/aicallresponder/
│   ├── MainActivity.kt          # Settings UI: API key, model, language, greeting, persona, switch
│   ├── Prefs.kt                 # SharedPreferences wrapper
│   ├── CallReceiver.kt          # PHONE_STATE broadcast -> starts the service on RINGING
│   ├── CallHandlerService.kt    # Foreground service: answers, forces speaker, runs the loop, hangs up
│   ├── ConversationManager.kt   # greeting -> listen -> DeepSeek -> speak -> repeat
│   ├── SpeechEngine.kt          # Output: TextToSpeech + MediaPlayer (language-aware)
│   ├── SttEngine.kt             # Speech-to-text interface
│   ├── GoogleSttEngine.kt       # STT via Android SpeechRecognizer (online fallback)
│   ├── WhisperSttEngine.kt      # On-device STT: AudioRecord capture + endpointing + whisper.cpp
│   ├── WhisperNative.kt         # JNI bridge to libwhisperjni
│   ├── DeepSeekClient.kt        # DeepSeek chat-completions client (java.net, OpenAI-compatible)
│   ├── DeviceOwnerManager.kt    # Detects Device Owner; silently grants runtime permissions
│   ├── AiDeviceAdminReceiver.kt # Device admin component (target for `dpm set-device-owner`)
│   └── BootReceiver.kt          # Re-grants permissions on boot when armed
├── cpp/                         # Native Whisper
│   ├── CMakeLists.txt           # Links whisper_jni against the whisper.cpp submodule
│   ├── whisper_jni.cpp          # JNI: initContext / transcribe / freeContext
│   └── whisper.cpp/             # git submodule (you add this — see below)
└── res/…                        # layout, theme, strings, launcher icon, xml/device_admin.xml
```

## Language (Nepali + others)

The **Language** field takes a BCP-47 tag; it defaults to **`ne-NP` (Nepali)**. It drives both
speech recognition and text-to-speech, and the default persona instructs DeepSeek to reply in Nepali
(falling back to the caller's language if they clearly use another). DeepSeek handles Nepali text
natively.

**Speech-to-text** is pluggable via the **STT engine** field:
- `whisper` *(default)* — fully on-device via whisper.cpp (see setup below). Requires the model
  path to be set; if it's blank the app falls back to Google.
- `google` — Android's `SpeechRecognizer`; supports `ne-NP` **online**, no setup, but may be starved
  of audio mid-call on some OEMs.

⚠️ **On-device Nepali TTS is a separate piece.** Not every device ships a Nepali voice. If the voice
data is missing, `SpeechEngine` logs a warning and falls back to the default voice (which will
mispronounce Devanagari). Install a Nepali-capable TTS engine/voice and select it in
**Settings → System → Languages & input → Text-to-speech output**.

## On-device Whisper (Nepali) setup

The Nepali STT runs [whisper.cpp](https://github.com/ggml-org/whisper.cpp) natively. Three one-time
steps:

**1. Add the whisper.cpp sources** (a git submodule the CMake build links against):
```bash
cd AndroidPhoneApp
git init            # if not already a repo
git submodule add https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp
```

**2. Install the NDK + CMake** in Android Studio: *SDK Manager → SDK Tools →* check **NDK (Side by
side)** and **CMake**. The Gradle config already declares `externalNativeBuild` and targets
`arm64-v8a`.

**3. Get a ggml model — do this inside the app.** No model is bundled in the APK. Open the app →
**Manage / download Whisper models**. Pick one (tiny → large-v3), tap **Download** (a foreground,
resumable download into the app's own storage with a progress notification), then **Use this model**
to activate it — that sets the STT engine to `whisper` and the model path for you. The screen shows
free space and the active model, and you can **Delete** models to reclaim space while experimenting.
   - **large-v3** = best accuracy but heavy (~3.1 GB, slowest per reply on a phone).
   - **large-v3-turbo (q5_0)** ≈ best practical balance (~574 MB).
   - **small** = a lighter balanced option (~466 MB).

   *Advanced:* to use a custom/fine-tuned model (e.g. converting
   `amitpant7/Nepali-Automatic-Speech-Recognition` to ggml via whisper.cpp's
   `models/convert-h5-to-ggml.py`, optionally quantized), `adb push` the `.bin` into the app's files
   dir and set **Whisper model path** manually.

**Performance note:** `small` is the accuracy/size sweet spot but is heavy on a phone (expect a few
seconds per utterance on mid-range hardware). Use `base`/quantized for lower latency. Because we
capture the mic with `AudioRecord` (not `SpeechRecognizer`), this path is also more likely to keep
working while the call is active — but it's still the speakerphone acoustic loop, so echo and
turn-taking limits remain.

## Device Owner provisioning

Being **Device Owner** lets the app grant its own runtime permissions silently and (re)arm on
boot — ideal for a dedicated appliance phone. It does **not** grant in-call audio access (see
limitations).

Provision on a **factory-reset device with no accounts added yet**:

```bash
adb install app-debug.apk
adb shell dpm set-device-owner com.example.aicallresponder/.AiDeviceAdminReceiver
```

If it fails with "not allowed to set the device owner", the device already has an account or
existing owner — factory reset and try again before signing into anything. Once set, open the app
and tap **Grant permissions**: it grants silently, no dialogs. To remove ownership later:
`adb shell dpm remove-active-admin com.example.aicallresponder/.AiDeviceAdminReceiver`.

## Build & run

1. **Open in Android Studio** (Hedgehog or newer). Let it sync Gradle. Android Studio will
   generate `local.properties` (your SDK path) and the Gradle wrapper JAR automatically.
   - Building from the command line instead? First generate the wrapper once with a local Gradle:
     `gradle wrapper --gradle-version 8.13`, then `./gradlew assembleDebug`.
2. **Install on a real device.** Auto-answering does **not** work on the emulator — you need a
   real SIM and real incoming calls.
3. **In the app:**
   - Tap **Grant permissions** and allow Phone, Microphone, and Notifications. On Android's dialog
     you must specifically allow **"Answer phone calls"** (it may be under a "Phone" group).
   - Paste your **DeepSeek API key** (`sk-...`). Get one at <https://platform.deepseek.com>.
   - Optionally edit the **model**, **greeting**, **persona**, and a **pre-recorded greeting path**.
   - Tap **Save settings**, then flip **Auto-answer incoming calls** ON.
4. **Call the phone from another number.** It should auto-answer, switch to speaker, greet, and
   start the conversation.

### Pre-recorded greeting
Leave the audio-path field blank to use TTS. To use a clip, put an `.mp3`/`.wav`/`.m4a` on the
device and enter its absolute path (e.g. `/storage/emulated/0/Download/greeting.mp3`). Reading
from shared storage may need the "Files and media" permission depending on your Android version;
the simplest reliable location is the app's own files dir.

## Configuration defaults

| Setting | Default | Where |
|---|---|---|
| Model | `deepseek-v4-flash` (low latency) | `Prefs.DEFAULT_MODEL` |
| Greeting | "Hello, you've reached an automated assistant…" | `Prefs.DEFAULT_GREETING` |
| Persona | Short, phone-appropriate assistant | `Prefs.DEFAULT_SYSTEM` |
| Max silence before giving up | 3 rounds | `ConversationManager.MAX_SILENCE` |

## Security note

The DeepSeek API key is stored on-device in SharedPreferences and called directly from the phone.
That's fine for personal use, but a key inside a distributed APK **can be extracted**. For anything
public, route requests through your own backend that holds the key.

## Where to go next (the robust path)

If caller capture proves too flaky on your device, switch to the **VoIP** design: point a
SIP/telephony number (Twilio, Telnyx, etc.) at a small backend that streams audio through
STT → DeepSeek → TTS with full-duplex control. The Android app then becomes a thin softphone/config
client. Ask and I can scaffold that variant.
