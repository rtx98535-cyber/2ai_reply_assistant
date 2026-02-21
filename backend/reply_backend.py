import json
import os
import random
import re
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib import request as urlrequest


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


class Config:
    host: str = os.getenv("BACKEND_HOST", "0.0.0.0")
    port: int = env_int("BACKEND_PORT", 4000)

    # Primary generation mode:
    # - "openai" => GPT-4.1-nano primary, rules fallback
    # - "rules" => rules primary, OpenAI shadow optional
    primary_mode: str = os.getenv("PRIMARY_MODE", "openai").strip().lower()
    primary_timeout_sec: int = max(3, env_int("PRIMARY_TIMEOUT_SEC", 12))

    # Shadow calibration mode. In openai primary mode, shadow compares against rules baseline (no extra model cost).
    # In rules primary mode, shadow optionally calls OpenAI for comparison.
    shadow_mode: bool = env_bool("SHADOW_MODE", True)
    shadow_sample_rate: float = max(0.0, min(1.0, env_float("SHADOW_SAMPLE_RATE", 1.0)))
    shadow_timeout_sec: int = max(3, env_int("SHADOW_TIMEOUT_SEC", 12))

    openai_api_key: str = os.getenv("OPENAI_API_KEY", "").strip()
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-4.1-nano").strip()
    openai_base_url: str = os.getenv("OPENAI_BASE_URL", "https://api.openai.com").rstrip("/")

    shadow_log_path: str = os.getenv("SHADOW_LOG_PATH", "backend/shadow_logs.jsonl")


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def canonical_text(text: str) -> str:
    return re.sub(r"[^a-z0-9\s]+", "", text.lower()).strip()


VALID_ARCHETYPES = {"witty", "supportive", "short", "curious", "direct"}
VALID_TONES = {"playful", "friendly", "neutral", "serious"}


def normalize_archetype(raw: str) -> str:
    value = (raw or "").strip().lower()
    return value if value in VALID_ARCHETYPES else "direct"


def normalize_tone(raw: str) -> str:
    value = (raw or "").strip().lower()
    return value if value in VALID_TONES else "neutral"


def dedupe_suggestions(suggestions: list[dict[str, str]]) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in suggestions:
        text = str(item.get("text", "")).replace("\n", " ").strip()
        text = re.sub(r"\s+", " ", text)
        if not text:
            continue
        key = canonical_text(text)
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(
            {
                "text": text,
                "archetype": normalize_archetype(str(item.get("archetype", "direct"))),
                "tone": normalize_tone(str(item.get("tone", "neutral"))),
            }
        )
    return out


def desired_count_from_payload(payload: dict[str, Any]) -> int:
    raw = payload.get("desired_count", 5)
    try:
        value = int(raw)
    except (TypeError, ValueError):
        value = 5
    return max(1, min(5, value))


def overlap_count(a: list[dict[str, str]], b: list[dict[str, str]]) -> int:
    sa = {canonical_text(x["text"]) for x in a}
    sb = {canonical_text(x["text"]) for x in b}
    return len(sa.intersection(sb))


def tone_from_bias(controls: dict[str, Any], fallback: str) -> str:
    bias = str(controls.get("tone_bias", "neutral")).strip().lower()
    if bias == "funny":
        return "playful"
    if bias == "polite":
        return "friendly"
    if bias == "serious":
        return "serious"
    return fallback


def clamp_text_for_length(text: str, length_mode: str) -> str:
    if length_mode != "short":
        return text.strip()
    words = text.split()
    if len(words) <= 8:
        return text.strip()
    return " ".join(words[:8]).rstrip(".,;:!?") + "."


def style_text(text: str, style: str, slang_level: int, emoji_level: int, tone: str) -> str:
    out = text.strip()
    if slang_level > 0:
        if style.lower() == "hinglish":
            out = (
                out.replace("this", "ye")
                .replace("that", "wo")
                .replace("you", "tum")
                .replace("your", "tumhara")
            )
            if slang_level >= 2 and not out.lower().startswith("bhai"):
                out = "bhai, " + out[0].lower() + out[1:]
        else:
            if slang_level >= 1 and not out.lower().startswith(("ngl", "tbh", "honestly")):
                out = "ngl, " + out[0].lower() + out[1:]
            if slang_level >= 2:
                out = out.replace("I am", "I'm kinda").replace("I think", "I kinda think")

    if emoji_level > 0:
        emoji_pool = {
            "playful": ["😂", "😄"],
            "friendly": ["🙂", "🙏"],
            "serious": ["🧠", "📌"],
            "neutral": ["🙂", "✨"],
        }
        choices = emoji_pool.get(tone, emoji_pool["neutral"])
        chosen = choices[min(max(emoji_level - 1, 0), len(choices) - 1)]
        if chosen not in out:
            out = f"{out} {chosen}"
    return out.strip()


def intent_templates(intent: str, style: str) -> list[dict[str, str]]:
    h = style.lower() == "hinglish"
    if intent == "asking":
        return [
            {"text": "Good question, I was thinking the same." if not h else "Sahi question hai, main bhi yahi soch raha tha.", "archetype": "supportive", "tone": "friendly"},
            {"text": "Can you explain this part a bit more?" if not h else "Ye part thoda aur explain kar sakte ho?", "archetype": "curious", "tone": "neutral"},
            {"text": "Interesting angle, what made you think that?" if not h else "Interesting angle hai, aisa kyun laga?", "archetype": "curious", "tone": "playful"},
            {"text": "I'd like to hear more context." if not h else "Thoda aur context sunna chahta hu.", "archetype": "direct", "tone": "neutral"},
            {"text": "Fair point, thanks for asking." if not h else "Fair point, puchne ke liye thanks.", "archetype": "supportive", "tone": "friendly"},
        ]
    if intent == "praising":
        return [
            {"text": "Great point, this is solid." if not h else "Sahi point, kaafi solid hai.", "archetype": "supportive", "tone": "friendly"},
            {"text": "Well said, this lands well." if not h else "Badiya bola, bilkul land kiya.", "archetype": "direct", "tone": "neutral"},
            {"text": "Love this perspective." if not h else "Ye perspective mast laga.", "archetype": "short", "tone": "playful"},
            {"text": "That's a really clean take." if not h else "Ye kaafi clean take hai.", "archetype": "direct", "tone": "neutral"},
            {"text": "Nice one." if not h else "Nice hai.", "archetype": "short", "tone": "friendly"},
        ]
    if intent in {"criticizing", "disagreeing"}:
        return [
            {"text": "I see it differently, but fair take." if not h else "Mera take alag hai, but fair point.", "archetype": "direct", "tone": "serious"},
            {"text": "Not sure I agree. What's your source?" if not h else "Pura agree nahi hu. Source kya hai?", "archetype": "curious", "tone": "serious"},
            {"text": "Valid concern, context matters here." if not h else "Concern valid hai, context bhi matter karta hai.", "archetype": "supportive", "tone": "neutral"},
            {"text": "Could you clarify what you mean exactly?" if not h else "Exactly kya mean kar rahe ho, clarify karoge?", "archetype": "curious", "tone": "neutral"},
            {"text": "I get your point, but I disagree." if not h else "Point samjha, but disagree karta hu.", "archetype": "direct", "tone": "serious"},
        ]
    if intent == "joking":
        return [
            {"text": "That caught me off guard." if not h else "Ye toh unexpected tha.", "archetype": "witty", "tone": "playful"},
            {"text": "Okay, that was actually funny." if not h else "Haha, ye genuinely funny tha.", "archetype": "short", "tone": "playful"},
            {"text": "Now I can't unsee this." if not h else "Ab ye unsee nahi hoga.", "archetype": "witty", "tone": "playful"},
            {"text": "Did not expect that ending." if not h else "Ye ending expect nahi ki thi.", "archetype": "direct", "tone": "playful"},
            {"text": "That was wild." if not h else "Ye toh wild tha.", "archetype": "short", "tone": "playful"},
        ]
    return [
        {"text": "Interesting take.", "archetype": "short", "tone": "neutral"},
        {"text": "Fair point, that makes sense.", "archetype": "supportive", "tone": "friendly"},
        {"text": "Could you share more context?", "archetype": "curious", "tone": "neutral"},
        {"text": "I can see where you're coming from.", "archetype": "supportive", "tone": "friendly"},
        {"text": "Good point.", "archetype": "direct", "tone": "neutral"},
    ]


def fallback_pool(style: str) -> list[dict[str, str]]:
    if style.lower() == "hinglish":
        return [
            {"text": "Nice!", "archetype": "short", "tone": "neutral"},
            {"text": "Sahi point.", "archetype": "direct", "tone": "neutral"},
            {"text": "Interesting.", "archetype": "short", "tone": "neutral"},
            {"text": "Good question.", "archetype": "curious", "tone": "neutral"},
            {"text": "Haan, makes sense.", "archetype": "supportive", "tone": "friendly"},
        ]
    return [
        {"text": "Nice!", "archetype": "short", "tone": "neutral"},
        {"text": "Good point.", "archetype": "direct", "tone": "neutral"},
        {"text": "Interesting.", "archetype": "short", "tone": "neutral"},
        {"text": "Good question.", "archetype": "curious", "tone": "neutral"},
        {"text": "That makes sense.", "archetype": "supportive", "tone": "friendly"},
    ]


def generate_rules_suggestions(payload: dict[str, Any]) -> list[dict[str, str]]:
    context = payload.get("context", {}) if isinstance(payload.get("context"), dict) else {}
    controls = payload.get("controls", {}) if isinstance(payload.get("controls"), dict) else {}
    desired_count = desired_count_from_payload(payload)
    intent = str(context.get("intent", "neutral")).strip().lower() or "neutral"
    style = str(context.get("user_style", "English")).strip() or "English"
    length_mode = str(controls.get("length", "medium")).strip().lower() or "medium"
    emoji_level = int(controls.get("emoji_level", 0) or 0)
    slang_level = int(controls.get("slang_level", 0) or 0)
    templates = intent_templates(intent, style)

    out: list[dict[str, str]] = []
    for template in templates:
        tone = tone_from_bias(controls, template["tone"])
        text = clamp_text_for_length(template["text"], length_mode)
        text = style_text(text, style, slang_level, emoji_level, tone)
        out.append({"text": text, "archetype": template["archetype"], "tone": tone})

    out = dedupe_suggestions(out)
    fillers = fallback_pool(style)
    for filler in fillers:
        if len(out) >= desired_count:
            break
        out.append(filler)
        out = dedupe_suggestions(out)

    while len(out) < desired_count:
        out.append({"text": "Nice!", "archetype": "short", "tone": "neutral"})
        out = dedupe_suggestions(out)

    return out[:desired_count]


class OpenAIClient:
    def __init__(self, config: Config):
        self.config = config

    def generate(self, payload: dict[str, Any], timeout_sec: int) -> list[dict[str, str]]:
        if not self.config.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY missing")

        url = f"{self.config.openai_base_url}/v1/chat/completions"
        desired_count = desired_count_from_payload(payload)
        context = payload.get("context", {}) if isinstance(payload.get("context"), dict) else {}
        reply_type = str(context.get("reply_type", "comment")).strip().lower() or "comment"
        primary_text = str(context.get("primary_text", "")).strip()
        secondary_texts_raw = context.get("secondary_texts", [])
        secondary_texts: list[str] = []
        if isinstance(secondary_texts_raw, list):
            for item in secondary_texts_raw:
                text = str(item).strip()
                if text:
                    secondary_texts.append(text)
        secondary_texts = secondary_texts[:6]
        user_draft = str(payload.get("user_draft", "")).strip()
        controls = payload.get("controls", {}) if isinstance(payload.get("controls"), dict) else {}

        if reply_type == "chat":
            system_prompt = (
                "You generate direct, in-context reply suggestions for social/chat inputs. "
                f"Output strict JSON with key 'suggestions' containing exactly {desired_count} objects. "
                "Each object keys: text, archetype, tone. "
                "Archetype one of: witty, supportive, short, curious, direct. "
                "Tone one of: playful, friendly, neutral, serious. "
                "No markdown. No code fences. No preamble. "
                "Rewrite ONLY the provided primary_text into improved alternatives. "
                "Do not add new facts, names, requests, or side topics. "
                "Preserve the original meaning, topic, entities, and language."
            )
            user_payload = {
                "task": f"Return exactly {desired_count} polished rewrites of primary_text.",
                "focus_rules": [
                    "use only primary_text",
                    "no side-topic additions",
                    "no context shift",
                ],
                "reply_type": reply_type,
                "primary_text": primary_text,
                "secondary_texts": secondary_texts,
                "user_draft": user_draft,
                "controls": controls,
            }
            temperature = 0.2
        else:
            system_prompt = (
                "You generate direct, in-context reply suggestions for social feed comments. "
                f"Output strict JSON with key 'suggestions' containing exactly {desired_count} objects. "
                "Each object keys: text, archetype, tone. "
                "Archetype one of: witty, supportive, short, curious, direct. "
                "Tone one of: playful, friendly, neutral, serious. "
                "No markdown. No code fences. No preamble. "
                "Write replies TO the primary_text, not rewrites OF primary_text. "
                "Do not copy or paraphrase the full primary_text. "
                "Use secondary_texts only as supporting context when provided."
            )
            user_payload = {
                "task": f"Return exactly {desired_count} short, sendable replies to the target content.",
                "focus_rules": [
                    "reply to primary_text as another person",
                    "do not rewrite primary_text",
                    "stay on-topic",
                ],
                "reply_type": reply_type,
                "primary_text": primary_text,
                "secondary_texts": secondary_texts,
                "user_draft": user_draft,
                "controls": controls,
            }
            temperature = 0.45

        body = {
            "model": self.config.openai_model,
            "temperature": temperature,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
            ],
            "response_format": {"type": "json_object"},
        }
        req = urlrequest.Request(
            url=url,
            method="POST",
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.config.openai_api_key}",
            },
        )
        with urlrequest.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8", errors="replace")

        parsed = json.loads(raw)
        content = parsed.get("choices", [{}])[0].get("message", {}).get("content", "")
        if not isinstance(content, str) or not content.strip():
            raise RuntimeError("OpenAI response missing message content")

        data = self._parse_model_json(content)
        suggestions_raw = data.get("suggestions")
        if not isinstance(suggestions_raw, list):
            raise RuntimeError("Model suggestions is not a list")

        out: list[dict[str, str]] = []
        for item in suggestions_raw:
            if not isinstance(item, dict):
                continue
            out.append(
                {
                    "text": str(item.get("text", "")).strip(),
                    "archetype": normalize_archetype(str(item.get("archetype", "direct"))),
                    "tone": normalize_tone(str(item.get("tone", "neutral"))),
                }
            )
        out = dedupe_suggestions(out)
        if len(out) != desired_count:
            raise RuntimeError(f"Model returned {len(out)} valid suggestions, expected {desired_count}")
        return out[:desired_count]

    def _parse_model_json(self, content: str) -> dict[str, Any]:
        text = content.strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE).strip()
            text = re.sub(r"\s*```$", "", text).strip()
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", text, flags=re.DOTALL)
            if not match:
                raise RuntimeError("Failed to parse model JSON")
            parsed = json.loads(match.group(0))
        if not isinstance(parsed, dict):
            raise RuntimeError("Model JSON payload is not an object")
        return parsed


class ShadowEvaluator:
    def __init__(self, config: Config, openai_client: OpenAIClient):
        self.config = config
        self.openai_client = openai_client
        self._log_lock = threading.Lock()
        log_path = Path(self.config.shadow_log_path)
        log_path.parent.mkdir(parents=True, exist_ok=True)

    def maybe_enqueue(
        self,
        request_id: str,
        install_id: str,
        payload: dict[str, Any],
        primary_output: list[dict[str, str]],
        primary_source: str,
        primary_error: str,
        rules_output: list[dict[str, str]],
    ) -> None:
        if not self.config.shadow_mode:
            return
        if random.random() > self.config.shadow_sample_rate:
            return

        # OpenAI primary: shadow compares against rules baseline (no extra model request cost).
        if primary_source == "openai":
            self._append_log(
                {
                    "timestamp": now_iso(),
                    "request_id": request_id,
                    "install_id": install_id,
                    "mode": "shadow_rules_baseline",
                    "model": self.config.openai_model,
                    "primary_source": primary_source,
                    "error": primary_error,
                    "primary_texts": [it["text"] for it in primary_output],
                    "shadow_texts": [it["text"] for it in rules_output],
                    "overlap_count": overlap_count(primary_output, rules_output),
                    "context_summary": self._context_summary(payload),
                }
            )
            return

        # OpenAI fallback triggered: log failure path explicitly.
        if primary_source == "rules_fallback":
            self._append_log(
                {
                    "timestamp": now_iso(),
                    "request_id": request_id,
                    "install_id": install_id,
                    "mode": "fallback_triggered",
                    "model": self.config.openai_model,
                    "primary_source": primary_source,
                    "error": primary_error,
                    "primary_texts": [it["text"] for it in primary_output],
                    "shadow_texts": [],
                    "overlap_count": 0,
                    "context_summary": self._context_summary(payload),
                }
            )
            return

        # Rules primary mode: optionally evaluate OpenAI in shadow.
        if primary_source == "rules_primary":
            thread = threading.Thread(
                target=self._rules_primary_shadow_run,
                args=(request_id, install_id, payload, primary_output),
                daemon=True,
            )
            thread.start()

    def _rules_primary_shadow_run(
        self,
        request_id: str,
        install_id: str,
        payload: dict[str, Any],
        primary_output: list[dict[str, str]],
    ) -> None:
        started = time.time()
        error = ""
        shadow_output: list[dict[str, str]] = []
        try:
            shadow_output = self.openai_client.generate(payload, timeout_sec=self.config.shadow_timeout_sec)
        except Exception as exc:
            error = str(exc)
        latency_ms = int((time.time() - started) * 1000)

        self._append_log(
            {
                "timestamp": now_iso(),
                "request_id": request_id,
                "install_id": install_id,
                "mode": "shadow_openai_compare",
                "model": self.config.openai_model,
                "latency_ms": latency_ms,
                "primary_source": "rules_primary",
                "error": error,
                "primary_texts": [it["text"] for it in primary_output],
                "shadow_texts": [it["text"] for it in shadow_output],
                "overlap_count": overlap_count(primary_output, shadow_output),
                "context_summary": self._context_summary(payload),
            }
        )

    def _context_summary(self, payload: dict[str, Any]) -> dict[str, Any]:
        ctx = payload.get("context", {}) if isinstance(payload.get("context"), dict) else {}
        return {
            "reply_type": ctx.get("reply_type"),
            "intent": ctx.get("intent"),
            "conversation_tone": ctx.get("conversation_tone"),
            "confidence": ctx.get("confidence"),
        }

    def _append_log(self, row: dict[str, Any]) -> None:
        serialized = json.dumps(row, ensure_ascii=False)
        with self._log_lock:
            with open(self.config.shadow_log_path, "a", encoding="utf-8") as f:
                f.write(serialized + "\n")


CONFIG = Config()
OPENAI = OpenAIClient(CONFIG)
SHADOW = ShadowEvaluator(CONFIG, OPENAI)


class ReplyHandler(BaseHTTPRequestHandler):
    server_version = "AIReplyBackend/1.1"

    def _write_json(self, status: int, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json(
                200,
                {
                    "ok": True,
                    "primary_mode": CONFIG.primary_mode,
                    "shadow_mode": CONFIG.shadow_mode,
                    "shadow_sample_rate": CONFIG.shadow_sample_rate,
                    "openai_model": CONFIG.openai_model,
                    "openai_key_present": bool(CONFIG.openai_api_key),
                },
            )
            return
        self._write_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        if self.path != "/v1/reply-suggestions":
            self._write_json(404, {"error": "Not found"})
            return

        content_len = int(self.headers.get("Content-Length", "0"))
        if content_len <= 0:
            self._write_json(400, {"error": "Empty body"})
            return

        body = self.rfile.read(content_len)
        try:
            payload = json.loads(body.decode("utf-8"))
        except Exception:
            self._write_json(400, {"error": "Invalid JSON"})
            return

        if not isinstance(payload, dict):
            self._write_json(400, {"error": "Invalid request"})
            return

        install_id = str(self.headers.get("X-Install-Id", "")).strip()
        request_id = str(uuid.uuid4())
        desired_count = desired_count_from_payload(payload)

        rules_output = generate_rules_suggestions(payload)
        primary_output = rules_output
        primary_source = "rules_primary"
        primary_error = ""

        if CONFIG.primary_mode == "openai":
            try:
                primary_output = OPENAI.generate(payload, timeout_sec=CONFIG.primary_timeout_sec)
                primary_source = "openai"
            except Exception as exc:
                primary_output = rules_output
                primary_source = "rules_fallback"
                primary_error = str(exc)
        elif CONFIG.primary_mode != "rules":
            primary_source = "rules_primary"
            primary_error = f"Unknown PRIMARY_MODE={CONFIG.primary_mode}, using rules"

        response_payload = {
            "source": primary_source,
            "suggestions": primary_output[:desired_count],
        }
        self._write_json(200, response_payload)

        SHADOW.maybe_enqueue(
            request_id=request_id,
            install_id=install_id,
            payload=payload,
            primary_output=primary_output[:desired_count],
            primary_source=primary_source,
            primary_error=primary_error,
            rules_output=rules_output[:desired_count],
        )

    def log_message(self, format: str, *args: Any) -> None:
        line = "%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), format % args)
        print(line.rstrip())


def main() -> None:
    server = ThreadingHTTPServer((CONFIG.host, CONFIG.port), ReplyHandler)
    print(
        json.dumps(
            {
                "event": "server_start",
                "host": CONFIG.host,
                "port": CONFIG.port,
                "primary_mode": CONFIG.primary_mode,
                "primary_timeout_sec": CONFIG.primary_timeout_sec,
                "shadow_mode": CONFIG.shadow_mode,
                "shadow_sample_rate": CONFIG.shadow_sample_rate,
                "openai_model": CONFIG.openai_model,
                "openai_key_present": bool(CONFIG.openai_api_key),
                "shadow_log_path": CONFIG.shadow_log_path,
            }
        )
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
