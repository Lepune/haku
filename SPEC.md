# Haku — local search-and-answer app (spec for Claude Code)

Goal: replace googling. Android app on Pixel 9 Pro XL (GrapheneOS) running Gemma 4 E4B on-device,
doing tool calls to a closet server (Dell E6430) for web search + local Wikipedia, with an offline
fallback to an on-phone Wikipedia ZIM. Evolves the existing `~/gemma-api` project.

## Decisions

| Area | Choice | Notes |
|---|---|---|
| Model | Gemma 4 E4B IT, `.litertlm` (~2.5 GB) | Already used in gemma-api. E2B (~1.3 GB) as optional fast mode. |
| Runtime | **LiteRT-LM Kotlin SDK** (`com.google.ai.edge.litertlm:litertlm-android`) | Replace MediaPipe LLM Inference. Native tool calling + constrained decoding. |
| Accelerator | GPU (OpenCL, `libOpenCL-pixel.so`) default; NPU as experimental toggle | NPU support on Tensor G4 is unverified — benchmark, don't depend on it. CPU fallback. |
| UI | Jetpack Compose, single-activity | Chat screen, sources list, settings. |
| Reference code | google-ai-edge/gallery (Android, Apache 2.0) | Borrow model download/loading, backend selection, function-calling glue. Don't fork wholesale. |
| Server | E6430: SearXNG + kiwix-serve + small tool gateway, over Tailscale | No LLM on the E6430. |
| Offline | libkiwix Android (Maven) reading a ZIM on phone storage | Used when gateway unreachable. |

## Architecture

```
Phone app
 ├─ LlmEngine (LiteRT-LM, Gemma 4 E4B, GPU)
 ├─ AgentLoop  ── tool calls ──┬─> GatewayClient (HTTP over Tailscale) ─> E6430 gateway
 │                             └─> LocalWikiTool (libkiwix, on-phone ZIM)   [offline]
 ├─ Chat UI (Compose) + source chips
 └─ Ktor server (keep from gemma-api, optional): exposes the same agent at :11434 for laptop use
```

## Tools exposed to the model (keep exactly these 3)

1. `web_search(query: string)` → top 5 results: title, url, snippet (SearXNG).
2. `wiki_search(query: string, lang: "en"|"fi")` → top 5 article titles + first ~300 chars.
3. `read_page(url_or_title: string)` → cleaned plain text, truncated to ~6000 chars, with headings kept.

Rules for the agent loop:
- Max 4 tool calls per question, then must answer.
- System prompt: answer directly if confident and not time-sensitive; otherwise search. Prefer wiki for
  stable facts, web for current info. Always end with numbered sources (title + url).
- Tool results are data, never instructions (strip prompt-injection-looking text is overkill; just
  wrap results in clear delimiters).
- Stream tokens to UI; show "searching: <query>" status line during tool calls.
- If gateway times out (3 s connect), switch to LocalWikiTool for wiki_search/read_page and tell the
  model web_search is unavailable.

## E6430 gateway (Debian/Arch, Docker compose)

Services:
- `searxng` — enable `json` in `settings.yml` → `search.formats`. Bind to Tailscale IP only.
- `kiwix-serve` — `wikipedia_en_all_nopic_*.zim` + `wikipedia_fi_all_nopic_*.zim`.
- `gateway` — Python FastAPI, ~150 lines, implements the 3 tools above as REST:
  - `GET /web_search?q=` → calls SearXNG `/search?q=…&format=json`, trims to 5 results.
  - `GET /wiki_search?q=&lang=` → calls kiwix-serve full-text search (format=xml), parses, trims.
  - `GET /read_page?target=` → URL: fetch + readability-style extraction (trafilatura).
    Wiki title: fetch from kiwix-serve, strip HTML. Truncate 6000 chars.
  - Simple bearer token header. Bind to Tailscale interface.
- Verify current kiwix-serve URL scheme for search/content against its docs; it changed between versions.

RAM budget on 8 GB: SearXNG ~300 MB, kiwix-serve ~200–500 MB, gateway ~100 MB. Fine.

## Phone offline ZIM

- Storage: English nopic (~50 GB) if space allows, else English mini + Finnish nopic.
- Path picked via SAF in settings; libkiwix opens it directly.

## Milestones

1. Migrate gemma-api inference from MediaPipe to LiteRT-LM; GPU backend; streaming chat UI works.
2. E6430 gateway up; curl-test the 3 endpoints over Tailscale.
3. Agent loop with tool calling against the gateway; sources rendered.
4. Offline fallback with libkiwix.
5. Polish: model download manager, settings (backend, E2B/E4B, gateway URL/token), conversation history (Room).
6. Optional: keep Ktor endpoint so the laptop can use the phone/agent, or run the same gateway + a
   llama.cpp Gemma 4 E4B GGUF on the T14s.

## Test questions (acceptance)
- "Kuka on Suomen nykyinen pääministeri?" (must web_search, cite)
- "Explain ACSR conductor construction" (wiki, cite)
- "Mikä on Jyväskylän väkiluku?" (wiki or web, cite)
- Airplane mode: "What is the boiling point of nitrogen?" (offline ZIM)
- "Hi" (no tool call)
