# TAV progress log

## Phase 0 — Stabilize (complete)

Fixed the nine audit defects so a clean clone builds without llama.cpp:

1. **Agent loop bounds** — `MAX_STEPS=25`, wall-clock timeout, `MAX_PARSE_RETRIES=3`.
2. **No hardcoded swipe pixels** — scroll nearest scrollable node, else `DisplayMetrics` fractions.
3. **`waitForStableUi`** — semantic tree hash (no ephemeral `n17` ids) unchanged for N ms after content-change events.
4. **AppResolver** — no Settings fallback; launcher labels + Levenshtein/token overlap + cached aliases; dialog asks “Which app?”.
5. **Native LLM opt-in** — `-PenableLocalLlm=true` only; `LlamaCppBackend` degrades if `loadLibrary` fails.
6. **Foreground service** — `ContextCompat.startForegroundService`; Android 14 `FOREGROUND_SERVICE_SPECIAL_USE` property.
7. **Backup** — `allowBackup=false`; DB/prefs excluded in backup rules.
8. **Gemini** — `BuildConfig.GEMINI_MODEL`, verify-on-first-call, timeouts, 429/5xx exponential retry; never log API key.
9. **`local.properties.example`** — `GEMINI_API_KEY=` and `GEMINI_MODEL=`.
