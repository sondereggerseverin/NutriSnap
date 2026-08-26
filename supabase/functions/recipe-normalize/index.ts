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

const SYSTEM = `You are a recipe extraction assistant. Convert ANY social-media caption
(German or English, emoji bullets, cups/tbsp or grams, engagement bait) into ONE strict JSON object.
Respond ONLY with valid JSON — no markdown, no explanation.

Schema:
{
  "title": "Dish name only",
  "description": "1-2 sentences or empty string",
  "servings": 1,
  "calories_per_serving": null,
  "protein_g": null,
  "carbs_g": null,
  "fat_g": null,
  "prep_time_minutes": null,
  "ingredient_sections": [
    { "section_name": "Basis", "items": ["60 g Hafermehl", "50 g Joghurt"] },
    { "section_name": "Topping", "items": ["Haselnussmus nach Bedarf", "Schokolade nach Bedarf"] }
  ],
  "instructions": "1. ...\\n2. ...",
  "tags": "overnight-oats,breakfast"
}

Rules:
- title: DISH NAME only. NEVER promotional text ("Folgt mir", "Tag 12/30", "Comment recipe",
  "Kommentiere", "DM me", "unter 2€ Reihe"). Prefer names near ingredients or first food line.
  If only promo exists, invent short name from main ingredients (e.g. "Bueno Overnight Oats").
- ingredient_sections: group by headers (Zutaten, Basis, Topping, The crust, Sauce, Icing).
  Each item ONE ingredient as "quantity unit name". Keep metric (g, ml, EL, TL) when present.
  Include optional lines as normal items with "optional:" prefix if marked.
  "Milch nach Wahl" / "nach Bedarf" are valid ingredients — keep them.
- NEVER put cooking steps into items (no "Preheat", "Mix", "vermischen", "1.) ...").
- NEVER put engagement bait, hashtags, music credits, "Save this" into items or title.
- instructions: numbered cooking steps only. Split long blobs into 1. 2. 3. when possible.
- servings: from "Makes N", "N Portionen", "serves N", "für eine Portion". Default 1.
- calories_per_serving / protein_g / carbs_g / fat_g: per serving if stated, else null.
- Ignore: "Folgt mir", "Tag X/30", hashtags, ads, outfit credits.
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

    return json({
      title: String(parsed.title ?? "Rezept").slice(0, 120),
      description: String(parsed.description ?? ""),
      ingredients: ingredientLines.join("\n"),
      instructions: String(parsed.instructions ?? ""),
      servings,
      calories_per_serving: numOrNull(parsed.calories_per_serving),
      protein_g: numOrNull(parsed.protein_g),
      carbs_g: numOrNull(parsed.carbs_g),
      fat_g: numOrNull(parsed.fat_g),
      prep_time_minutes: numOrNull(parsed.prep_time_minutes),
      tags: String(parsed.tags ?? platform).slice(0, 200),
      sourceUrl: sourceUrl || null,
      platform,
      imageUrl,
      caption_chars: caption.length,
      normalized_by: "recipe-normalize@groq+fetch",
    });
  } catch (e) {
    return json(
      { error: "normalize failed", detail: String(e).slice(0, 300) },
      500,
    );
  }
});

/** Parallel caption fetch – first usable wins (AMM-style server side). */
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

  const jobs: Promise<{ caption: string; imageUrl: string | null }>[] = [];

  // 1) Jina Reader (often gets full IG/TikTok text)
  jobs.push(
    (async () => {
      const jinaUrl = `https://r.jina.ai/${url}`;
      const r = await fetch(jinaUrl, {
        signal: timeout(8000),
        headers: {
          Accept: "text/plain",
          "X-Return-Format": "text",
        },
      });
      const text = await r.text();
      return { caption: cleanFetchedText(text), imageUrl: null };
    })(),
  );

  // 2) Instagram oEmbed
  if (platform.includes("instagram") || url.includes("instagram")) {
    jobs.push(
      (async () => {
        const oe =
          `https://api.instagram.com/oembed/?url=${encodeURIComponent(url)}&omitscript=true`;
        const r = await fetch(oe, { signal: timeout(5000) });
        const j = await r.json();
        const title = String(j.title ?? "");
        const thumb = j.thumbnail_url ? String(j.thumbnail_url) : null;
        return { caption: cleanFetchedText(title), imageUrl: thumb };
      })(),
    );

    // 3) ddinstagram mirror
    const dd = url
      .replace("www.instagram.com", "ddinstagram.com")
      .replace("instagram.com", "ddinstagram.com");
    if (dd !== url) {
      jobs.push(
        (async () => {
          const r = await fetch(`https://r.jina.ai/${dd}`, {
            signal: timeout(7000),
            headers: { Accept: "text/plain" },
          });
          const text = await r.text();
          return { caption: cleanFetchedText(text), imageUrl: null };
        })(),
      );
    }
  }

  // 4) TikTok: oEmbed + jina already covered; try vm.tiktok expand via jina only

  const results = await Promise.allSettled(jobs);
  // cancel leftovers
  controllers.forEach((c) => {
    try {
      c.abort();
    } catch {
      /* ignore */
    }
  });

  let best = { caption: "", imageUrl: null as string | null };
  for (const r of results) {
    if (r.status !== "fulfilled") continue;
    const c = r.value;
    if (c.caption.length > best.caption.length) {
      best = { caption: c.caption, imageUrl: c.imageUrl ?? best.imageUrl };
    } else if (!best.imageUrl && c.imageUrl) {
      best.imageUrl = c.imageUrl;
    }
  }
  return best;
}

function cleanFetchedText(raw: string): string {
  let t = raw
    .replace(/\r/g, "")
    .replace(/https?:\/\/\S+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  // Drop obvious page chrome from jina
  const markers = [
    "Zutaten:",
    "Ingredients",
    "Zubereitung:",
    "Method",
    "INGREDIENTS",
  ];
  // Prefer keeping full text if it looks like a recipe
  const lower = t.toLowerCase();
  const hasRecipe = markers.some((m) => lower.includes(m.toLowerCase())) ||
    /\d+\s*g\b/i.test(t) ||
    /\d+\s*el\b/i.test(t);
  if (!hasRecipe && t.length > 4000) t = t.slice(0, 4000);
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
