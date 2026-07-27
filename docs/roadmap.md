# Roadmap

Ordered roughly by value per unit of work. Everything here is deliberately out
of the first build, not forgotten.

## 1. Web retrieval - improvements

Search and URL reading **shipped** on 2026-07-27 (`net/WebTools.kt`,
`net/WebAugmenter.kt`), built entirely in-app: no API key, no account, no
self-hosted service. SearXNG and Brave were rejected because both need external
setup.

What is still worth doing:

- **Model-refined search queries.** The query is currently the user's message
  verbatim. Letting the model rewrite it into a good query needs a scratch
  completion that does not append to the chat history - the native layer has no
  such call yet. That is the main quality lever here.
- **Robustness.** DuckDuckGo HTML parsing breaks if their markup changes.
  Failures already degrade to "no results" rather than throwing, but a second
  parser (lite.duckduckgo.com) as fallback would help.
- **Wire retrieval to the fact-checking dial.** Above some threshold, run a
  verification search automatically instead of waiting for the globe button.
- **PDF reading.** `fetch` deliberately refuses non-text content types today.
- **Provider choice**, if the no-external-setup rule is ever relaxed.

Honest note: with web access on, the app is no longer strictly offline. It is
off by default, armed per message, and the fetched URLs are shown on the answer.

## 1a. Voice input cannot be grounded (known gap)

Confirmed on device 2026-07-27: a spoken question about Nikola Tesla came back
with "an American inventor" and no sources attached.

The cause is structural, not a bug. Voice goes into the model **as audio** via
the mmproj encoder, which is why tone survives - but it also means the app never
sees a transcript. Retrieval runs before generation and needs text, so a
voice-only message skips Wikipedia and search entirely.

Options, in increasing cost:

- Require a typed hint alongside voice (weak, defeats the point).
- Run Android's on-device `SpeechRecognizer` **in parallel**, purely to produce a
  retrieval query, while the real audio still goes to the model. The transcript
  never reaches the prompt, so nothing non-verbal is lost. This is the good one.
- A two-pass approach where the model first transcribes its own audio, then the
  app retrieves and re-asks. Doubles latency.

Until this is fixed, the fact-checking dial and Wikipedia grounding only apply
to typed messages. That should probably be surfaced in the UI.

## 1b. Arbitrary Hugging Face models

Requested 2026-07-27. Today `ModelCatalog` is a hardcoded list of two Gemma 4
builds with byte sizes baked in.

Wanted: paste any HF repo id (or repo + filename), have the app query
`https://huggingface.co/api/models/<repo>`, list the `.gguf` files with their
real sizes, and download the chosen one. Pieces already in place: the downloader
is generic over `RemoteFile`, and sizes come from that same API today.

What needs real work:

- **Chat template.** `render_chat` in `pocketllm_jni.cpp` hardcodes Gemma 4's
  `<|turn>role ... <turn|>` format. Another model family needs its own. Either
  detect the architecture from GGUF metadata and switch renderer, or bundle a
  small Jinja subset interpreter (llama.cpp's own
  `llama_chat_apply_template` is not enough - it failed on Gemma 4 precisely
  because it only pattern-matches a fixed list).
- **mmproj pairing.** Multimodal repos ship a matching projector; text-only ones
  do not. The catalog entry has to model "projector optional".
- **Gated repos** need an HF token; `.env.example` already has the placeholder.
- **RAM guardrails** so a 13 GB Q8 download is refused on an 8 GB phone.

## 2. Name change

`PocketLLM` collides with at least four shipping products, including an iOS app
doing almost exactly this. Verified free at the time of checking: `OwnLLM`,
`OnDeviceChat`, `TapLLM`, `ReadyLLM`.

**Still unchecked: the trademark registers.** TMview's API refused the
connection and Justia blocks automated access. Before any Play Store listing,
search DPMA, EUIPO and USPTO by hand. Product existence and trademark
registration are different questions, and only the second one carries legal
weight.

Renaming touches three lines: `settings.gradle.kts` (`rootProject.name`),
`app/build.gradle.kts` (`namespace` + `applicationId`), and
`res/values/strings.xml` (`app_name`). The Kotlin package can stay if desired.

## 3. Foreground service for downloads and model load

Today a 4 GB download lives in `viewModelScope`, and the model load lives in the
Application object. Android can kill either when the app is backgrounded.

A foreground service with a progress notification would make both survivable,
and would let a download continue with the screen off. Needs
`FOREGROUND_SERVICE_DATA_SYNC` and a notification permission prompt on 13+.

## 4. Context window management

Currently a full context returns "context full - start a new chat". That is
honest but blunt.

Better: drop the oldest turns and re-evaluate, or use `llama_memory_seq_rm` to
evict a prefix and shift positions. The chat-template diffing in
`pocketllm_jni.cpp` assumes an append-only transcript, so this is a real change,
not a tweak.

## 5. GPU and CPU acceleration

`nGpuLayers` is hardwired to 0 because the build compiles no GPU backend.

**Done 2026-07-27: dotprod.** The build now sets
`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`. This was not an optimisation, it was
a bug: `GGML_NATIVE=OFF` with no arch set makes ggml-cpu emit no `-march` at
all, so every quantised matmul ran without SDOT. 1.3 → 6.9 tok/s on a Galaxy S23
Ultra. Anything below this line is now a real optimisation on top of a sane
baseline.

- **i8mm.** `+i8mm` roughly doubles quantised matmul again on cores that have
  it (Snapdragon 8 Gen 1 and later). It is an armv8.6 feature and would SIGILL
  on older phones, so it cannot simply be added to `GGML_CPU_ARM_ARCH`. Doing it
  properly means either `GGML_CPU_ALL_VARIANTS` with `GGML_BACKEND_DL=ON` (which
  conflicts with the current single-`.so` packaging) or building two CPU
  backends and picking at runtime off `/proc/cpuinfo`. **Best remaining
  effort-to-speedup ratio.**
- **KleidiAI** (`GGML_CPU_KLEIDIAI=ON`). Arm's own optimised quantised kernels,
  a pure CPU win with no driver risk. Fetches KleidiAI at configure time.
- **OpenCL / Adreno.** `GGML_OPENCL=ON` plus the Adreno kernels. Big win on
  Qualcomm parts, but driver quality varies and it needs real device testing.
- **Vulkan.** More portable, historically slower than OpenCL on Adreno for this
  workload.
- **Thread and core affinity.** `inferenceThreads()` is `cores - 2`; nothing
  pins work to the big cores, so Android is free to schedule decode onto
  little ones.

## 6. Speculative decoding

Both model repos ship an MTP drafter (`mtp-gemma-4-E4B-it.gguf`, 60 MB). Cheap
in storage, and a meaningful token-rate improvement if llama.cpp's speculative
path is wired up on the Android side.

## 7. Chat persistence

Conversations currently die with the process. Room or a JSON file, plus a chat
list, plus export.

## 8. Video input

Explicitly out of scope for v1 and not selected in the original brief.

llama.cpp's mtmd video helper shells out to ffmpeg, which does not exist on
Android, so `MTMD_VIDEO` is off. Doing it properly means sampling frames with
`MediaMetadataRetriever`, feeding them as a bitmap sequence, and transcribing
the audio track separately. Gemma 4 caps video at 60 s at 1 fps.

## 8a. Replacing the audio path with Whisper

Raised because voice felt slow. It was measured first, and the measurement said
otherwise: the app was slow *everywhere* because of the missing `-march` flag
(section 5), text turns included. Whisper would have been slow too.

Still worth revisiting once the CPU work is done, for a different reason: a
transcript is what section 1a needs to ground voice input. But it is a real
cost - `whisper.cpp` as a second submodule, another 150-570 MB model to
download, and the loss of everything non-verbal that the audio encoder
currently preserves. Measure the audio encode share of a voice turn from the
per-turn timing log before committing to it.

## 9. Smaller things

- Tokens-per-second and context-usage readout in the UI (the numbers are
  already logged per turn; they are just not on screen).
- Text selection in message bubbles. `ClickableText` consumes taps, so
  selection and tappable links currently cannot coexist without more work.
- Model-side markdown discipline: the model still emits `![alt](url)` with
  invented URLs, which renders as "[image unavailable]". A system-prompt line
  telling it not to fabricate image links would be cheaper than any renderer
  change.
- Auto-fallback to E2B when an E4B load fails with OOM, rather than showing an
  error. `ModelCatalog.recommendedFor` already exists but nothing calls it on a
  failed load.
- Stop sequences and antiprompt support.
- Release signing config (`keystore.properties` is already gitignored).
