-- Einmalig in Supabase SQL Editor ausfuehren, um bestehende Duplikate zu entfernen,
-- die durch den lokal_id-Sync-Bug entstanden sind (siehe SyncManager.kt / SupabaseSync.kt).
-- Behaelt pro Duplikat-Gruppe die AELTESTE Zeile (kleinste id), loescht den Rest.
-- WICHTIG: Vor dem Ausfuehren ein Backup/Snapshot der Tabellen machen.

-- Rezepte: Duplikate = gleicher user_id + title + total_calories + prep_time_minutes
delete from recipes r
using recipes r2
where r.user_id = r2.user_id
  and r.title = r2.title
  and coalesce(r.total_calories, -1) = coalesce(r2.total_calories, -1)
  and coalesce(r.prep_time_minutes, -1) = coalesce(r2.prep_time_minutes, -1)
  and r.id > r2.id;

-- Diary-Eintraege: Duplikate = gleicher user_id + food_name + date_str + meal_type + amount_grams
delete from diary_entries d
using diary_entries d2
where d.user_id = d2.user_id
  and d.food_name = d2.food_name
  and d.date_str = d2.date_str
  and d.meal_type = d2.meal_type
  and d.amount_grams = d2.amount_grams
  and d.id > d2.id;
