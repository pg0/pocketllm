# Changelog

## 2026-07-27 — 0.3.1

- ui — **"Show context and speed" setting**: a line above the composer with `ctx 1240 / 4096 (30%)`, a thin bar, and the last turn's `tok/s`, token count, prompt time and media tokens. The bar turns red past 85%, because a full window ends the conversation and does it silently otherwise
- jni — `nativeLastTurnStats` returns the timings that were previously only in logcat, as one array rather than four calls that could straddle a turn. Read on the inference thread after every turn, cancelled ones included - a partial answer still spent context and still took time
- llm — stats clear on a new chat rather than claiming the window is a third full when it is empty

## 2026-07-27 — 0.3.0

- llm — **image detail setting**, which is the fix for "the text is fragmented" on a photographed page. `clip` scales every image down until it fits a token budget, and llama.cpp caps Gemma 4 at 280 tokens - about 0.6 MP, which puts 10pt body text at 14 px. Downscaling less on our side does nothing, because clip resizes to its budget regardless of what it is handed. The budget itself is now settable: Standard 280, High 600, Maximum 1120 tokens per image
- jni — `nativeLoadProjector` takes `imageMaxTokens`; the engine reloads the projector when it changes, since the budget is fixed at encoder init
- media — the PDF page limit is computed instead of fixed at five: half the context window divided by what one page costs. Seven pages at stock settings, one at maximum detail on a 4096 window
- media — PDF pages render at 2048 px now rather than 1536. There is no point matching the encoder's target: it resizes to its own budget anyway, and a crisp render downsampled beats a coarse one upscaled
- build — **16 KB page size compatible.** Both `.so` files were already 16 KB-aligned (NDK r27 does that), but AGP 8.1.4 zipaligns APK entries to 4 KB, and an uncompressed library mapped straight out of the APK has to start on a 16 KB boundary. AGP 8.5.2 / Gradle 8.7 do it correctly; verified with `zipalign -c -P 16`

## 2026-07-27 — 0.2.1

- ui — tapping a thumbnail opens the attachment full screen, with pinch zoom, double tap for 2x and tap to close. It shows the file the app prepared rather than handing the original to another app, which is the point: what is on screen is exactly what the model was given, and a PDF page that rendered badly or a photo that came out sideways is invisible at 64 dp
- ui — tapping a document chip shows the extracted text with its character count. When an answer misses something plainly in the file, this is what says whether the model failed or the extraction did. The X on the chip still removes it

## 2026-07-27 — 0.2.0

- media — **documents attach from the + menu**: PDF, Word, Excel, CSV and any text or code file
- media — text formats go into the **prompt**, not through an encoder. Text costs roughly a quarter of what a picture of the same text costs, and it is exact rather than read off pixels. Capped at 6000 characters of a 4096-token window, and truncation is stated in the prompt so the model says "the part I can see does not mention that" instead of "the document does not mention that"
- media — `.docx` and `.xlsx` are parsed in-app - both are a zip of XML, so it is one entry and a few regexes rather than several megabytes of APK for a format that is mostly styling. Excel cells resolve through the shared-string table and come out tab-separated, one row per line
- media — `.pdf` renders to page images for the vision encoder instead. A PDF describes marks on a page rather than text, Android's `PdfRenderer` hands out pixels and nothing else, and a scanned document has no text layer to extract at all - looking at it is the only way to read it. Five pages max (~250 tokens each), rendered at 1536 px because body copy at 1024 px stops being legible, and the page count is shown before sending
- media — the old binary `.doc`/`.xls`/`.ppt` are refused by name with the fix ("save it as .docx, CSV or text") rather than producing mojibake
- net — a document suppresses retrieval the same way an image does: "summarise this" is not a search query, and the document has already claimed the context an article would have needed
- tests — 49 total: docx runs and line breaks, xlsx shared strings, self-closing and out-of-range cells

## 2026-07-27 — 0.1.10

- ui — the status line names each retrieval step as it happens: "Checking Wikipedia", "Searching DuckDuckGo", "Reading 2/3: heise.de". Retrieval is several sequential requests against other people's servers, and one static label for all of them makes a slow site look exactly like a hang
- llm — FIX a German question answered in English. Three things push the reply towards English and they add up: the system prompt is written in English, Wikipedia is queried in English first because those articles are better cited, and search results are mostly English. The question ends up the only non-English text in the prompt and the model follows the majority. The rule is now stated in every system prompt, custom ones included, rather than only in the two Wikipedia retrieval blocks
- net — pages are capped at **512 KB downloaded**. Only ~6000 characters of stripped text are ever used, so reading a multi-megabyte page in full spent mobile data on markup that was discarded on arrival. A truncated body is cut back to the last complete tag so no `<div class=` shows up as text

- ui — the source URLs under an answer open in the browser when tapped. They were plain labels; listing what was fetched only means something if it can be opened and checked
- net — FIX citation numbers pointing at the wrong source: search hits were numbered from 1 while Wikipedia sat unnumbered above them in the same list, so "[2]" in an answer was the third entry shown. Numbering now runs across both, and the list under the answer carries the same numbers

## 2026-07-27 — 0.1.8

- ui — **Wikipedia is a checkbox in the + menu**, next to web search, and it is **off by default**. It was consulted automatically for anything fact-shaped, which is a lookup nobody asked for, spending context on an article that may have nothing to do with the question. Both sources now have to be checked
- ui — the menu item drives the same persisted flag as the settings sheet rather than a second per-message one: a source is something you want on for a stretch of conversation, and two switches disagreeing about whether Wikipedia is on would be worse than either. With web access off both are shown disabled and say "needs web access" instead of vanishing

## 2026-07-27 — 0.1.7

- net — FIX retrieval running on messages that are about an attachment: "classify this flower" sent the app to Wikipedia and a search *before* the model had seen the picture. That text names nothing lookupable, so it returns an unrelated article and spends the context the image needs. With an attachment present, automatic Wikipedia, automatic time-sensitive search, the armed search toggle and the retry-with-search pass are all off. A URL typed by hand still gets read - that is an instruction, not a guess
- ui — an armed search toggle that gets skipped for this reason says so on the message instead of being ignored quietly

## 2026-07-27 — 0.1.6

- ui — FIX the caret staying at the top of the composer while dictation wrote into it, so a spoken sentence longer than one line scrolled out of sight below the field. The composer state is a `TextFieldValue` now and every write from dictation carries the caret to the end with it
- media — **video attachment removed.** It was six stills from the whole clip, not six per second: llama.cpp's video path shells out to ffmpeg, which Android does not have, and 4096 tokens of context cannot hold enough frames for the sampling to mean anything. Six frames of an arbitrary video is a worse answer than no video. `VideoPrep` deleted; images and audio files still attach
- ui — the web-search globe moved into the `+` menu, with a check mark when it is on. Two icons fewer in the composer row is that much more text field; it is a toggle, not something pressed every message. The `+` itself is tinted while search is armed, so a setting behind a menu is not a setting you forget is on

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
