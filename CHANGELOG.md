# Changelog

## 2026-07-27 — 0.1.5

- ui — message text is selectable (long press) and double tap copies the whole message
- ui — replaced the deprecated `ClickableText` with `Text` plus an explicit tap detector. Necessary, not cosmetic: Compose delivers taps to the innermost hit node first and the text's own detector consumes them, so a double-tap handler on the enclosing bubble only ever fired on the padding. Single tap opens a link, double tap copies, long press is left unhandled so `SelectionContainer` can start a selection

## 2026-07-27 — 0.1.4

- net — the Wikipedia toggle now means what it says: on = try to look it up, off = never. The gate is a narrow exclusion (work meant for the model: write, translate, count, summarise) rather than a guess at which questions deserve an article. Relevance is enforced on what comes back via the title check, which is a better filter than predicting what will come back
- tests — `looksFactual` replaced by `worthLookingUp`

## 2026-07-27 — 0.1.3

- llm — **the model now knows what day it is.** A local model has no clock, only a training cutoff, so it called a 2026 event upcoming with complete confidence. No fact-checking prompt can catch that, because the model has no way to know it is wrong. Every system prompt now carries the date, a custom one included
- llm — FIX the model refusing its own retrieved context: "I am a large language model and can't provide real-time updates" fired even with a `<web_context>` block containing the answer in the same prompt. Countered in the system prompt and restated in every retrieval block
- net — questions turning on a year or on "latest / aktuell / wer hat gewonnen" now search **without waiting for the globe button**: they cannot be answered from weights at all
- net — retry-with-search: when the answer reads as "I don't know" and the network is available, the turn is rolled back and asked again with sources. Rolling back matters - a retry stacked under the model's own refusal just gets agreed with
- jni — `nativeRollbackTurn`: undoes the last exchange, history and KV cache both, via a pre-turn snapshot and `llama_memory_seq_rm`
- net — FIX Wikipedia missing run-together terms: "WM2026" returns zero hits, and the API's own spelling suggestion ("wm 2026") finds the article. Also fixed a regex that could read a title out of a `search: []` miss
- media — **the microphone is now dictation, not audio-to-model.** A spoken sentence cost a few hundred audio tokens of a 4096-token context where its transcript costs a dozen, nothing was shown on screen so a misheard question looked like a stupid model, and an audio-only turn could not be grounded at all. Text lands in the composer to be read and corrected before sending
- media — audio still reaches the model's encoder directly, now as an explicitly attached file
- ui — the attach button is a **+ menu**: image, audio file, or video
- media — video attaches as evenly spaced frames (`VideoPrep`). There is no native video path - llama.cpp's helper shells out to ffmpeg, which Android does not have. Six frames because each costs ~250 tokens; the menu and the composer both say so rather than implying the model watched the clip
- media — removed `AudioRecorder`, replaced by the platform recogniser
- tests — 42 total: time sensitivity, non-answer detection

## 2026-07-27 — 0.1.2

- native — **5x faster**: `GGML_NATIVE=OFF` with no `GGML_CPU_ARM_ARCH` set makes ggml-cpu emit no `-march` at all, inheriting the NDK baseline `armv8-a` with no dot-product instruction. Now built for `armv8.2-a+dotprod+fp16`. Same prompt on a Galaxy S23 Ultra: 1.3 → 6.9 tok/s decode, 44 s → 12 s prefill
- media — FIX picked images were silently dropped: `BitmapFactory.decodeStream` returns null by design under `inJustDecodeBounds`, so the elvis guarding it fired on every single pick. The stream is checked instead of the bitmap
- ui — markdown rendering for assistant messages: headings, lists, tables, code blocks, quotes, rules, and links that open when tapped. Hand-rolled rather than a dependency; re-parsed per streamed token, so an unclosed code fence renders as a block that runs to the end
- ui — `![alt](url)` is fetched and displayed, falling back to alt text when the model invents the URL. Inline images become links so raw markdown is never shown
- ui — attachments show as thumbnails instead of an "Image" chip, in the composer and in the sent message
- ui — named status line ("Searching the web", "Checking Wikipedia", "Reading example.com", "Thinking") with a spinner: a slow turn used to be a Stop button and no other sign of life
- ui — FIX dead space under the input row: Scaffold's content padding already carries the navigation-bar inset, and `navigationBarsPadding()` applied it a second time
- ui — attachment failures surface as an error card instead of nothing happening
- net — Wikipedia lead image for "show me a picture of X", with an instruction that stops the model apologising for something the app is already displaying
- net — FIX over-eager grounding: "count people" triggered a Wikipedia lookup. Bare interrogatives and question marks no longer qualify; the gate wants an entity-shaped question or a proper name, and rejects task verbs outright. Second guard: Wikipedia's search always returns *something*, so a hit whose title shares no word with the query is discarded
- ui — FIX the web-search toggle cleared itself after every send, which reads as the app turning a setting off behind your back. It now stays where you put it
- jni — per-turn timing log (media prep / encode+prefill / decode / tok\_s), which is how the `-march` problem was found rather than guessed at
- jni — attachment failures distinguish "no KV slot", "cancelled" and a real error instead of collapsing all three into "failed to process the attachment"
- tests — 34 total: markdown block parsing, the grounding gate, title relevance, image-request subject extraction

## 2026-07-27 — 0.1.1

- jni — FIX "chat template failed": Gemma 4 ships an 18 KB Jinja template with tool-calling macros and uses `<|turn>role … <turn|>`; `llama_chat_apply_template` does not parse Jinja, only pattern-matches a fixed list, so it returned -1. Renderer now written by hand against the GGUF's own template. Falling back to llama.cpp's `"gemma"` entry would have been worse than the error — that emits `<start_of_turn>`, which Gemma 4 does not use at all
- jni — Gemma 4 has a real `system` role (Gemma 3 did not); system prompt now gets its own turn instead of being folded into the first user message
- media — automatic voice-end detection: RMS energy with a noise floor measured from the room, stops 1.2 s after speech ends, gives up after 4 s of nobody speaking
- net — Wikipedia grounding as priority-1 fact source; keyless JSON API (more reliable than the DDG HTML scrape), English article first with local-language fallback, model answers in the question's language
- settings — editable system prompt (blank = generated from dials), Wikipedia toggle, Reset for the response-style dials, app version shown
- settings — explanation of how creativity and fact-checking relate, and why fact-checking caps creativity
- build — `buildConfig` deliberately left off: enabling it forces javac, which trips AGP 8.1.4's JdkImageTransform under Studio's JDK 21; version read from PackageManager instead
- known gap — voice-only messages cannot be grounded: audio goes to the model directly, so no transcript exists for retrieval. See docs/roadmap.md

## 2026-07-27 — 0.1.0

- project — initial scaffold: Android app (Kotlin, Compose, AGP 8.1.4, Gradle 8.2), arm64-v8a, minSdk 29
- native — llama.cpp pinned as submodule at `d73c1d6`; built via `LLAMA_BUILD_MTMD=ON` + `LLAMA_BUILD_TOOLS=OFF` so only the standalone mtmd library comes along; statically linked into one `libpocketllm.so` (6.4 MB stripped)
- jni — `pocketllm_jni.cpp`: model load with progress, mmproj load, chat-template diffing against the KV cache, mtmd multimodal path, streaming decode with UTF-8 boundary buffering, cancellation via abort callback
- llm — two dials (creativity, fact-checking) mapping to sampler values plus system prompt; fact-checking caps creativity so the settings cannot silently cancel each other
- llm — engine is application-scoped on a single inference thread; projector load deferred until the first attachment
- model — catalog for Gemma 4 E4B/E2B mobile QAT with real HF byte sizes; resumable range-request downloader; size-exact completeness check
- media — image downscale to 1024 px with EXIF rotation; 16 kHz mono WAV recorder feeding the model's audio encoder directly rather than a speech-to-text step
- ui — single chat screen, auto-loads the model on launch with no prompts, settings bottom sheet
- net — in-app web retrieval: DuckDuckGo HTML search + page fetch + dependency-free HTML-to-text; no API key, account or self-hosted service (SearXNG/Brave rejected as external setup)
- net — retrieval is deterministic and pre-generation, not a model-driven tool-calling loop: a URL in the message triggers a read, the globe button arms search for one message; context capped at ~1000 tokens
- ui — web access off by default, per-message globe toggle, fetched URLs listed under the answer
- tests — 17 unit tests: dial mapping and creativity cap, URL extraction, HTML stripping and entity decoding
- docs — README plus roadmap (web search, URL reading, rename, foreground service, GPU/KleidiAI, speculative decoding)
- naming — "PocketLLM" collides with several shipping products; kept provisionally, rename isolated to three lines, trademark registers still unchecked
