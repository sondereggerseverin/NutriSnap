/**
 * NutriSnap – AMM-ähnlicher Server-Import (kostenlos).
 *
 * Input:
 *   { sourceUrl, platform?, caption?, imageUrl? }
 *   – sourceUrl allein reicht: Server holt Caption selbst
 *   – caption optional (Client-Fallback / Cache)
 *
 * Output: strukturiertes Rezept-JSON
 *
 * Secrets: supabase secrets set GROQ_API_KEY=...
 * Deploy:  supabase functions deploy recipe-normalize
 */
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODEL = "llama-3.3-70b-versatile";

const SYSTEM = `You are a recipe extraction assistant for a German nutrition app (NutriSnap).
Convert ANY social-media caption (German, English, OR French; emoji bullets; cups/tbsp/c.à soupe)
into ONE strict JSON object. Respond ONLY with valid JSON — no markdown.

Schema:
{
  "title": "German dish name only",
  "description": "1-2 German sentences or empty",
  "servings": 2,
  "meal_category": "DESSERT",
  "prep_time_minutes": null,
  "ingredient_sections": [
    { "section_name": "Creme", "items": ["4 EL Skyr", "30 g Vanille-Whey"] },
    { "section_name": "Tiramisu", "items": ["3 Stück Reiswaffeln", "Kaffee nach Bedarf"] }
  ],
  "instructions": "1. ...\\n2. ...",
  "tags": "dessert,high-protein"
}

Rules:
- OUTPUT LANGUAGE: German for title, section names, ingredient NAMES, and instructions.
- UNITS: Never invent grams for spoons/pieces.
  FR: "c. à soupe" / cas → EL; "c. à café" / cac → TL; "galettes" → Stück; "15 à 20 g" → "15-20 g".
  EN: tbsp→EL, tsp→TL, cups→g/ml with density when known.
- Extract EVERY ingredient. Sections from headers (Pour la crème / Pour 1 tiramisu / Topping).
- If caption has NO cooking steps: GENERATE 5–8 realistic German steps (like All My Meals).
- servings: CAREFUL — "pour 2 tiramisus individuels", "für 2", "makes 2", "2 Portionen",
  "Serves 2", "Rezept für 2" → 2. "eine Portion" / single jar → 1. Default 1 only if unclear.
  Do NOT use ingredient counts as servings.
- meal_category: one of BREAKFAST | MAIN | SIDE_SNACK | DESSERT | DRINK | SAUCE | OTHER.
  Overnight oats/porridge/müsli → BREAKFAST. Tiramisu/Kuchen/Pudding/Mousse → DESSERT.
  Herzhaftes mit Fleisch/Pasta/Bowl → MAIN.
- title: dish only, German if possible ("High-Protein-Tiramisu", not promo).
- tags: include meal type keywords. Do NOT invent nutrition numbers.
- NEVER engagement bait, hashtags, @mentions in ingredients.

French example:
"Pour la crème: 4 c. à soupe de Skyr, 30 g de whey, 1 c. à café de miel, 150 g de blancs d'œufs
Pour 1 tiramisu: 3 galettes de riz, Café, 15 à 20 g de pâte de spéculoos, 1 spéculoos"
→ Creme: 4 EL Skyr, 30 g Vanille-Whey, 1 TL Honig, 150 g Eiklar
→ Tiramisu: 3 Stück Reiswaffeln, Kaffee nach Bedarf, 15-20 g Spekulatiuscreme, 1 Stück Spekulatius
→ instructions: generated German assembly steps; servings: 2
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
    let imageUrl = imageUrlIn;

    // AMM-Flow: nur URL → Server holt Caption
    if (caption.length < 40 && sourceUrl) {
      const fetched = await fetchCaptionFromUrl(sourceUrl, platform);
      if (fetched.caption.length > caption.length) caption = fetched.caption;
      if (!imageUrl && fetched.imageUrl) imageUrl = fetched.imageUrl;
    }

    if (caption.length < 20) {
      return json({
        error: "caption too short",
        detail: sourceUrl
          ? "Could not fetch caption from URL; client should retry with local caption"
          : "Provide caption or sourceUrl",
      }, 400);
    }

    const clipped = caption.slice(0, 12000);
    const userMsg =
      `Platform: ${platform}\nSource: ${sourceUrl || "n/a"}\nExtract recipe from this caption:\n\n${clipped}`;

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
      normalized_by: "recipe-normalize@groq+fetch",
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
