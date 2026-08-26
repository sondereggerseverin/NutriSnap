# Server-Import wie All My Meals (kostenlos)

AMM-ähnliche Pipeline: **nur der Link** geht an den Server. Caption-Fetch + Strukturierung laufen serverseitig (Groq Free-Tier). Die App zeigt das fertige Rezept – typisch in wenigen Sekunden statt 25–30 s WebView-Race.

## Architektur

```
App
  → POST /functions/v1/recipe-normalize  { sourceUrl, platform }
  → Supabase Edge Function
       1) Caption holen (Jina, oEmbed, Mirrors) parallel
       2) Groq llama-3.3-70b → festes JSON-Schema
  → strukturiertes Rezept
  → bei Fehler: App fällt auf lokalen WebView-Parser zurück
```

## Einmalig deployen

Voraussetzung: [Supabase CLI](https://supabase.com/docs/guides/cli) + Projekt-Login.

```bash
# Im Repo-Root
supabase login
supabase link --project-ref <DEIN_PROJECT_REF>

# Groq-Key als Secret (kostenloser Key von console.groq.com)
supabase secrets set GROQ_API_KEY=gsk_...

# Function deployen
supabase functions deploy recipe-normalize
```

Die App nutzt bereits `SUPABASE_URL` + `SUPABASE_ANON_KEY` aus dem Build
(`local.properties` / CI-Secrets).

Ohne deployed Function fällt der Import still auf den lokalen Parser zurück
(WebView + Geräte-Groq) – dann wieder ~20–30 s möglich.

## Kosten

| Komponente | Kosten |
|---|---|
| Supabase Edge Functions | Free-Tier (500k Invocations/Monat) |
| Groq llama-3.3-70b | Free-Tier (RPM-Limits) |
| Jina / oEmbed Caption-Fetch | kostenlos |

## Test

Nur URL (AMM-Pfad):

```bash
curl -X POST "$SUPABASE_URL/functions/v1/recipe-normalize" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"sourceUrl":"https://www.instagram.com/reel/DceOJSCSuks/","platform":"instagram"}'
```

Mit Caption (Fallback):

```bash
curl -X POST "$SUPABASE_URL/functions/v1/recipe-normalize" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"caption":"Zutaten:\n60g Hafermehl\n50g Joghurt\n…","platform":"instagram"}'
```
