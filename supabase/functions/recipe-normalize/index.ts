/**
 * NutriSnap – AMM-ähnlicher Server-Import (kostenlos).
 *
 * Input:
 *   { sourceUrl, platform?, caption?, imageUrl?, transcript? }
 *   – sourceUrl allein reicht: Server holt Caption selbst
 *   – caption optional (Client-Fallback / Cache)
 *   – transcript optional: gesprochenes Audio aus dem Video (Whisper o.ä.)
 *     → wird bevorzugt, wenn Caption schwach/kurz ist (Score 0 wie bei vielen Reels)
 *
 * Output: strukturiertes Rezept-JSON
 *
 * Secrets: supabase secrets set GROQ_API_KEY=...
 * Deploy:  supabase functions deploy recipe-normalize
 */
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
// llama-3.3-70b-versatile wurde von Groq am 17.06.2026 deprecated und ist seit
// dem Shutdown nicht mehr erreichbar (model_not_found) - offizielle Empfehlung
// von Groq: openai/gpt-oss-120b (Alternative: qwen/qwen3.6-27b).
// https://console.groq.com/docs/deprecations
const MODEL = "openai/gpt-oss-120b";

const SYSTEM = `You are a recipe extraction assistant for a German nutrition app (NutriSnap).
Convert ANY social-media caption OR spoken video transcript (German, English, French, OR Italian; emoji bullets)
into ONE strict JSON object. Respond ONLY with valid JSON — no markdown.

The input may be:
- a written caption, OR
- a speech-to-text transcript from a cooking video (spoken quantities, steps, filler words).
Treat both the same: extract ingredients and steps.

Schema:
{
  "title": "German dish name only",
  "description": "1-2 German sentences or empty",
  "servings": 2,
  "meal_category": "MAIN",
  "prep_time_minutes": null,
  "ingredient_sections": [
    { "section_name": "", "items": ["400 g Mehl Type 00", "400 g griechischer Joghurt"] }
  ],
  "instructions": "1. ...\\n2. ...",
  "tags": "main,high-protein"
}

Rules:
- OUTPUT LANGUAGE MUST BE GERMAN for title, section names, ingredient NAMES, instructions.
  Translate Italian/French/English food names. NEVER leave "farina", "cucchiaini", "olio", "sale".
- UNITS: Never invent grams for spoons/pieces.
  IT: cucchiaio/i → EL; cucchiaino/i → TL; "di" after unit → drop.
  FR: c. à soupe → EL; c. à café → TL; galettes → Stück.
  EN: tbsp→EL, tsp→TL.
- Extract EVERY ingredient line (including baking powder / lievito AND fluids):
  "ca. 150 ml ungesüßte Mandelmilch" MUST appear — never drop milk/water/ml lines.
- NEVER output "1 Portion" / "1 g Portion" as an ingredient. Headers like
  "Zutaten für 1 Portion:" set servings only.
- If text has steps (Procedimento/Verfahren/Method or spoken "dann …"): translate to numbered German steps.
  If no steps: GENERATE 5–8 realistic German steps.
- servings: "Zutaten für 1 Portion" → 1; "teilen in 8 Kugeln" / "8 palline" / "makes 8" → 8;
  "pour 2 individuels" / "für 2" → 2. Default 1 only if unclear.
- meal_category: BREAKFAST | MAIN | SIDE_SNACK | DESSERT | DRINK | SAUCE | OTHER.
  Piadina/Wrap/Brot/herzhaft → MAIN. Overnight oats → BREAKFAST. Tiramisu → DESSERT.
- title: dish only in German ("Protein-Piadina", not promo / gym speech).
- Do NOT invent nutrition numbers. No hashtags/@mentions in ingredients.
- From transcripts: ignore filler ("ähm", "so", "yeah") and keep only recipe content.

Italian example:
"Ingredienti: 400 g farina 00, 400 g yogurt greco, 3 cucchiaini di sale,
4 cucchiai di olio, 1 cucchiaino di lievito. Procedimento: mescolare… 8 palline…"
→ title "Protein-Piadina"
→ items: 400 g Mehl Type 00, 400 g griechischer Joghurt, 3 TL Salz, 4 EL Öl, 1 TL Backpulver
→ servings: 8, meal_category: MAIN, German numbered steps
`;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: cors });
  }

  try {
    const apiKey = Deno.env.get("GROQ_API_KEY") ?? "";
    if (!apiKey) {
      return json({ error: "GROQ_API_KEY not configured" }, 500);
    }

    const body = await req.json();
    const platform = String(body.platform ?? "instagram").toLowerCase();
    const sourceUrl = body.sourceUrl ? String(body.sourceUrl).trim() : "";
    const imageUrlIn = body.imageUrl ? String(body.imageUrl) : null;
    let caption = String(body.caption ?? "").trim();
    const transcript = String(body.transcript ?? body.audio_text ?? "").trim();
    let imageUrl = imageUrlIn;
    let textSource: "transcript" | "caption" | "fetched" = "caption";

    // AMM-Flow: nur URL → Server holt Caption (wenn noch schwach)
    if (caption.length < 40 && sourceUrl && transcript.length < 40) {
      const fetched = await fetchCaptionFromUrl(sourceUrl, platform);
      if (fetched.caption.length > caption.length) {
        caption = fetched.caption;
        textSource = "fetched";
      }
      if (!imageUrl && fetched.imageUrl) imageUrl = fetched.imageUrl;
    }

    // Transkript (Audio aus Video) hat Vorrang, wenn Caption schwach ist
    // → deckt Reels mit Score 0 / kurzer Caption ab (AMM-ähnlich)
    let recipeText = caption;
    if (transcript.length >= 40 && scoreCaption(transcript) >= scoreCaption(caption)) {
      recipeText = transcript;
      textSource = "transcript";
    } else if (transcript.length >= 80 && caption.length < 60) {
      // Caption nur Titel/Emoji → Transkript trotzdem nutzen
      recipeText = [caption, transcript].filter(Boolean).join("\n\n");
      textSource = "transcript";
    }

    if (recipeText.length < 20) {
      return json({
        error: "caption too short",
        detail: sourceUrl
          ? "Could not fetch caption from URL; client should retry with local caption or transcript"
          : "Provide caption, transcript, or sourceUrl",
      }, 400);
    }

    const clipped = recipeText.slice(0, 12000);
    const sourceLabel = textSource === "transcript"
      ? "spoken video transcript (and caption if present)"
      : "caption";
    const userMsg =
      `Platform: ${platform}\nSource: ${sourceUrl || "n/a"}\nExtract recipe from this ${sourceLabel}:\n\n${clipped}`;

    const groqResp = await fetch(GROQ_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        temperature: 0.1,
        max_tokens: 2500,
        messages: [
          { role: "system", content: SYSTEM },
          { role: "user", content: userMsg },
        ],
      }),
    });

    const groqText = await groqResp.text();
    if (!groqResp.ok) {
      return json(
        { error: `Groq ${groqResp.status}`, detail: groqText.slice(0, 400) },
        502,
      );
    }

    const choice = JSON.parse(groqText)?.choices?.[0]?.message?.content ?? "";
    const cleaned = choice
      .trim()
      .replace(/^```json\s*/i, "")
      .replace(/^```\s*/i, "")
      .replace(/\s*```$/i, "")
      .trim();

    const parsed = JSON.parse(cleaned);

    const sections = Array.isArray(parsed.ingredient_sections)
      ? parsed.ingredient_sections
      : [];
    const ingredientLines: string[] = [];
    for (const sec of sections) {
      const name = (sec.section_name ?? "").toString().trim();
      if (name) ingredientLines.push(name);
      const items = Array.isArray(sec.items) ? sec.items : [];
      for (const it of items) {
        const line = typeof it === "string"
          ? it
          : [it?.quantity, it?.unit, it?.name].filter(Boolean).join(" ");
        if (line.trim()) ingredientLines.push(`• ${line.trim()}`);
      }
    }

    const servings = Math.max(1, Number(parsed.servings) || 1);

    const mealCat = String(parsed.meal_category ?? "").toUpperCase().trim();
    const allowedCats = [
      "BREAKFAST",
      "MAIN",
      "SIDE_SNACK",
      "DESSERT",
      "DRINK",
      "SAUCE",
      "OTHER",
    ];

    return json({
      title: String(parsed.title ?? "Rezept").slice(0, 120),
      description: String(parsed.description ?? ""),
      ingredients: ingredientLines.join("\n"),
      instructions: String(parsed.instructions ?? ""),
      servings,
      meal_category: allowedCats.includes(mealCat) ? mealCat : "",
      prep_time_minutes: numOrNull(parsed.prep_time_minutes),
      // Nährwerte bewusst NICHT vom Server – Nutzer verifiziert in der App
      tags: String(parsed.tags ?? platform).slice(0, 200),
      sourceUrl: sourceUrl || null,
      platform,
      imageUrl,
      caption_chars: caption.length,
      caption_score: scoreCaption(caption),
      transcript_chars: transcript.length,
      text_source: textSource,
      normalized_by: textSource === "transcript"
        ? "recipe-normalize@groq+transcript"
        : "recipe-normalize@groq+fetch",
    });
  } catch (e) {
    return json(
      { error: "normalize failed", detail: String(e).slice(0, 300) },
      500,
    );
  }
});

/** Score: recipe-like text beats mere length (oEmbed title alone scores low). */
function scoreCaption(text: string): number {
  if (!text || text.length < 30) return 0;
  let s = Math.min(text.length / 20, 40);
  const qty = (text.match(
    /\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|cas|cac|tbsp|tsp|cup|stück|c\.\s*à)/gi,
  ) || []).length;
  s += Math.min(qty * 8, 40);
  const lower = text.toLowerCase();
  for (
    const m of [
      "zutaten",
      "ingredient",
      "zubereitung",
      "pour la",
      "topping",
      "recette",
      "method",
      "c. à soupe",
      "overnight",
    ]
  ) {
    if (lower.includes(m)) s += 5;
  }
  // Login walls / chrome
  if (lower.includes("log in") || lower.includes("sign up")) s -= 20;
  if (lower.includes("content-security-policy")) s -= 50;
  return Math.max(0, s);
}

/** Parallel multi-source caption fetch; best score wins. */
async function fetchCaptionFromUrl(
  url: string,
  platform: string,
): Promise<{ caption: string; imageUrl: string | null }> {
  const controllers: AbortController[] = [];
  const timeout = (ms: number) => {
    const c = new AbortController();
    controllers.push(c);
    setTimeout(() => c.abort(), ms);
    return c.signal;
  };

  const jina = async (target: string, ms = 9000) => {
    const r = await fetch(`https://r.jina.ai/${target}`, {
      signal: timeout(ms),
      headers: {
        Accept: "text/plain",
        "X-Return-Format": "text",
        "User-Agent": "Mozilla/5.0 NutriSnapBot/1.0",
      },
    });
    const text = await r.text();
    return { caption: cleanFetchedText(text), imageUrl: null as string | null };
  };

  const jobs: Promise<{ caption: string; imageUrl: string | null }>[] = [];
  jobs.push(jina(url, 9000));

  const isIg = platform.includes("instagram") || url.includes("instagram");
  if (isIg) {
    const m = url.match(/instagram\.com\/(?:reel|p|tv)\/([A-Za-z0-9_-]+)/i);
    const code = m?.[1] ?? null;

    jobs.push(
      (async () => {
        const oe =
          `https://api.instagram.com/oembed/?url=${
            encodeURIComponent(url)
          }&omitscript=true`;
        const r = await fetch(oe, { signal: timeout(5000) });
        const j = await r.json();
        return {
          caption: cleanFetchedText(String(j.title ?? "")),
          imageUrl: j.thumbnail_url ? String(j.thumbnail_url) : null,
        };
      })(),
    );

    if (code) {
      const variants = [
        `https://www.instagram.com/p/${code}/`,
        `https://www.instagram.com/reel/${code}/`,
        `https://www.instagram.com/p/${code}/embed/captioned/`,
        `https://www.instagram.com/reel/${code}/embed/captioned/`,
        `https://ddinstagram.com/p/${code}/`,
        `https://ddinstagram.com/reel/${code}/`,
        `https://imginn.com/p/${code}/`,
        `https://www.picuki.com/media/${code}`,
      ];
      for (const v of variants) {
        jobs.push(jina(v, 8000));
      }
    } else {
      const dd = url
        .replace("www.instagram.com", "ddinstagram.com")
        .replace("instagram.com", "ddinstagram.com");
      if (dd !== url) jobs.push(jina(dd, 8000));
    }
  }

  if (platform.includes("tiktok") || url.includes("tiktok")) {
    jobs.push(
      (async () => {
        const oe =
          `https://www.tiktok.com/oembed?url=${encodeURIComponent(url)}`;
        const r = await fetch(oe, { signal: timeout(5000) });
        const j = await r.json();
        return {
          caption: cleanFetchedText(
            [j.title, j.author_name].filter(Boolean).join("\n"),
          ),
          imageUrl: j.thumbnail_url ? String(j.thumbnail_url) : null,
        };
      })(),
    );
  }

  const results = await Promise.allSettled(jobs);
  controllers.forEach((c) => {
    try {
      c.abort();
    } catch {
      /* ignore */
    }
  });

  let best = { caption: "", imageUrl: null as string | null, score: -1 };
  for (const r of results) {
    if (r.status !== "fulfilled") continue;
    const c = r.value;
    const sc = scoreCaption(c.caption);
    if (sc > best.score) {
      best = { caption: c.caption, imageUrl: c.imageUrl ?? best.imageUrl, score: sc };
    } else if (!best.imageUrl && c.imageUrl) {
      best.imageUrl = c.imageUrl;
    }
  }
  return { caption: best.caption, imageUrl: best.imageUrl };
}

function cleanFetchedText(raw: string): string {
  let t = raw
    .replace(/\r/g, "")
    .replace(/https?:\/\/\S+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  // Drop jina chrome lines
  t = t
    .split("\n")
    .filter((line) => {
      const l = line.toLowerCase();
      if (l.startsWith("title:") && l.length < 40) return false;
      if (l.startsWith("url source:")) return false;
      if (l.includes("markdown content")) return false;
      return true;
    })
    .join("\n")
    .trim();
  if (scoreCaption(t) < 15 && t.length > 4000) t = t.slice(0, 4000);
  return t.slice(0, 12000);
}

function numOrNull(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}
