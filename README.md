# AI Reply Assistant (Android)

Current checkpoint: **feed-only runtime** with two interaction paths on `AI+`:

- `Tap` -> draft enhancement panel.
- `Drag` -> detached pointer targeting on feed text.

## What is implemented now

- `ChatReplyEngine` has been removed from runtime for this checkpoint.
- Supported feed apps use `FeedReplyEngine` only.
- `AI+` icon is fixed above keyboard (does not move).
- Drag UX:
  - pointer detaches instantly when user moves finger.
  - release point is used as capture target.
  - pointer animates back to `AI+`.
- Tap UX (focused input required):
  - reads current input draft.
  - opens small action panel with:
    - `Enhance this`
    - `Put more context`
    - `Correct grammar`
- Suggestions UI is now swipe slider (horizontal pager) with dots.
- Suggestion text is shown full-length (no local truncation in intelligence layer).
- Overlay transparency issue fixed (no white background overlay).

## Supported app packages

Feed mode (`FEED_POINTER`):

- `com.twitter.android` (X)
- `com.instagram.barcelona` (Threads)
- `com.linkedin.android` (LinkedIn)

If package is not in this allowlist, `AI+` is hidden.

## Behavior summary

Feed apps:

- `Tap AI+`:
  - captures typed draft from focused input.
  - opens action panel for rewrite/grammar flows.
- `Drag AI+`:
  - detached pointer appears and follows drag.
  - drop runs feed capture near target text.
  - weak/meta capture is blocked with guidance.

## Backend contract

Endpoint:

- `POST /v1/reply-suggestions`

Request fields:

- `context` (`reply_type`, `primary_text`, `secondary_texts`, `intent`, `conversation_tone`, `user_style`, `confidence`)
- `controls` (`tone_bias`, `length`, `emoji_level`, `slang_level`)
- `user_draft`

Response:

- exactly 5 suggestions

Current backend behavior:

- `reply_type="chat"` -> rewrite-style suggestions (used by tap draft enhancement flows)
- `reply_type="comment"/"post"` -> reply-style suggestions (used by feed pointer flows)

Debug backend base URL:

- `app/build.gradle.kts` -> `REPLY_API_BASE_URL = "http://192.168.31.136:4000"`

## Build and install

Clean build:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat -p C:\2ai_reply_assistant --no-configuration-cache clean :app:assembleDebug
```

Install:

```powershell
$adb="C:\Users\Animus\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb uninstall com.animus.aireplyassistant.debug
& $adb install -r "C:\2ai_reply_assistant\app\build\outputs\apk\debug\app-debug.apk"
```

Package:

- `com.animus.aireplyassistant.debug`

## Accessibility note

After reinstall, if overlay does not appear:

1. Accessibility -> disable `AI Reply Assistant`
2. Force stop app
3. Re-enable `AI Reply Assistant`
4. Re-test

## Code map

Core routing/service:

- `app/src/main/java/com/animus/aireplyassistant/accessibility/ReplyAccessibilityService.kt`

Feed context engine:

- `app/src/main/java/com/animus/aireplyassistant/context/FeedReplyEngine.kt`
- `app/src/main/java/com/animus/aireplyassistant/context/ContextEngine.kt`

Overlay:

- `app/src/main/java/com/animus/aireplyassistant/overlay/OverlayController.kt`
- `app/src/main/java/com/animus/aireplyassistant/overlay/OverlayUi.kt`
- `app/src/main/java/com/animus/aireplyassistant/AIReplyAssistantTheme.kt`

Generation:

- `app/src/main/java/com/animus/aireplyassistant/generation/ApiModels.kt`
- `app/src/main/java/com/animus/aireplyassistant/generation/ReplySuggestionsRepository.kt`
- `app/src/main/java/com/animus/aireplyassistant/generation/ReplyIntelligenceLayer.kt`
- `app/src/main/java/com/animus/aireplyassistant/generation/HttpReplySuggestionsApi.kt`
- `app/src/main/java/com/animus/aireplyassistant/generation/MockReplySuggestionsApi.kt`

Backend:

- `backend/reply_backend.py`

## Test checklist (current checkpoint)

Feed pointer flow:

1. Hold + drag from `AI+` -> detached pointer appears immediately.
2. Release on post body -> capture + suggestions should run.
3. Release on timestamp/stats row -> blocked with `Nothing detected...`.
4. Pointer should return to icon after release/cancel.

Tap draft panel flow:

1. Focus an input with typed text and tap `AI+` -> panel opens.
2. `Enhance this` -> rewrite suggestions.
3. `Put more context` -> context input appears, then `Enhance with context` works.
4. `Correct grammar` -> grammar-correction suggestions.

Suggestions UI:

1. Suggestions appear in horizontal slider.
2. Swipe left/right cycles suggestions.
3. Text is full-length (not clipped by intelligence layer).

## Known limitations

- Runtime is intentionally feed-only in this checkpoint.
- Tap-panel actions still route through `reply_type="chat"` rewrite behavior.
- If OpenAI quota/rate fails, backend falls back to rules suggestions.

## Continue from here

1. Add dedicated backend mode for grammar (`correct_grammar`) instead of reusing chat rewrite prompt.
2. Split tap rewrite intents cleanly (`enhance`, `grammar`, `contextual_rewrite`) with explicit prompt templates.
3. Add drag UX polish:
   - haptic feedback on drag start/release
   - optional line fade / return easing tuning
4. Add slider usability:
   - copy button per card
   - index label (`2/5`)
5. Add regression checks for:
   - tap panel behavior
   - detached pointer behavior
   - transparent overlay on accessibility enable.
