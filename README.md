# PocketLLM

An Android chat app that opens straight into a conversation with a local Gemma 4
model. No sign-in, no web page, no model picker, no "load model" step - the
weights start loading the moment the app opens, and the chat is the only screen.

Everything runs on the phone. The network is used for the one-time model
download, and after that only if you switch web access on.

> **Name is provisional.** `PocketLLM` collides with several existing products
> (an iOS app, a Flutter project, a ThirdAI desktop tool). A rename is planned;
> see [docs/roadmap.md](docs/roadmap.md). It touches three lines - see
> `settings.gradle.kts`.

## What it does

- **Auto-loads on launch.** The configured model begins loading from the view
  model's constructor. There is no confirmation step.
- **Images in.** Attach a photo; the vision encoder feeds it to the model. The
  pending attachment is shown as a thumbnail, not a label.
- **Dictation.** The mic transcribes into the text field, where you can read and
  fix it before sending. Not audio-to-model: a spoken sentence costs a few
  hundred audio tokens out of 4096 where its transcript costs a dozen, a
  misheard question is visible instead of looking like a stupid model, and
  retrieval has something to search with.
- **Audio and video in.** The `+` menu attaches an image, an audio file - which
  *does* go to the model's audio encoder directly, for when the sound itself is
  the subject - or a video, which is sampled into frames.
- **Two dials**, creativity and fact-checking, described below, plus an
  editable system prompt for when the dials are not enough.
- **Web access, off by default.** Turn it on and a link in your message gets
  fetched and read, Wikipedia is consulted for questions about the world, and
  the globe button adds a DuckDuckGo search. The URLs actually read are listed
  under the answer.
- **Markdown answers.** Headings, lists, tables, code blocks, and links that
  open when tapped. Images the model emits as `![alt](url)` are fetched and
  displayed, or fall back to their alt text when the URL turns out to be
  invented. Long press selects text, double tap copies the whole message.
- **Resumable downloads.** A 4 GB fetch over mobile data will get interrupted;
  it resumes from where it stopped.

## The two settings

Both live in the settings sheet behind the gear icon.

| Dial | What it changes |
|---|---|
| **Creativity** | Sampling. Higher raises temperature, widens the nucleus, relaxes the repetition penalty. |
| **Fact-checking** | Grounding. Higher rewrites the system prompt to forbid invented specifics and to prefer "I don't know" over a guess. |

They pull against each other, so the interaction is explicit rather than
accidental: **fact-checking caps creativity.** At maximum fact-checking,
creativity can only reach a quarter of its range. Without that cap, sliding
creativity to 100 would silently defeat the fact-checking setting. The settings
sheet says so when the cap is active.

Mapping and exact values: `llm/GenerationParams.kt`, verified by `DialsTest`.

The settings sheet also holds a **system prompt** field. Leave it blank and the
prompt is generated from the dials; write something and it is used verbatim,
with the dials then affecting sampling only.

## Web access

Off by default. With it off, a conversation touches the network never.

With it on:

- A `http(s)://` link in your message is fetched, stripped to text and fed in as
  the primary source. Bare domains are ignored on purpose - "I like example.com"
  is prose, not an instruction.
- **Wikipedia** is consulted for questions that are actually about the world,
  and ranked above everything else. It gets its own path rather than going
  through search because it offers a real keyless JSON API: one curated, citable
  article beats five snippets from arbitrary sites. English is tried first
  because those articles are longer and better cited; the model is told to
  answer in whatever language you asked in.
- The globe button turns on **DuckDuckGo** search. It stays on until you turn it
  off. Top results plus the full text of the first hit go in as context.
- Asking to *see* something ("show me a picture of X") pulls the article's lead
  image and displays it under the answer, and tells the model not to claim it
  cannot show pictures.
- A question turning on a year or on "latest / aktuell / wer hat gewonnen"
  searches **without waiting for the globe button**. Those cannot be answered
  from weights at all.
- If the answer still comes back as "I don't know", the turn is rolled back and
  asked again with search results. The rollback matters: a retry stacked
  underneath the model's own refusal tends to get agreed with.

The Wikipedia setting means what it says: with it on, the app tries to look
things up; with it off it never does. The only messages skipped are ones no
article could answer - "write me a poem", "count these", "translate this".

Relevance is enforced on what comes *back*, not by guessing in advance which
questions deserve an article. Wikipedia's search endpoint never says "no match",
it always returns its best guess, so a returned article whose title shares no
word with the query is thrown away. That is a better filter than trying to
predict the result, and it is why "count people" no longer drags an unrelated
article into the prompt.

No API key, account or self-hosted service: it uses DuckDuckGo's plain-HTML
endpoint and a hand-rolled HTML-to-text pass. The trade-off is that scraping can
break if their markup changes; failures degrade to "no results" and the model
answers without web context rather than erroring.

Retrieval is **deterministic, not model-driven**. The decision to fetch happens
before generation starts, so a heavily quantized 4B model is never asked to emit
and parse its own tool calls mid-answer. The cost is that the search query is
your message verbatim rather than a model-refined one - see the roadmap.

Retrieved text is capped at roughly 1000 tokens, because the 4096-token context
also has to hold the conversation and the answer.

## Model

Default is **Gemma 4 E4B (mobile QAT)** from Unsloth. Both repos are ungated, so
no Hugging Face token is needed.

| File | Size |
|---|---|
| `gemma-4-E4B-it-qat-UD-Q2_K_XL.gguf` | 3.22 GB |
| `mmproj-F16.gguf` (vision + audio encoders) | 990 MB |

`Gemma 4 E2B` is offered as the lighter fallback (2.19 GB + 986 MB).

The projector is a **separate, deferred load**: it costs close to a gigabyte of
RAM and a text-only conversation never needs it, so it is only mounted the first
time you attach an image or record audio.

Sizes in `ModelCatalog.kt` are the real byte counts from the Hugging Face API.
A file is only treated as present at its exact expected size, so a truncated
download surfaces immediately instead of as an unreadable-GGUF crash later.

## Requirements

- A phone with **8 GB RAM** (E4B needs roughly 5 GB free with the projector
  resident) and about **4.5 GB free storage**. On 6 GB devices, pick E2B.
- arm64 only. Every device that can hold a 3 GB model is arm64, and shipping one
  ABI keeps the APK from doubling.
- Android 10 (API 29) or newer.

## Building

```powershell
git clone --recursive git@github.com:pg0/pocketllm.git
cd pocketllm
```

If you cloned without `--recursive`:

```powershell
git submodule update --init --recursive
```

Then:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The first build compiles all of ggml, llama and mtmd for arm64 and takes a
while. Gradle downloads NDK r27 and CMake automatically; nothing needs to be
installed by hand beyond Android Studio.

Unit tests (no device or NDK build needed):

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Install:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## How it is put together

```
app/src/main/cpp/
  CMakeLists.txt        pulls in third_party/llama.cpp as a subproject
  pocketllm_jni.cpp     the whole native surface: load, chat, multimodal, stream

app/src/main/kotlin/com/redcoralstudios/pocketllm/
  llm/LlamaBridge.kt        1:1 JNI declarations, no policy
  llm/LlmEngine.kt          session lifecycle, single worker thread, streaming
  llm/GenerationParams.kt   the two dials -> sampler values + system prompt
  model/ModelCatalog.kt     known models, real HF sizes
  model/ModelDownloader.kt  resumable range-request download
  model/ModelStore.kt       paths, completeness, RAM/thread heuristics
  media/ImagePrep.kt        downscale + EXIF rotate before the vision encoder
  media/AudioRecorder.kt    16 kHz mono WAV for the audio encoder
  net/WebTools.kt           search, page fetch, Wikipedia, HTML-to-text
  net/WebAugmenter.kt       builds the retrieval block prepended to a turn
  ui/Markdown.kt            block + inline markdown renderer
  ui/RemoteImage.kt         disk-cached image loading, no image library
  ui/                       Compose chat + settings sheet
```

Notes worth knowing before changing anything:

- **llama.cpp contexts are not thread-safe.** Every native call for a session
  goes through one dedicated thread. `nativeCancel` is the documented exception.
- **The engine is application-scoped**, not activity-scoped, so rotating the
  phone does not restart a twenty-second model load.
- **Gemma 4's chat template cannot go through `llama_chat_apply_template`.**
  That function does not parse Jinja - it pattern-matches the template string
  against a fixed list of known formats. Gemma 4 ships an 18 KB Jinja document
  using `<|turn>role … <turn|>`, which matches nothing, so the call returns -1.
  Forcing it onto llama.cpp's older `"gemma"` entry is worse than the error:
  that emits `<start_of_turn>`, which Gemma 4 does not use at all, and the
  result is a silently wrong prompt rather than a loud failure. `render_chat`
  writes the format by hand, verified against the template in the GGUF.
- **Gemma 4 *does* have a real `system` role** - Gemma 3 did not, and code
  written for Gemma 3 folds the system prompt into the first user turn.
- **`GGML_NATIVE=OFF` alone leaves a 5x slowdown on the table.** With it off and
  neither `GGML_CPU_ARM_ARCH` nor `GGML_CPU_ALL_VARIANTS` set, ggml-cpu appends
  no `-march` at all and inherits the NDK's baseline `armv8-a`, which has no
  dot-product instruction. Measured on a Galaxy S23 Ultra: 1.3 tok/s decode and
  a 44 s prefill. `armv8.2-a+dotprod+fp16` took the same prompt to 6.9 tok/s
  with a 12 s prefill. `i8mm` would give more but is armv8.6 and would crash
  pre-2021 devices, so it needs runtime dispatch first.
- **`buildConfig` stays off.** Enabling it generates `BuildConfig.java`, which
  forces javac to run, which trips AGP 8.1.4's `JdkImageTransform` under the
  JDK 21 bundled with Android Studio. The version string is read from
  `PackageManager` instead.
- **The model has no clock.** Without being told the date it treats "now" as
  wherever its training data ended, and calls a 2026 event upcoming with
  complete confidence. No fact-checking prompt catches this, because the model
  has no way to know it is wrong. Every system prompt carries the date.
- **Instruction-tuned models refuse retrieved context.** "I am a large language
  model and cannot provide real-time updates" fires even with a `<web_context>`
  block containing the answer sitting in the same prompt. The retrieval
  instructions live in the user turn, which loses to a habit that strong, so the
  correction has to be in the system prompt - and is restated next to the
  evidence for good measure.
- **There is no native video path.** `MTMD_VIDEO` is off because llama.cpp's
  helper shells out to ffmpeg. Video is sampled into six frames, and the UI says
  so rather than implying the model watched the clip.
- **Only the new suffix is ever tokenized.** The full transcript is re-rendered
  through the chat template each turn, then diffed against what is already in
  the KV cache - the standard llama.cpp approach.
- **`llama_sampler_sample` accepts the token internally.** Do not call
  `llama_sampler_accept` after it or repeat penalties are counted twice.
- **Token pieces split UTF-8 characters.** Partial byte sequences are held back
  until complete, otherwise streaming emits mojibake.

## What is not in here yet

Web search, URL reading and a few other things are deliberately deferred - see
[docs/roadmap.md](docs/roadmap.md).

## Licence

The app is Red Coral Studios GmbH. `third_party/llama.cpp` is MIT. Gemma 4
weights are Apache 2.0 and are downloaded at runtime, never bundled.
