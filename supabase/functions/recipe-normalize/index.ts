/**
 * NutriSnap – kostenlose Server-Normalisierung für Social-Media-Rezepte.
 *
 * Input:  { caption, sourceUrl?, platform?, imageUrl? }
 * Output: strukturiertes Rezept-JSON (Titel, Zutaten, Schritte, Makros)
 *
 * Secrets (supabase secrets set):
 *   GROQ_API_KEY=...
 *
 * Deploy:
 *   supabase functions deploy recipe-normalize
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
    { "section_name": "Crust", "items": ["60 g cottage cheese", "80 ml egg whites"] }
  ],
  "instructions": "1. ...\\n2. ...",
  "tags": "high-protein,pizza"
}

Rules:
- title: DISH NAME only. NEVER promotional text, "Comment recipe", "Kommentiere", "DM me",
  "Who knew she was a chef", full first paragraph. Prefer food names near ingredients.
  If only promo exists, invent short name from main ingredients (e.g. "Protein Cottage Cheese Pizza").
- ingredient_sections: group by headers (The crust, Toppings, ZUTATEN Teig, Sauce).
  Each item ONE ingredient as "quantity unit name". Keep original units if metric;
  for US volumes prefer original (1/4 cup, 1 tsp) — do not invent grams.
- NEVER put cooking steps into items (no "Preheat", "Mix", "Bake", "1.) ...").
- NEVER put engagement bait, hashtags, outfit credits, "Save this" into items or title.
- instructions: numbered cooking steps only. No ingredient lists, no hashtags, no promo.
- servings: from "Makes N", "N Portionen", "serves N". Default 1.
- calories_per_serving / protein_g / carbs_g / fat_g: per serving if stated, else null.
- Ignore: "Comment X for recipe", "Kommentiere …", "link in bio", hashtags, ads.

Hard caption examples:
1) EN cups + promo: "Another Hailey recipe & it slaps!! … INGREDIENTS: The crust 1/4 cup cottage cheese … 1.) Preheat oven…"
   → title "Protein Cottage Cheese Pizza", sections Crust/Toppings, numbered steps, no promo title.
2) DE emoji: "📘 ZUTATEN Teig: 🔹 380g Dinkelmehl … ZUBEREITUNG: 1. verkneten…"
   → sections Teig/Belag, German steps only.
3) Bait-only title: "Comment recipe & I'll DM you…" with ingredient names below
   → invent dish title from ingredients; never use bait as title.
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
    const caption = String(body.caption ?? "").trim();
    if (caption.length < 20) {
      return json({ error: "caption too short" }, 400);
    }

    // Cap size — free tier / cost control
    const clipped = caption.slice(0, 12000);
    const platform = String(body.platform ?? "instagram");
    const sourceUrl = body.sourceUrl ? String(body.sourceUrl) : null;
    const imageUrl = body.imageUrl ? String(body.imageUrl) : null;

    const userMsg =
      `Platform: ${platform}\nExtract recipe from this caption:\n\n${clipped}`;

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

    // Build flat ingredients string (same convention as app)
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
    const cals = numOrNull(parsed.calories_per_serving);
    const protein = numOrNull(parsed.protein_g);
    const carbs = numOrNull(parsed.carbs_g);
    const fat = numOrNull(parsed.fat_g);

    return json({
      title: String(parsed.title ?? "Rezept").slice(0, 120),
      description: String(parsed.description ?? ""),
      ingredients: ingredientLines.join("\n"),
      instructions: String(parsed.instructions ?? ""),
      servings,
      calories_per_serving: cals,
      protein_g: protein,
      carbs_g: carbs,
      fat_g: fat,
      prep_time_minutes: numOrNull(parsed.prep_time_minutes),
      tags: String(parsed.tags ?? platform).slice(0, 200),
      sourceUrl,
      platform,
      imageUrl,
      normalized_by: "recipe-normalize@groq",
    });
  } catch (e) {
    return json(
      { error: "normalize failed", detail: String(e).slice(0, 300) },
      500,
    );
  }
});

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
