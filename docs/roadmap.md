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
- **PDF reading from the web.** `fetch` deliberately refuses non-text content
  types. Attached PDFs are handled (rendered to page images, see
  `media/PdfPages.kt`); a linked one is not, and routing it through the same
  renderer would close the gap.
- **Provider choice**, if the no-external-setup rule is ever relaxed.

Honest note: with web access on, the app is no longer strictly offline. It is
off by default, armed per message, and the fetched URLs are shown on the answer.

## 1a. Voice input cannot be grounded - RESOLVED 2026-07-27

Was: a spoken question about Nikola Tesla came back with "an American inventor"
and no sources, because voice went to the model as audio and the app never saw a
transcript to search with.

Resolved by making the microphone **dictate into the text field** rather than
attach audio. The transcript is text like any other message, so retrieval,
Wikipedia and the fact-checking dial all apply, and it costs a dozen tokens
instead of a few hundred. It is also simply better UX: a misheard question is
now visible and correctable instead of looking like a stupid model.

Audio-to-encoder was not thrown away - it is an explicit attachment now, for
when the sound itself is the subject rather than a way of typing.

What is still open here:

- **Language switching.** The recogniser is handed the device locale. A German
  phone dictating English gets worse results than it needs to.
- **API 29-30 fallback.** `createOnDeviceSpeechRecognizer` is API 31+; below
  that the platform recogniser may route to the network, which quietly makes an
  offline app online. Worth surfacing in the UI when it happens.

## 1b. Arbitrary Hugging Face models - shipped 2026-07-27

Paste a repo id, get a list of its `.gguf` files with real sizes, download one.
`model/HuggingFace.kt` reads the tree endpoint, `model/CustomModels.kt` stores
the result, `ui/ModelPicker.kt` is the dialog.

The hard part was the **chat template**. `render_chat` hardcoded Gemma 4's
`<|turn>role ... <turn|>`, which is exactly the format `llama_chat_apply_template`
cannot produce. Resolved by picking a renderer once at load: the hand-written
one on the `<|turn>` marker, the model's own template through llama.cpp when a
probe render succeeds, ChatML as a stated guess otherwise. No Jinja interpreter
was needed after all - llama.cpp's fixed list covers about sixty families, and
the app only has to notice when it does not.

That surfaced a second thing: incremental prompt evaluation assumed every render
extends the previous one. Several built-in templates fold the system prompt into
the *last* user message, so the transcript shifts every turn. The session now
compares against the previous render and re-evaluates from scratch on a
mismatch.

Still open here:

- **Nothing verifies the template guess.** A model that lands on ChatML gets a
  red warning and no further help. Reading `tokenizer.chat_template` and
  matching a few more families by hand would shrink the guess bucket.
- **Split GGUFs are refused, not joined.** `model-00001-of-00003.gguf` and
  friends are hidden with a note. Joining them means downloading N files and
  either concatenating or passing the first path to llama.cpp, which does
  understand shards natively - the downloader is what does not.
- **RAM guardrails warn, they do not refuse.** A row turns red below the
  estimate and the download still proceeds. That is probably right for a
  borderline case and wrong for a 13 GB Q8 on an 8 GB phone.
- **Context size is one global setting**, so a model that could take 32k and one
  that should stay at 4k share a number. `recommendedContext` is stored per
  model and currently ignored at load.

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

## 8. Video input - shipped and then removed, 2026-07-27

llama.cpp's mtmd video helper shells out to ffmpeg, which does not exist on
Android, so `MTMD_VIDEO` is off and there is no native video path at all.
`VideoPrep` sampled frames with `MediaMetadataRetriever` and handed them to the
vision encoder as an ordered image sequence.

Six frames, because each costs roughly 250 tokens of a 4096-token context that
also has to hold the question and the answer. Gemma 4 nominally accepts a minute
at 1 fps; sixty frames would be ~15,000 tokens, which is not available here.

**Removed the same day it shipped.** Six stills of an entire clip is not video
input, and no amount of labelling makes it one: the model cannot see motion,
cuts, or anything between the samples, so it answers confidently about a clip it
mostly did not see. That is worse than the app not offering video. Deleted in
0.1.6; it is in git history if the context budget ever changes.

What it would take to bring back honestly:

- **A context large enough for real sampling.** At 32k, 1 fps over a short clip
  becomes affordable and the frames start describing motion rather than sampling
  it.
- **Scene-change sampling** instead of even spacing, so the frames land on cuts.
- **The soundtrack**, extracted and fed to the audio encoder - for most clips
  that carries more of the content than any six frames do.

## 8a. Whisper - not needed, twice over

Raised because voice felt slow. Measured first, and the measurement said
otherwise: the app was slow *everywhere* because of the missing `-march` flag
(section 5), text turns included. Whisper would have been slow too.

The second reason it was wanted - a transcript to ground voice input - is
solved by the platform recogniser (section 1a) at zero download cost.

That leaves no open problem for `whisper.cpp` to fix here. Revisit only if
on-device recognition turns out to be materially worse than Whisper for the
languages in use, and weigh it against a 150-570 MB second model.

## 8b. Documents - shipped 2026-07-27, with edges

Text formats and Office files go into the prompt; PDFs go to the vision encoder
as page images. See the README for why they differ.

Open:

- **Only the first sheet of an .xlsx is read.** A workbook with a dozen tabs
  loses eleven of them silently. Needs a sheet picker, or at least a note.
- **Five PDF pages** is a context limit, not a design one. It should scale with
  the context-size setting the way the frame count should have.
- **No .pptx.** Same zip-of-XML trick would work on `ppt/slides/slideN.xml`.
- **Scanned PDFs depend on the model's OCR.** Gemma 4 reads clean scans well and
  handwriting poorly; there is no fallback when it misreads.
- **A second document replaces nothing** - attach three and all three go in,
  which will overflow the window before the app says anything useful.

## 9. Smaller things

- ~~Tokens-per-second and context-usage readout in the UI~~ - shipped 0.3.1,
  behind a setting. What is still missing is what to *do* when the bar fills:
  see section 4.
- Regenerate / edit-and-resend a turn. `nativeRollbackTurn` already provides
  the hard part; it just has no button.
- Model-side markdown discipline: the model still emits `![alt](url)` with
  invented URLs, which renders as "[image unavailable]". A system-prompt line
  telling it not to fabricate image links would be cheaper than any renderer
  change.
- Auto-fallback to E2B when an E4B load fails with OOM, rather than showing an
  error. `ModelCatalog.recommendedFor` already exists but nothing calls it on a
  failed load.
- Stop sequences and antiprompt support.
- Release signing config (`keystore.properties` is already gitignored).
