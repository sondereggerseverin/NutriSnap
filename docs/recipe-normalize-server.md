# Server-Normalisierung (kostenlos)

AMM-ähnliche Pipeline: Caption wird **serverseitig** mit Groq (Free-Tier) in ein festes Rezept-Schema überführt.

## Architektur

```
App (Caption via WebView/HTTP)
  → POST /functions/v1/recipe-normalize
  → Supabase Edge Function (Deno)
  → Groq llama-3.3-70b-versatile
  → strukturiertes JSON
  → App (Fallback: lokaler Parser)
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

Die App nutzt bereits `SUPABASE_URL` + `SUPABASE_ANON_KEY` aus dem Build.
Ohne deployed Function fällt der Import still auf den lokalen Parser zurück.

## Kosten

| Komponente | Kosten |
|---|---|
| Supabase Edge Functions | Free-Tier (500k Invocations/Monat) |
| Groq llama-3.3-70b | Free-Tier (RPM-Limits) |
| App-seitig | nur Caption-Fetch wie bisher |

## Test

```bash
curl -X POST "$SUPABASE_URL/functions/v1/recipe-normalize" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"caption":"INGREDIENTS:\n1/4 cup cottage cheese\n1/3 cup egg whites\n1.) Preheat oven to 350...","platform":"instagram"}'
```
