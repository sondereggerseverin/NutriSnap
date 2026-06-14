
import { useState, useEffect, useRef, useCallback } from "react";

// ─── THEME & TOKENS ───────────────────────────────────────────────────────────
const COLORS = {
  primary: "#2D7D46",       // forest green
  primaryLight: "#4CAF70",
  primaryDark: "#1B5E35",
  accent: "#F4A225",        // warm amber
  accentLight: "#FFD67A",
  bg: "#F7F8F5",            // warm off-white
  card: "#FFFFFF",
  surface: "#EFF3ED",
  text: "#1A2B1E",
  textMid: "#4A6450",
  textLight: "#8BA891",
  danger: "#E05252",
  dangerLight: "#FDE8E8",
  border: "#DDE8DC",
  protein: "#4B8BF5",
  carb: "#F4A225",
  fat: "#E05252",
  fiber: "#2D7D46",
  breakfast: "#FF9B45",
  lunch: "#4B8BF5",
  dinner: "#A259FF",
  snack: "#2D7D46",
};

// ─── MOCK FOOD DATABASE ───────────────────────────────────────────────────────
const FOOD_DB = [
  { id: "1", name: "Haferflocken", brand: "Kölln", kcal: 372, protein: 13.5, carbs: 58.7, fat: 7.1, fiber: 10.0, sugar: 1.1, sodium: 0.005, serving: 100, servingUnit: "g", barcode: "4006180045050" },
  { id: "2", name: "Vollmilch 3,5%", brand: "Weihenstephan", kcal: 65, protein: 3.3, carbs: 4.7, fat: 3.5, fiber: 0, sugar: 4.7, sodium: 0.05, serving: 100, servingUnit: "ml", barcode: "4004950060090" },
  { id: "3", name: "Banane", brand: "", kcal: 88, protein: 1.1, carbs: 22.8, fat: 0.3, fiber: 2.6, sugar: 17.0, sodium: 0.001, serving: 120, servingUnit: "g" },
  { id: "4", name: "Hühnerbrust gegrillt", brand: "", kcal: 165, protein: 31.0, carbs: 0, fat: 3.6, fiber: 0, sugar: 0, sodium: 0.074, serving: 100, servingUnit: "g" },
  { id: "5", name: "Brauner Reis", brand: "Uncle Ben's", kcal: 370, protein: 7.5, carbs: 77.0, fat: 2.9, fiber: 2.8, sugar: 0.8, sodium: 0.003, serving: 100, servingUnit: "g" },
  { id: "6", name: "Lachs", brand: "", kcal: 208, protein: 20.0, carbs: 0, fat: 13.0, fiber: 0, sugar: 0, sodium: 0.059, serving: 100, servingUnit: "g" },
  { id: "7", name: "Griechischer Joghurt 0%", brand: "Ehrmann", kcal: 57, protein: 10.0, carbs: 4.0, fat: 0.2, fiber: 0, sugar: 4.0, sodium: 0.05, serving: 100, servingUnit: "g" },
  { id: "8", name: "Ei (Hühnerei)", brand: "", kcal: 155, protein: 13.0, carbs: 1.1, fat: 11.0, fiber: 0, sugar: 1.1, sodium: 0.124, serving: 60, servingUnit: "g" },
  { id: "9", name: "Avocado", brand: "", kcal: 160, protein: 2.0, carbs: 9.0, fat: 15.0, fiber: 7.0, sugar: 0.7, sodium: 0.007, serving: 150, servingUnit: "g" },
  { id: "10", name: "Vollkornbrot", brand: "Mestemacher", kcal: 241, protein: 8.5, carbs: 41.0, fat: 2.2, fiber: 7.5, sugar: 3.0, sodium: 0.38, serving: 50, servingUnit: "g" },
  { id: "11", name: "Mandeln", brand: "", kcal: 576, protein: 21.0, carbs: 22.0, fat: 50.0, fiber: 12.5, sugar: 4.0, sodium: 0.001, serving: 30, servingUnit: "g" },
  { id: "12", name: "Apfel", brand: "", kcal: 52, protein: 0.3, carbs: 14.0, fat: 0.2, fiber: 2.4, sugar: 10.0, sodium: 0.001, serving: 150, servingUnit: "g" },
  { id: "13", name: "Linsen gekocht", brand: "", kcal: 116, protein: 9.0, carbs: 20.0, fat: 0.4, fiber: 7.9, sugar: 1.8, sodium: 0.002, serving: 100, servingUnit: "g" },
  { id: "14", name: "Quark Magerquark", brand: "Bauer", kcal: 68, protein: 12.0, carbs: 3.5, fat: 0.2, fiber: 0, sugar: 3.5, sodium: 0.04, serving: 100, servingUnit: "g" },
  { id: "15", name: "Süßkartoffel", brand: "", kcal: 86, protein: 1.6, carbs: 20.0, fat: 0.1, fiber: 3.0, sugar: 4.2, sodium: 0.055, serving: 100, servingUnit: "g" },
  { id: "16", name: "Thunfisch in Wasser", brand: "Followfish", kcal: 108, protein: 23.5, carbs: 0, fat: 1.0, fiber: 0, sugar: 0, sodium: 0.29, serving: 100, servingUnit: "g" },
  { id: "17", name: "Kokosmilch", brand: "AROY-D", kcal: 197, protein: 2.0, carbs: 6.0, fat: 19.0, fiber: 0, sugar: 4.0, sodium: 0.015, serving: 100, servingUnit: "ml" },
  { id: "18", name: "Spinat roh", brand: "", kcal: 23, protein: 2.9, carbs: 3.6, fat: 0.4, fiber: 2.2, sugar: 0.4, sodium: 0.079, serving: 100, servingUnit: "g" },
  { id: "19", name: "Olivenöl extra virgin", brand: "", kcal: 884, protein: 0, carbs: 0, fat: 100, fiber: 0, sugar: 0, sodium: 0, serving: 10, servingUnit: "ml" },
  { id: "20", name: "Protein Powder Chocolate", brand: "Whey Gold", kcal: 402, protein: 80.0, carbs: 7.0, fat: 5.5, fiber: 2.0, sugar: 3.0, sodium: 0.2, serving: 30, servingUnit: "g" },
];

// ─── SAMPLE RECIPES ───────────────────────────────────────────────────────────
const SAMPLE_RECIPES = [
  {
    id: "r1", name: "Overnight Oats", category: "Breakfast", tags: ["vegan", "meal-prep"],
    totalTime: 10, servings: 1,
    image: "🥣",
    ingredients: [
      { foodId: "1", name: "Haferflocken", amount: 80, unit: "g" },
      { foodId: "2", name: "Vollmilch", amount: 200, unit: "ml" },
      { foodId: "3", name: "Banane", amount: 80, unit: "g" },
    ],
    instructions: ["Haferflocken in eine Schüssel geben.", "Milch dazugießen und umrühren.", "Banane in Scheiben schneiden und obendrauf legen.", "Über Nacht im Kühlschrank ziehen lassen."],
    kcal: 450, protein: 15, carbs: 72, fat: 10,
  },
  {
    id: "r2", name: "Hähnchen Quinoa Bowl", category: "Lunch", tags: ["high-protein", "gluten-free"],
    totalTime: 25, servings: 1,
    image: "🥗",
    ingredients: [
      { foodId: "4", name: "Hühnerbrust", amount: 150, unit: "g" },
      { foodId: "18", name: "Spinat", amount: 80, unit: "g" },
      { foodId: "9", name: "Avocado", amount: 80, unit: "g" },
    ],
    instructions: ["Hähnchenbrust in der Pfanne anbraten.", "Spinat kurz mitgaren.", "Avocado schneiden.", "Alles in einer Schüssel anrichten."],
    kcal: 480, protein: 52, carbs: 12, fat: 24,
  },
  {
    id: "r3", name: "Lachs mit Süßkartoffel", category: "Dinner", tags: ["omega-3", "healthy"],
    totalTime: 30, servings: 2,
    image: "🐟",
    ingredients: [
      { foodId: "6", name: "Lachs", amount: 200, unit: "g" },
      { foodId: "15", name: "Süßkartoffel", amount: 300, unit: "g" },
      { foodId: "19", name: "Olivenöl", amount: 15, unit: "ml" },
    ],
    instructions: ["Süßkartoffel schälen und in Würfel schneiden.", "Im Ofen bei 200°C 25 Min backen.", "Lachs würzen und 4 Min pro Seite braten."],
    kcal: 560, protein: 44, carbs: 45, fat: 20,
  },
];

// ─── INITIAL STATE ────────────────────────────────────────────────────────────
const today = new Date().toISOString().split("T")[0];

const DEFAULT_GOALS = { kcal: 2000, protein: 150, carbs: 220, fat: 65, fiber: 30 };

const INITIAL_STATE = {
  diary: {
    [today]: {
      breakfast: [],
      lunch: [
        { id: "e1", foodId: "4", name: "Hühnerbrust gegrillt", amount: 150, kcal: 247, protein: 46.5, carbs: 0, fat: 5.4, fiber: 0 },
        { id: "e2", foodId: "5", name: "Brauner Reis", amount: 100, kcal: 370, protein: 7.5, carbs: 77, fat: 2.9, fiber: 2.8 },
      ],
      dinner: [],
      snack: [],
    },
  },
  goals: DEFAULT_GOALS,
  weight: [
    { date: "2026-06-07", value: 78.5 },
    { date: "2026-06-08", value: 78.3 },
    { date: "2026-06-09", value: 78.6 },
    { date: "2026-06-10", value: 78.1 },
    { date: "2026-06-11", value: 77.9 },
    { date: "2026-06-12", value: 77.8 },
    { date: "2026-06-13", value: 77.5 },
    { date: "today", value: 77.4 },
  ],
  streak: 7,
  favorites: ["1", "4", "7"],
  recipes: SAMPLE_RECIPES,
  customFoods: [],
};

// ─── HELPERS ──────────────────────────────────────────────────────────────────
function calcNutrients(foodId, amount) {
  const f = FOOD_DB.find((x) => x.id === foodId);
  if (!f) return { kcal: 0, protein: 0, carbs: 0, fat: 0, fiber: 0 };
  const ratio = amount / f.serving;
  return {
    kcal: Math.round(f.kcal * ratio),
    protein: +(f.protein * ratio).toFixed(1),
    carbs: +(f.carbs * ratio).toFixed(1),
    fat: +(f.fat * ratio).toFixed(1),
    fiber: +(f.fiber * ratio).toFixed(1),
  };
}

function sumMeal(entries) {
  return entries.reduce(
    (acc, e) => ({ kcal: acc.kcal + e.kcal, protein: acc.protein + e.protein, carbs: acc.carbs + e.carbs, fat: acc.fat + e.fat, fiber: acc.fiber + e.fiber }),
    { kcal: 0, protein: 0, carbs: 0, fat: 0, fiber: 0 }
  );
}

function sumDay(dayDiary) {
  if (!dayDiary) return { kcal: 0, protein: 0, carbs: 0, fat: 0, fiber: 0 };
  const all = [...dayDiary.breakfast, ...dayDiary.lunch, ...dayDiary.dinner, ...dayDiary.snack];
  return sumMeal(all);
}

function uid() { return Math.random().toString(36).slice(2); }

// ─── MACRO RING ───────────────────────────────────────────────────────────────
function MacroRing({ eaten, goal, size = 120 }) {
  const pct = Math.min(eaten / goal, 1);
  const r = size / 2 - 8;
  const circ = 2 * Math.PI * r;
  const dash = pct * circ;
  const remaining = Math.max(goal - eaten, 0);
  return (
    <div style={{ position: "relative", width: size, height: size }}>
      <svg width={size} height={size}>
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={COLORS.border} strokeWidth={8} />
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={COLORS.primary} strokeWidth={8}
          strokeDasharray={`${dash} ${circ}`} strokeLinecap="round"
          transform={`rotate(-90 ${size/2} ${size/2})`} style={{ transition: "stroke-dasharray 0.5s ease" }} />
      </svg>
      <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
        <span style={{ fontSize: 22, fontWeight: 700, color: COLORS.text, lineHeight: 1 }}>{remaining}</span>
        <span style={{ fontSize: 10, color: COLORS.textLight, marginTop: 2 }}>kcal übrig</span>
      </div>
    </div>
  );
}

// ─── MACRO BAR ────────────────────────────────────────────────────────────────
function MacroBar({ label, value, goal, color }) {
  const pct = Math.min((value / goal) * 100, 100);
  return (
    <div style={{ flex: 1 }}>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: COLORS.textMid }}>{label}</span>
        <span style={{ fontSize: 11, color: COLORS.textLight }}>{value}g</span>
      </div>
      <div style={{ background: COLORS.border, borderRadius: 4, height: 6, overflow: "hidden" }}>
        <div style={{ width: `${pct}%`, height: "100%", background: color, borderRadius: 4, transition: "width 0.4s ease" }} />
      </div>
    </div>
  );
}

// ─── STREAK BADGE ─────────────────────────────────────────────────────────────
function StreakBadge({ streak }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 4, background: COLORS.accentLight, borderRadius: 20, padding: "4px 12px" }}>
      <span style={{ fontSize: 16 }}>🔥</span>
      <span style={{ fontSize: 14, fontWeight: 700, color: COLORS.primaryDark }}>{streak}</span>
    </div>
  );
}

// ─── PILL BUTTON ─────────────────────────────────────────────────────────────
function Pill({ children, active, onClick, color }) {
  return (
    <button onClick={onClick} style={{
      padding: "6px 14px", borderRadius: 20, border: "none", cursor: "pointer", fontSize: 13, fontWeight: 600,
      background: active ? (color || COLORS.primary) : COLORS.surface,
      color: active ? "#fff" : COLORS.textMid,
      transition: "all 0.15s"
    }}>{children}</button>
  );
}

// ─── MODAL ────────────────────────────────────────────────────────────────────
function Modal({ title, onClose, children }) {
  return (
    <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.45)", zIndex: 100, display: "flex", alignItems: "flex-end", justifyContent: "center" }}
      onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div style={{ background: COLORS.card, borderRadius: "20px 20px 0 0", width: "100%", maxWidth: 480, maxHeight: "90vh", overflow: "auto" }}>
        <div style={{ display: "flex", alignItems: "center", padding: "16px 20px 0", justifyContent: "space-between" }}>
          <h2 style={{ fontSize: 18, fontWeight: 700, color: COLORS.text, margin: 0 }}>{title}</h2>
          <button onClick={onClose} style={{ background: COLORS.surface, border: "none", borderRadius: 20, width: 32, height: 32, cursor: "pointer", fontSize: 18, color: COLORS.textMid }}>✕</button>
        </div>
        <div style={{ padding: "16px 20px 32px" }}>{children}</div>
      </div>
    </div>
  );
}

// ─── ADD FOOD MODAL ───────────────────────────────────────────────────────────
function AddFoodModal({ mealType, onAdd, onClose, favorites, customFoods }) {
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState("search"); // search | favorites | recent | barcode
  const [selected, setSelected] = useState(null);
  const [amount, setAmount] = useState("");
  const [fakeScanning, setFakeScanning] = useState(false);

  const allFoods = [...FOOD_DB, ...customFoods];
  const results = query.length > 1
    ? allFoods.filter(f => f.name.toLowerCase().includes(query.toLowerCase()) || (f.brand && f.brand.toLowerCase().includes(query.toLowerCase())))
    : [];
  const favFoods = allFoods.filter(f => favorites.includes(f.id));

  function handleSelect(food) {
    setSelected(food);
    setAmount(String(food.serving));
  }

  function handleAdd() {
    if (!selected || !amount) return;
    const num = parseFloat(amount);
    if (isNaN(num) || num <= 0) return;
    const n = calcNutrients(selected.id, num);
    onAdd({ id: uid(), foodId: selected.id, name: selected.name, amount: num, ...n });
    onClose();
  }

  function handleBarcodeScan() {
    setFakeScanning(true);
    setTimeout(() => {
      setFakeScanning(false);
      handleSelect(FOOD_DB[0]);
    }, 2000);
  }

  const mealColors = { breakfast: COLORS.breakfast, lunch: COLORS.lunch, dinner: COLORS.dinner, snack: COLORS.snack };
  const mealLabels = { breakfast: "Frühstück", lunch: "Mittagessen", dinner: "Abendessen", snack: "Snack" };

  return (
    <Modal title={`Zu ${mealLabels[mealType]} hinzufügen`} onClose={onClose}>
      {selected ? (
        <div>
          <div style={{ background: COLORS.surface, borderRadius: 12, padding: 16, marginBottom: 16 }}>
            <div style={{ fontWeight: 700, fontSize: 16, color: COLORS.text }}>{selected.name}</div>
            {selected.brand && <div style={{ fontSize: 13, color: COLORS.textLight }}>{selected.brand}</div>}
            <div style={{ display: "flex", gap: 12, marginTop: 12 }}>
              {["kcal","protein","carbs","fat"].map(k => {
                const val = k === "kcal" ? Math.round(selected.kcal * (parseFloat(amount)||0) / selected.serving)
                  : +((selected[k === "carbs" ? "carbs" : k]) * (parseFloat(amount)||0) / selected.serving).toFixed(1);
                return (
                  <div key={k} style={{ textAlign: "center", flex: 1 }}>
                    <div style={{ fontSize: 16, fontWeight: 700, color: k === "kcal" ? COLORS.primary : COLORS[k] || COLORS.text }}>{val}</div>
                    <div style={{ fontSize: 10, color: COLORS.textLight }}>{k === "kcal" ? "kcal" : k+"g"}</div>
                  </div>
                );
              })}
            </div>
          </div>
          <div style={{ marginBottom: 16 }}>
            <label style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid, display: "block", marginBottom: 6 }}>Menge ({selected.servingUnit})</label>
            <input
              type="number" value={amount} onChange={e => setAmount(e.target.value)}
              style={{ width: "100%", padding: "12px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 18, fontWeight: 600, color: COLORS.text, outline: "none", background: COLORS.bg, boxSizing: "border-box" }}
            />
            <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
              {[25,50,100,150,200].map(v => (
                <Pill key={v} active={parseFloat(amount)===v} onClick={() => setAmount(String(v))}>{v}</Pill>
              ))}
            </div>
          </div>
          <div style={{ display: "flex", gap: 10 }}>
            <button onClick={() => setSelected(null)} style={{ flex: 1, padding: "13px", border: `1.5px solid ${COLORS.border}`, borderRadius: 12, background: COLORS.card, color: COLORS.textMid, fontWeight: 600, cursor: "pointer", fontSize: 15 }}>← Zurück</button>
            <button onClick={handleAdd} style={{ flex: 2, padding: "13px", border: "none", borderRadius: 12, background: mealColors[mealType], color: "#fff", fontWeight: 700, cursor: "pointer", fontSize: 15 }}>Hinzufügen</button>
          </div>
        </div>
      ) : (
        <div>
          <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
            {[["search","🔍"], ["favorites","❤️"], ["barcode","📷"]].map(([t, icon]) => (
              <Pill key={t} active={tab===t} onClick={() => setTab(t)}>{icon} {t === "search" ? "Suche" : t === "favorites" ? "Favoriten" : "Barcode"}</Pill>
            ))}
          </div>

          {tab === "search" && (
            <div>
              <input
                autoFocus value={query} onChange={e => setQuery(e.target.value)}
                placeholder="Lebensmittel suchen..."
                style={{ width: "100%", padding: "12px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 15, color: COLORS.text, outline: "none", background: COLORS.bg, boxSizing: "border-box", marginBottom: 12 }}
              />
              {results.length > 0 ? results.map(f => (
                <FoodRow key={f.id} food={f} onClick={() => handleSelect(f)} />
              )) : query.length > 1 ? (
                <div style={{ textAlign: "center", color: COLORS.textLight, padding: "32px 0" }}>
                  <div style={{ fontSize: 32, marginBottom: 8 }}>🔍</div>
                  <div>Kein Treffer für "{query}"</div>
                </div>
              ) : (
                <div style={{ color: COLORS.textLight, fontSize: 14, textAlign: "center", padding: "24px 0" }}>Mindestens 2 Zeichen eingeben…</div>
              )}
            </div>
          )}

          {tab === "favorites" && (
            <div>
              {favFoods.length === 0 ? (
                <div style={{ textAlign: "center", color: COLORS.textLight, padding: "32px 0" }}>
                  <div style={{ fontSize: 32, marginBottom: 8 }}>❤️</div>
                  <div>Noch keine Favoriten</div>
                </div>
              ) : favFoods.map(f => <FoodRow key={f.id} food={f} onClick={() => handleSelect(f)} />)}
            </div>
          )}

          {tab === "barcode" && (
            <div style={{ textAlign: "center", padding: "24px 0" }}>
              <div style={{ width: 220, height: 160, background: COLORS.surface, borderRadius: 16, display: "flex", alignItems: "center", justifyContent: "center", margin: "0 auto 20px", border: `2px dashed ${COLORS.border}`, position: "relative", overflow: "hidden" }}>
                {fakeScanning ? (
                  <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10 }}>
                    <div style={{ width: 40, height: 40, borderRadius: "50%", border: `3px solid ${COLORS.primary}`, borderTopColor: "transparent", animation: "spin 0.8s linear infinite" }} />
                    <div style={{ fontSize: 13, color: COLORS.textMid }}>Scanne Barcode…</div>
                  </div>
                ) : (
                  <div style={{ fontSize: 48 }}>📷</div>
                )}
              </div>
              <button onClick={handleBarcodeScan} disabled={fakeScanning} style={{ padding: "13px 32px", background: COLORS.primary, color: "#fff", border: "none", borderRadius: 12, fontWeight: 700, fontSize: 15, cursor: "pointer" }}>
                {fakeScanning ? "Scannen…" : "Barcode scannen"}
              </button>
              <div style={{ fontSize: 12, color: COLORS.textLight, marginTop: 12 }}>oder Barcode-Nummer manuell eingeben</div>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

function FoodRow({ food, onClick }) {
  return (
    <div onClick={onClick} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "12px 0", borderBottom: `1px solid ${COLORS.border}`, cursor: "pointer" }}>
      <div>
        <div style={{ fontWeight: 600, color: COLORS.text, fontSize: 14 }}>{food.name}</div>
        <div style={{ fontSize: 12, color: COLORS.textLight }}>{food.brand || "Generisch"} • {food.serving}{food.servingUnit}</div>
      </div>
      <div style={{ textAlign: "right" }}>
        <div style={{ fontWeight: 700, color: COLORS.primary, fontSize: 14 }}>{food.kcal} kcal</div>
        <div style={{ fontSize: 11, color: COLORS.textLight }}>P:{food.protein}g K:{food.carbs}g F:{food.fat}g</div>
      </div>
    </div>
  );
}

// ─── DIARY SCREEN ─────────────────────────────────────────────────────────────
function DiaryScreen({ state, dispatch }) {
  const [date, setDate] = useState(today);
  const [addModal, setAddModal] = useState(null); // null | mealType
  const [expandedMeal, setExpandedMeal] = useState(null);

  const diary = state.diary[date] || { breakfast: [], lunch: [], dinner: [], snack: [] };
  const totals = sumDay(diary);
  const goals = state.goals;

  const meals = [
    { key: "breakfast", label: "Frühstück", icon: "☀️", color: COLORS.breakfast },
    { key: "lunch", label: "Mittagessen", icon: "🌤️", color: COLORS.lunch },
    { key: "dinner", label: "Abendessen", icon: "🌙", color: COLORS.dinner },
    { key: "snack", label: "Snacks", icon: "🍎", color: COLORS.snack },
  ];

  // Date navigation
  const prevDay = () => { const d = new Date(date); d.setDate(d.getDate()-1); setDate(d.toISOString().split("T")[0]); };
  const nextDay = () => { const d = new Date(date); d.setDate(d.getDate()+1); const n = d.toISOString().split("T")[0]; if (n <= today) setDate(n); };

  const dateLabel = date === today ? "Heute" : new Date(date).toLocaleDateString("de-DE", { weekday: "short", day: "numeric", month: "short" });

  function removeEntry(mealKey, entryId) {
    dispatch({ type: "REMOVE_ENTRY", date, mealKey, entryId });
  }

  return (
    <div style={{ padding: "0 0 80px" }}>
      {/* Date navigator */}
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 16, padding: "16px 20px", background: COLORS.card, borderBottom: `1px solid ${COLORS.border}` }}>
        <button onClick={prevDay} style={{ background: COLORS.surface, border: "none", borderRadius: 8, width: 32, height: 32, cursor: "pointer", fontSize: 16, color: COLORS.textMid }}>‹</button>
        <span style={{ fontWeight: 700, color: COLORS.text, fontSize: 15, minWidth: 120, textAlign: "center" }}>{dateLabel}</span>
        <button onClick={nextDay} disabled={date >= today} style={{ background: COLORS.surface, border: "none", borderRadius: 8, width: 32, height: 32, cursor: "pointer", fontSize: 16, color: date < today ? COLORS.textMid : COLORS.border }}>›</button>
      </div>

      {/* Calorie summary card */}
      <div style={{ margin: "16px", background: COLORS.card, borderRadius: 16, padding: "20px", boxShadow: "0 2px 12px rgba(0,0,0,0.06)" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 16 }}>
          <MacroRing eaten={totals.kcal} goal={goals.kcal} />
          <div style={{ flex: 1, paddingLeft: 20 }}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, color: COLORS.textMid, marginBottom: 4 }}>
                <span>Gegessen</span><span style={{ fontWeight: 700, color: COLORS.text }}>{totals.kcal} kcal</span>
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, color: COLORS.textMid }}>
                <span>Ziel</span><span>{goals.kcal} kcal</span>
              </div>
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              <MacroBar label="Protein" value={totals.protein} goal={goals.protein} color={COLORS.protein} />
              <MacroBar label="Kohlenhydrate" value={totals.carbs} goal={goals.carbs} color={COLORS.carb} />
              <MacroBar label="Fett" value={totals.fat} goal={goals.fat} color={COLORS.fat} />
            </div>
          </div>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {[
            { l: "Protein", v: totals.protein, g: goals.protein, c: COLORS.protein },
            { l: "Kohlenhydr.", v: totals.carbs, g: goals.carbs, c: COLORS.carb },
            { l: "Fett", v: totals.fat, g: goals.fat, c: COLORS.fat },
            { l: "Ballaststoffe", v: totals.fiber, g: goals.fiber, c: COLORS.fiber },
          ].map(({ l, v, g, c }) => (
            <div key={l} style={{ flex: 1, textAlign: "center", background: COLORS.surface, borderRadius: 10, padding: "8px 4px" }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: c }}>{v}g</div>
              <div style={{ fontSize: 10, color: COLORS.textLight }}>{l}</div>
              <div style={{ fontSize: 10, color: COLORS.border }}>/{g}g</div>
            </div>
          ))}
        </div>
      </div>

      {/* Meal cards */}
      {meals.map(({ key, label, icon, color }) => {
        const entries = diary[key] || [];
        const mealTotals = sumMeal(entries);
        const isOpen = expandedMeal === key;
        return (
          <div key={key} style={{ margin: "0 16px 12px", background: COLORS.card, borderRadius: 16, overflow: "hidden", boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
            <div onClick={() => setExpandedMeal(isOpen ? null : key)} style={{ display: "flex", alignItems: "center", padding: "14px 16px", cursor: "pointer" }}>
              <div style={{ width: 36, height: 36, borderRadius: 10, background: color + "22", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 18, marginRight: 12 }}>{icon}</div>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 700, color: COLORS.text, fontSize: 14 }}>{label}</div>
                <div style={{ fontSize: 12, color: COLORS.textLight }}>
                  {entries.length > 0 ? `${mealTotals.kcal} kcal • ${entries.length} Einträge` : "Noch nichts eingetragen"}
                </div>
              </div>
              <button onClick={(e) => { e.stopPropagation(); setAddModal(key); }} style={{ background: color, border: "none", borderRadius: 20, width: 32, height: 32, color: "#fff", fontSize: 18, cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center" }}>+</button>
              <span style={{ color: COLORS.textLight, marginLeft: 8, fontSize: 12 }}>{isOpen ? "▲" : "▼"}</span>
            </div>
            {isOpen && entries.length > 0 && (
              <div style={{ borderTop: `1px solid ${COLORS.border}` }}>
                {entries.map(e => (
                  <div key={e.id} style={{ display: "flex", alignItems: "center", padding: "10px 16px", borderBottom: `1px solid ${COLORS.border}` }}>
                    <div style={{ flex: 1 }}>
                      <div style={{ fontSize: 14, fontWeight: 600, color: COLORS.text }}>{e.name}</div>
                      <div style={{ fontSize: 12, color: COLORS.textLight }}>{e.amount}g • P:{e.protein}g K:{e.carbs}g F:{e.fat}g</div>
                    </div>
                    <div style={{ fontWeight: 700, color: COLORS.primary, marginRight: 12 }}>{e.kcal}</div>
                    <button onClick={() => removeEntry(key, e.id)} style={{ background: COLORS.dangerLight, border: "none", borderRadius: 8, width: 28, height: 28, color: COLORS.danger, cursor: "pointer", fontSize: 14 }}>✕</button>
                  </div>
                ))}
                <div style={{ display: "flex", justifyContent: "space-between", padding: "10px 16px", background: COLORS.surface }}>
                  <span style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid }}>Gesamt</span>
                  <span style={{ fontSize: 13, fontWeight: 700, color: COLORS.primary }}>{mealTotals.kcal} kcal</span>
                </div>
              </div>
            )}
          </div>
        );
      })}

      {addModal && (
        <AddFoodModal
          mealType={addModal}
          favorites={state.favorites}
          customFoods={state.customFoods}
          onAdd={(entry) => dispatch({ type: "ADD_ENTRY", date, mealKey: addModal, entry })}
          onClose={() => setAddModal(null)}
        />
      )}
    </div>
  );
}

// ─── NUTRITION DETAIL SCREEN ──────────────────────────────────────────────────
function NutritionDetail({ totals, goals }) {
  const nutrients = [
    { label: "Kalorien", value: totals.kcal, goal: goals.kcal, unit: "kcal", color: COLORS.primary },
    { label: "Protein", value: totals.protein, goal: goals.protein, unit: "g", color: COLORS.protein },
    { label: "Kohlenhydrate", value: totals.carbs, goal: goals.carbs, unit: "g", color: COLORS.carb },
    { label: "Fett", value: totals.fat, goal: goals.fat, unit: "g", color: COLORS.fat },
    { label: "Ballaststoffe", value: totals.fiber, goal: goals.fiber, unit: "g", color: COLORS.fiber },
  ];
  return (
    <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, margin: "0 16px 12px", boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
      <h3 style={{ margin: "0 0 14px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Nährwerte heute</h3>
      {nutrients.map(n => (
        <div key={n.label} style={{ marginBottom: 12 }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
            <span style={{ fontSize: 13, color: COLORS.textMid }}>{n.label}</span>
            <span style={{ fontSize: 13, fontWeight: 700, color: n.color }}>{n.value} / {n.goal} {n.unit}</span>
          </div>
          <div style={{ background: COLORS.border, borderRadius: 6, height: 8 }}>
            <div style={{ width: `${Math.min((n.value/n.goal)*100,100)}%`, height: "100%", background: n.color, borderRadius: 6, transition: "width 0.4s" }} />
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── ANALYSIS SCREEN ──────────────────────────────────────────────────────────
function AnalysisScreen({ state }) {
  const [period, setPeriod] = useState("week");
  const days7 = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i);
    const key = d.toISOString().split("T")[0];
    const totals = sumDay(state.diary[key]);
    days7.push({ label: d.toLocaleDateString("de-DE", { weekday: "short" }), ...totals, date: key });
  }
  const maxKcal = Math.max(...days7.map(d => d.kcal), state.goals.kcal);

  const avgKcal = Math.round(days7.reduce((s, d) => s + d.kcal, 0) / 7);
  const avgProtein = +(days7.reduce((s, d) => s + d.protein, 0) / 7).toFixed(1);

  return (
    <div style={{ padding: "16px 16px 80px" }}>
      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        {["week","month"].map(p => <Pill key={p} active={period===p} onClick={() => setPeriod(p)}>{p==="week"?"7 Tage":"30 Tage"}</Pill>)}
      </div>

      {/* Calorie chart */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <h3 style={{ margin: "0 0 16px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Kalorienübersicht</h3>
        <div style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 120 }}>
          {days7.map((d, i) => (
            <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
              <div style={{ fontSize: 9, color: COLORS.textLight, fontWeight: 600 }}>{d.kcal > 0 ? d.kcal : ""}</div>
              <div style={{ width: "100%", position: "relative" }}>
                {/* Goal line marker */}
                <div style={{ height: Math.round((d.kcal / maxKcal) * 90), background: d.date === today ? COLORS.primary : COLORS.primaryLight, borderRadius: "4px 4px 0 0", minHeight: d.kcal > 0 ? 4 : 0, transition: "height 0.5s ease" }} />
              </div>
              <div style={{ fontSize: 10, color: COLORS.textMid }}>{d.label}</div>
            </div>
          ))}
        </div>
        <div style={{ borderTop: `1px solid ${COLORS.border}`, marginTop: 12, paddingTop: 12, display: "flex", gap: 12 }}>
          <div style={{ flex: 1, textAlign: "center" }}>
            <div style={{ fontSize: 18, fontWeight: 700, color: COLORS.primary }}>{avgKcal}</div>
            <div style={{ fontSize: 11, color: COLORS.textLight }}>Ø kcal/Tag</div>
          </div>
          <div style={{ flex: 1, textAlign: "center" }}>
            <div style={{ fontSize: 18, fontWeight: 700, color: COLORS.protein }}>{avgProtein}g</div>
            <div style={{ fontSize: 11, color: COLORS.textLight }}>Ø Protein/Tag</div>
          </div>
          <div style={{ flex: 1, textAlign: "center" }}>
            <div style={{ fontSize: 18, fontWeight: 700, color: COLORS.accent }}>{state.streak}</div>
            <div style={{ fontSize: 11, color: COLORS.textLight }}>Tage Streak 🔥</div>
          </div>
        </div>
      </div>

      {/* Weight chart */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: COLORS.text }}>Gewicht</h3>
          <span style={{ fontSize: 13, color: COLORS.textLight }}>7 Tage</span>
        </div>
        <WeightChart data={state.weight} />
      </div>

      {/* Macro distribution */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <h3 style={{ margin: "0 0 14px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Makro-Verteilung (Woche)</h3>
        {[
          { l: "Protein", v: avgProtein, g: state.goals.protein, c: COLORS.protein },
          { l: "Kohlenhydrate", v: +(days7.reduce((s,d)=>s+d.carbs,0)/7).toFixed(1), g: state.goals.carbs, c: COLORS.carb },
          { l: "Fett", v: +(days7.reduce((s,d)=>s+d.fat,0)/7).toFixed(1), g: state.goals.fat, c: COLORS.fat },
        ].map(({ l, v, g, c }) => (
          <div key={l} style={{ marginBottom: 12 }}>
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
              <span style={{ fontSize: 13, color: COLORS.textMid }}>{l}</span>
              <span style={{ fontSize: 13, fontWeight: 700, color: c }}>{v}g / {g}g</span>
            </div>
            <div style={{ background: COLORS.border, borderRadius: 6, height: 8 }}>
              <div style={{ width: `${Math.min((v/g)*100,100)}%`, height: "100%", background: c, borderRadius: 6 }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function WeightChart({ data }) {
  const vals = data.map(d => d.value);
  const min = Math.min(...vals) - 0.5;
  const max = Math.max(...vals) + 0.5;
  const w = 280; const h = 80;
  const points = data.map((d, i) => {
    const x = (i / (data.length - 1)) * (w - 20) + 10;
    const y = h - ((d.value - min) / (max - min)) * (h - 16) - 8;
    return `${x},${y}`;
  }).join(" ");

  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${w} ${h}`} style={{ overflow: "visible" }}>
        <polyline points={points} fill="none" stroke={COLORS.primary} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" />
        {data.map((d, i) => {
          const x = (i / (data.length - 1)) * (w - 20) + 10;
          const y = h - ((d.value - min) / (max - min)) * (h - 16) - 8;
          return <circle key={i} cx={x} cy={y} r={i === data.length-1 ? 5 : 3} fill={i === data.length-1 ? COLORS.primary : COLORS.card} stroke={COLORS.primary} strokeWidth={2} />;
        })}
      </svg>
      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
        <span style={{ fontSize: 12, color: COLORS.textLight }}>{data[0].date.slice(5)}</span>
        <span style={{ fontSize: 14, fontWeight: 700, color: COLORS.primary }}>{data[data.length-1].value} kg</span>
        <span style={{ fontSize: 12, color: COLORS.textLight }}>{data[data.length-1].date === "today" ? "heute" : data[data.length-1].date.slice(5)}</span>
      </div>
    </div>
  );
}

// ─── RECIPES SCREEN ───────────────────────────────────────────────────────────
function RecipesScreen({ state, dispatch }) {
  const [tab, setTab] = useState("discover"); // discover | my | favorites
  const [selected, setSelected] = useState(null);
  const [showCreate, setShowCreate] = useState(false);
  const [search, setSearch] = useState("");
  const [activeTag, setActiveTag] = useState(null);

  const allTags = [...new Set(state.recipes.flatMap(r => r.tags))];
  const filtered = state.recipes.filter(r =>
    (search.length < 2 || r.name.toLowerCase().includes(search.toLowerCase())) &&
    (!activeTag || r.tags.includes(activeTag))
  );

  if (selected) return <RecipeDetail recipe={selected} onBack={() => setSelected(null)} onAddToDiary={(meal) => {
    dispatch({ type: "ADD_ENTRY", date: today, mealKey: meal, entry: {
      id: uid(), foodId: selected.id, name: selected.name, amount: 1,
      kcal: selected.kcal, protein: selected.protein, carbs: selected.carbs, fat: selected.fat, fiber: 0
    }});
    setSelected(null);
  }} />;

  if (showCreate) return <CreateRecipeScreen onBack={() => setShowCreate(false)} onSave={(r) => { dispatch({ type: "ADD_RECIPE", recipe: r }); setShowCreate(false); }} />;

  return (
    <div style={{ padding: "16px 16px 80px" }}>
      <div style={{ display: "flex", gap: 10, marginBottom: 14 }}>
        <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Rezept suchen…"
          style={{ flex: 1, padding: "10px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 14, color: COLORS.text, outline: "none", background: COLORS.bg }} />
        <button onClick={() => setShowCreate(true)} style={{ background: COLORS.primary, border: "none", borderRadius: 10, color: "#fff", padding: "10px 14px", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" }}>+ Neu</button>
      </div>

      <div style={{ display: "flex", gap: 8, marginBottom: 14, flexWrap: "wrap" }}>
        <Pill active={!activeTag} onClick={() => setActiveTag(null)}>Alle</Pill>
        {allTags.map(t => <Pill key={t} active={activeTag===t} onClick={() => setActiveTag(t === activeTag ? null : t)}>#{t}</Pill>)}
      </div>

      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        {[["discover","🌍 Entdecken"],["my","📋 Meine"]].map(([t,l]) => (
          <Pill key={t} active={tab===t} onClick={() => setTab(t)}>{l}</Pill>
        ))}
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {filtered.map(r => (
          <RecipeCard key={r.id} recipe={r} onClick={() => setSelected(r)} />
        ))}
        {filtered.length === 0 && (
          <div style={{ textAlign: "center", padding: "40px 0", color: COLORS.textLight }}>
            <div style={{ fontSize: 40, marginBottom: 12 }}>🍽️</div>
            <div>Keine Rezepte gefunden</div>
            <button onClick={() => setShowCreate(true)} style={{ marginTop: 16, padding: "10px 24px", background: COLORS.primary, color: "#fff", border: "none", borderRadius: 10, fontWeight: 700, cursor: "pointer" }}>Erstes Rezept erstellen</button>
          </div>
        )}
      </div>
    </div>
  );
}

function RecipeCard({ recipe, onClick }) {
  const catColors = { Breakfast: COLORS.breakfast, Lunch: COLORS.lunch, Dinner: COLORS.dinner, Snack: COLORS.snack };
  return (
    <div onClick={onClick} style={{ background: COLORS.card, borderRadius: 16, padding: 16, boxShadow: "0 1px 6px rgba(0,0,0,0.05)", cursor: "pointer", display: "flex", gap: 14, alignItems: "center" }}>
      <div style={{ width: 60, height: 60, borderRadius: 12, background: (catColors[recipe.category] || COLORS.primary) + "22", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 30, flexShrink: 0 }}>
        {recipe.image}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 700, color: COLORS.text, fontSize: 15, marginBottom: 4 }}>{recipe.name}</div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 6 }}>
          {recipe.tags.slice(0,2).map(t => (
            <span key={t} style={{ fontSize: 11, background: COLORS.surface, color: COLORS.textMid, borderRadius: 6, padding: "2px 7px", fontWeight: 600 }}>#{t}</span>
          ))}
        </div>
        <div style={{ fontSize: 12, color: COLORS.textLight }}>⏱ {recipe.totalTime} Min • {recipe.servings} Port. • {recipe.kcal} kcal</div>
      </div>
    </div>
  );
}

function RecipeDetail({ recipe, onBack, onAddToDiary }) {
  const [showAddMeal, setShowAddMeal] = useState(false);
  const meals = ["breakfast","lunch","dinner","snack"];
  const mealLabels = { breakfast: "Frühstück", lunch: "Mittagessen", dinner: "Abendessen", snack: "Snack" };

  return (
    <div style={{ padding: "0 0 80px" }}>
      <div style={{ background: COLORS.card, padding: "16px 20px", display: "flex", alignItems: "center", gap: 12, borderBottom: `1px solid ${COLORS.border}` }}>
        <button onClick={onBack} style={{ background: COLORS.surface, border: "none", borderRadius: 8, width: 32, height: 32, cursor: "pointer", fontSize: 16, color: COLORS.textMid }}>←</button>
        <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: COLORS.text }}>{recipe.name}</h2>
      </div>

      <div style={{ padding: 16 }}>
        <div style={{ background: COLORS.card, borderRadius: 16, padding: 20, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
          <div style={{ fontSize: 60, textAlign: "center", marginBottom: 12 }}>{recipe.image}</div>
          <div style={{ display: "flex", gap: 8, justifyContent: "center", marginBottom: 12, flexWrap: "wrap" }}>
            {recipe.tags.map(t => (
              <span key={t} style={{ fontSize: 12, background: COLORS.surface, color: COLORS.primary, borderRadius: 8, padding: "3px 10px", fontWeight: 600 }}>#{t}</span>
            ))}
          </div>
          <div style={{ display: "flex", gap: 10, justifyContent: "center" }}>
            {[["⏱",`${recipe.totalTime} Min`], ["👤",`${recipe.servings} Port.`], ["🔥",`${recipe.kcal} kcal`]].map(([i,l]) => (
              <div key={l} style={{ textAlign: "center", flex: 1, background: COLORS.surface, borderRadius: 10, padding: 10 }}>
                <div style={{ fontSize: 18 }}>{i}</div>
                <div style={{ fontSize: 12, fontWeight: 600, color: COLORS.textMid }}>{l}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Macros */}
        <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
          <h3 style={{ margin: "0 0 12px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Nährwerte (pro Portion)</h3>
          <div style={{ display: "flex", gap: 8 }}>
            {[["Kcal", recipe.kcal, COLORS.primary], ["Protein", recipe.protein+"g", COLORS.protein], ["Kohlenhydr.", recipe.carbs+"g", COLORS.carb], ["Fett", recipe.fat+"g", COLORS.fat]].map(([l,v,c]) => (
              <div key={l} style={{ flex: 1, textAlign: "center", background: COLORS.surface, borderRadius: 10, padding: 10 }}>
                <div style={{ fontSize: 16, fontWeight: 700, color: c }}>{v}</div>
                <div style={{ fontSize: 10, color: COLORS.textLight }}>{l}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Ingredients */}
        <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
          <h3 style={{ margin: "0 0 12px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Zutaten ({recipe.servings} Portion)</h3>
          {recipe.ingredients.map((ing, i) => (
            <div key={i} style={{ display: "flex", justifyContent: "space-between", padding: "8px 0", borderBottom: i < recipe.ingredients.length-1 ? `1px solid ${COLORS.border}` : "none" }}>
              <span style={{ fontSize: 14, color: COLORS.text }}>{ing.name}</span>
              <span style={{ fontSize: 14, fontWeight: 600, color: COLORS.textMid }}>{ing.amount} {ing.unit}</span>
            </div>
          ))}
        </div>

        {/* Instructions */}
        <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 16, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
          <h3 style={{ margin: "0 0 12px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>Zubereitung</h3>
          {recipe.instructions.map((step, i) => (
            <div key={i} style={{ display: "flex", gap: 12, marginBottom: 12 }}>
              <div style={{ width: 24, height: 24, borderRadius: "50%", background: COLORS.primary, color: "#fff", fontSize: 12, fontWeight: 700, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0, marginTop: 2 }}>{i+1}</div>
              <span style={{ fontSize: 14, color: COLORS.text, lineHeight: 1.5 }}>{step}</span>
            </div>
          ))}
        </div>

        <button onClick={() => setShowAddMeal(!showAddMeal)} style={{ width: "100%", padding: 14, background: COLORS.primary, color: "#fff", border: "none", borderRadius: 14, fontWeight: 700, fontSize: 16, cursor: "pointer" }}>
          + Zum Tagebuch hinzufügen
        </button>
        {showAddMeal && (
          <div style={{ background: COLORS.card, borderRadius: 14, padding: 12, marginTop: 10, boxShadow: "0 2px 12px rgba(0,0,0,0.1)" }}>
            {meals.map(m => (
              <button key={m} onClick={() => { onAddToDiary(m); setShowAddMeal(false); }} style={{ display: "block", width: "100%", padding: "11px 14px", background: COLORS.surface, border: "none", borderRadius: 10, color: COLORS.text, fontWeight: 600, cursor: "pointer", marginBottom: 6, textAlign: "left", fontSize: 14 }}>
                {mealLabels[m]}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function CreateRecipeScreen({ onBack, onSave }) {
  const [name, setName] = useState("");
  const [category, setCategory] = useState("Lunch");
  const [time, setTime] = useState("20");
  const [servings, setServings] = useState("2");
  const [tags, setTags] = useState("");
  const [ingredients, setIngredients] = useState([{ name: "", amount: "", unit: "g" }]);
  const [instructions, setInstructions] = useState([""]);

  function addIngredient() { setIngredients([...ingredients, { name: "", amount: "", unit: "g" }]); }
  function addStep() { setInstructions([...instructions, ""]); }

  function handleSave() {
    if (!name.trim()) return;
    onSave({
      id: "r" + uid(), name, category, totalTime: parseInt(time)||20, servings: parseInt(servings)||1,
      tags: tags.split(",").map(t=>t.trim()).filter(Boolean),
      image: "🍽️",
      ingredients: ingredients.filter(i => i.name),
      instructions: instructions.filter(i => i.trim()),
      kcal: 400, protein: 25, carbs: 40, fat: 12,
    });
  }

  return (
    <div style={{ padding: "0 0 80px" }}>
      <div style={{ background: COLORS.card, padding: "16px 20px", display: "flex", alignItems: "center", gap: 12, borderBottom: `1px solid ${COLORS.border}` }}>
        <button onClick={onBack} style={{ background: COLORS.surface, border: "none", borderRadius: 8, width: 32, height: 32, cursor: "pointer", fontSize: 16 }}>←</button>
        <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: COLORS.text }}>Neues Rezept</h2>
        <button onClick={handleSave} style={{ marginLeft: "auto", background: COLORS.primary, border: "none", borderRadius: 8, color: "#fff", padding: "7px 16px", fontWeight: 700, cursor: "pointer", fontSize: 13 }}>Speichern</button>
      </div>

      <div style={{ padding: 16 }}>
        <InputField label="Rezeptname" value={name} onChange={setName} placeholder="z.B. Chicken Bowl" />
        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid, display: "block", marginBottom: 6 }}>Kategorie</label>
          <div style={{ display: "flex", gap: 8 }}>
            {["Breakfast","Lunch","Dinner","Snack"].map(c => <Pill key={c} active={category===c} onClick={() => setCategory(c)}>{c}</Pill>)}
          </div>
        </div>
        <div style={{ display: "flex", gap: 10, marginBottom: 14 }}>
          <div style={{ flex: 1 }}><InputField label="Zeit (Min)" value={time} onChange={setTime} type="number" /></div>
          <div style={{ flex: 1 }}><InputField label="Portionen" value={servings} onChange={setServings} type="number" /></div>
        </div>
        <InputField label="Tags (kommagetrennt)" value={tags} onChange={setTags} placeholder="vegan, high-protein" />

        <div style={{ marginBottom: 14 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
            <label style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid }}>Zutaten</label>
            <button onClick={addIngredient} style={{ background: COLORS.primary, color: "#fff", border: "none", borderRadius: 6, padding: "4px 10px", cursor: "pointer", fontSize: 13, fontWeight: 700 }}>+ Zutat</button>
          </div>
          {ingredients.map((ing, i) => (
            <div key={i} style={{ display: "flex", gap: 8, marginBottom: 8 }}>
              <input value={ing.name} onChange={e => { const a=[...ingredients]; a[i].name=e.target.value; setIngredients(a); }} placeholder="Zutat"
                style={{ flex: 2, padding: "9px 12px", border: `1.5px solid ${COLORS.border}`, borderRadius: 8, fontSize: 13, color: COLORS.text, outline: "none" }} />
              <input value={ing.amount} onChange={e => { const a=[...ingredients]; a[i].amount=e.target.value; setIngredients(a); }} placeholder="100"
                style={{ flex: 1, padding: "9px 12px", border: `1.5px solid ${COLORS.border}`, borderRadius: 8, fontSize: 13, color: COLORS.text, outline: "none" }} />
              <select value={ing.unit} onChange={e => { const a=[...ingredients]; a[i].unit=e.target.value; setIngredients(a); }}
                style={{ flex: 1, padding: "9px 8px", border: `1.5px solid ${COLORS.border}`, borderRadius: 8, fontSize: 13, color: COLORS.text, background: COLORS.bg }}>
                {["g","ml","TL","EL","Stk"].map(u => <option key={u}>{u}</option>)}
              </select>
            </div>
          ))}
        </div>

        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
            <label style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid }}>Zubereitung</label>
            <button onClick={addStep} style={{ background: COLORS.primary, color: "#fff", border: "none", borderRadius: 6, padding: "4px 10px", cursor: "pointer", fontSize: 13, fontWeight: 700 }}>+ Schritt</button>
          </div>
          {instructions.map((step, i) => (
            <div key={i} style={{ display: "flex", gap: 8, marginBottom: 8, alignItems: "flex-start" }}>
              <div style={{ width: 24, height: 24, borderRadius: "50%", background: COLORS.primary, color: "#fff", fontSize: 11, fontWeight: 700, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0, marginTop: 8 }}>{i+1}</div>
              <textarea value={step} onChange={e => { const a=[...instructions]; a[i]=e.target.value; setInstructions(a); }} placeholder={`Schritt ${i+1}…`} rows={2}
                style={{ flex: 1, padding: "9px 12px", border: `1.5px solid ${COLORS.border}`, borderRadius: 8, fontSize: 13, color: COLORS.text, outline: "none", resize: "vertical", fontFamily: "inherit" }} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function InputField({ label, value, onChange, placeholder, type="text" }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <label style={{ fontSize: 13, fontWeight: 600, color: COLORS.textMid, display: "block", marginBottom: 6 }}>{label}</label>
      <input type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        style={{ width: "100%", padding: "10px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 14, color: COLORS.text, outline: "none", background: COLORS.bg, boxSizing: "border-box" }} />
    </div>
  );
}

// ─── SEARCH SCREEN ────────────────────────────────────────────────────────────
function SearchScreen({ state, dispatch }) {
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState("all"); // all | favorites | custom | barcode
  const [addTarget, setAddTarget] = useState(null);
  const [showFoodDetail, setShowFoodDetail] = useState(null);
  const [scanning, setScanning] = useState(false);

  const allFoods = [...FOOD_DB, ...state.customFoods];
  const results = query.length > 1
    ? allFoods.filter(f => f.name.toLowerCase().includes(query.toLowerCase()) || (f.brand && f.brand.toLowerCase().includes(query.toLowerCase())))
    : allFoods.slice(0, 8);

  function doScan() {
    setScanning(true);
    setTimeout(() => { setScanning(false); setShowFoodDetail(FOOD_DB[0]); }, 2000);
  }

  return (
    <div style={{ padding: "16px 16px 80px" }}>
      <div style={{ position: "relative", marginBottom: 14 }}>
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Lebensmittel suchen…"
          style={{ width: "100%", padding: "12px 14px 12px 40px", border: `1.5px solid ${COLORS.border}`, borderRadius: 12, fontSize: 15, color: COLORS.text, outline: "none", background: COLORS.card, boxSizing: "border-box" }} />
        <span style={{ position: "absolute", left: 13, top: "50%", transform: "translateY(-50%)", fontSize: 17, color: COLORS.textLight }}>🔍</span>
      </div>

      <div style={{ display: "flex", gap: 8, marginBottom: 16, flexWrap: "wrap" }}>
        {[["all","Alle"],["favorites","❤️ Favoriten"],["custom","✏️ Eigene"],["barcode","📷 Scan"]].map(([t,l]) => (
          <Pill key={t} active={tab===t} onClick={() => setTab(t)}>{l}</Pill>
        ))}
      </div>

      {tab === "barcode" ? (
        <div style={{ textAlign: "center", padding: "32px 0" }}>
          <div style={{ width: 240, height: 180, background: COLORS.card, borderRadius: 16, display: "flex", alignItems: "center", justifyContent: "center", margin: "0 auto 24px", border: `2px dashed ${COLORS.border}` }}>
            {scanning ? (
              <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 12 }}>
                <div style={{ width: 44, height: 44, borderRadius: "50%", border: `3px solid ${COLORS.primary}`, borderTopColor: "transparent", animation: "spin 0.8s linear infinite" }} />
                <div style={{ fontSize: 14, color: COLORS.textMid }}>Scanne Barcode…</div>
              </div>
            ) : (
              <div style={{ fontSize: 60 }}>📷</div>
            )}
          </div>
          <button onClick={doScan} disabled={scanning} style={{ padding: "13px 36px", background: COLORS.primary, color: "#fff", border: "none", borderRadius: 14, fontWeight: 700, fontSize: 16, cursor: "pointer", marginBottom: 16 }}>
            Barcode scannen
          </button>
          <div style={{ fontSize: 13, color: COLORS.textLight }}>Scanne den Barcode eines Produkts um automatisch die Nährwerte zu laden</div>
        </div>
      ) : (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
            <span style={{ fontSize: 13, color: COLORS.textMid }}>
              {query.length > 1 ? `${results.length} Treffer` : "Beliebte Lebensmittel"}
            </span>
            <button onClick={() => dispatch({ type: "SHOW_CREATE_FOOD" })} style={{ background: "none", border: "none", color: COLORS.primary, fontWeight: 700, cursor: "pointer", fontSize: 13 }}>+ Eigenes erstellen</button>
          </div>
          {results.map(f => {
            const isFav = state.favorites.includes(f.id);
            return (
              <div key={f.id} onClick={() => setShowFoodDetail(f)} style={{ display: "flex", alignItems: "center", padding: "12px 0", borderBottom: `1px solid ${COLORS.border}`, cursor: "pointer" }}>
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, color: COLORS.text, fontSize: 14 }}>{f.name}</div>
                  <div style={{ fontSize: 12, color: COLORS.textLight }}>{f.brand || "Generisch"} • {f.serving}{f.servingUnit}</div>
                </div>
                <div style={{ textAlign: "right", marginRight: 12 }}>
                  <div style={{ fontWeight: 700, color: COLORS.primary, fontSize: 14 }}>{f.kcal} kcal</div>
                  <div style={{ fontSize: 11, color: COLORS.textLight }}>P:{f.protein}g K:{f.carbs}g F:{f.fat}g</div>
                </div>
                <button onClick={(e) => { e.stopPropagation(); dispatch({ type: "TOGGLE_FAVORITE", foodId: f.id }); }}
                  style={{ background: "none", border: "none", fontSize: 18, cursor: "pointer", color: isFav ? "#E05252" : COLORS.border }}>
                  {isFav ? "❤️" : "🤍"}
                </button>
              </div>
            );
          })}
        </div>
      )}

      {showFoodDetail && (
        <Modal title={showFoodDetail.name} onClose={() => setShowFoodDetail(null)}>
          <FoodDetailView food={showFoodDetail} />
        </Modal>
      )}
    </div>
  );
}

function FoodDetailView({ food }) {
  const nutrients = [
    { l: "Kalorien", v: food.kcal, u: "kcal" },
    { l: "Protein", v: food.protein, u: "g" },
    { l: "Kohlenhydrate", v: food.carbs, u: "g" },
    { l: "davon Zucker", v: food.sugar, u: "g" },
    { l: "Fett", v: food.fat, u: "g" },
    { l: "Ballaststoffe", v: food.fiber, u: "g" },
    { l: "Natrium", v: food.sodium, u: "g" },
  ];
  return (
    <div>
      <div style={{ background: COLORS.surface, borderRadius: 12, padding: 14, marginBottom: 14 }}>
        <div style={{ fontSize: 13, color: COLORS.textLight, marginBottom: 2 }}>{food.brand || "Generisch"}</div>
        <div style={{ fontSize: 13, color: COLORS.textMid }}>Portionsgröße: {food.serving}{food.servingUnit}</div>
      </div>
      {nutrients.map(({ l, v, u }) => (
        <div key={l} style={{ display: "flex", justifyContent: "space-between", padding: "9px 0", borderBottom: `1px solid ${COLORS.border}` }}>
          <span style={{ fontSize: 14, color: COLORS.textMid }}>{l}</span>
          <span style={{ fontSize: 14, fontWeight: 700, color: COLORS.text }}>{v} {u}</span>
        </div>
      ))}
    </div>
  );
}

// ─── PROFILE SCREEN ───────────────────────────────────────────────────────────
function ProfileScreen({ state, dispatch }) {
  const [editGoals, setEditGoals] = useState(false);
  const [goalDraft, setGoalDraft] = useState({ ...state.goals });
  const [weightInput, setWeightInput] = useState("");

  function saveGoals() { dispatch({ type: "SET_GOALS", goals: goalDraft }); setEditGoals(false); }

  function logWeight() {
    const v = parseFloat(weightInput);
    if (isNaN(v) || v <= 0) return;
    dispatch({ type: "LOG_WEIGHT", date: today, value: v });
    setWeightInput("");
  }

  const lastWeight = state.weight[state.weight.length - 1];

  return (
    <div style={{ padding: "16px 16px 80px" }}>
      {/* Profile header */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 20, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)", textAlign: "center" }}>
        <div style={{ width: 72, height: 72, borderRadius: "50%", background: COLORS.surface, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 36, margin: "0 auto 12px" }}>🧑</div>
        <div style={{ fontWeight: 700, fontSize: 18, color: COLORS.text, marginBottom: 4 }}>NutriSnap User</div>
        <StreakBadge streak={state.streak} />
        <div style={{ display: "flex", gap: 12, marginTop: 16 }}>
          {[["🎯",`${state.goals.kcal} kcal`,"Ziel"],["⚖️",`${lastWeight?.value || "—"} kg`,"Gewicht"],["🔥",state.streak,"Streak"]].map(([i,v,l]) => (
            <div key={l} style={{ flex: 1, background: COLORS.surface, borderRadius: 10, padding: 10, textAlign: "center" }}>
              <div style={{ fontSize: 18 }}>{i}</div>
              <div style={{ fontWeight: 700, color: COLORS.text, fontSize: 14 }}>{v}</div>
              <div style={{ fontSize: 11, color: COLORS.textLight }}>{l}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Weight logging */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <h3 style={{ margin: "0 0 12px", fontSize: 15, fontWeight: 700, color: COLORS.text }}>⚖️ Gewicht eintragen</h3>
        <div style={{ display: "flex", gap: 10 }}>
          <input type="number" step="0.1" value={weightInput} onChange={e => setWeightInput(e.target.value)} placeholder={`z.B. ${lastWeight?.value || 75.0}`}
            style={{ flex: 1, padding: "11px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 15, color: COLORS.text, outline: "none", background: COLORS.bg }} />
          <button onClick={logWeight} style={{ padding: "11px 20px", background: COLORS.primary, color: "#fff", border: "none", borderRadius: 10, fontWeight: 700, cursor: "pointer" }}>Eintragen</button>
        </div>
        {state.weight.length > 0 && (
          <div style={{ marginTop: 14 }}>
            <WeightChart data={state.weight.slice(-7)} />
          </div>
        )}
      </div>

      {/* Goals */}
      <div style={{ background: COLORS.card, borderRadius: 16, padding: 16, marginBottom: 12, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: COLORS.text }}>🎯 Nährstoffziele</h3>
          <button onClick={() => editGoals ? saveGoals() : setEditGoals(true)}
            style={{ background: editGoals ? COLORS.primary : COLORS.surface, color: editGoals ? "#fff" : COLORS.textMid, border: "none", borderRadius: 8, padding: "6px 14px", cursor: "pointer", fontWeight: 600, fontSize: 13 }}>
            {editGoals ? "Speichern" : "Bearbeiten"}
          </button>
        </div>
        {[["Kalorien","kcal","kcal"],["Protein","protein","g"],["Kohlenhydrate","carbs","g"],["Fett","fat","g"],["Ballaststoffe","fiber","g"]].map(([l,k,u]) => (
          <div key={k} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "9px 0", borderBottom: `1px solid ${COLORS.border}` }}>
            <span style={{ fontSize: 14, color: COLORS.textMid }}>{l}</span>
            {editGoals ? (
              <input type="number" value={goalDraft[k]} onChange={e => setGoalDraft({...goalDraft, [k]: parseInt(e.target.value)||0})}
                style={{ width: 80, padding: "5px 8px", border: `1.5px solid ${COLORS.border}`, borderRadius: 8, fontSize: 14, fontWeight: 700, color: COLORS.text, outline: "none", textAlign: "right" }} />
            ) : (
              <span style={{ fontSize: 14, fontWeight: 700, color: COLORS.primary }}>{state.goals[k]} {u}</span>
            )}
          </div>
        ))}
      </div>

      {/* Settings */}
      <div style={{ background: COLORS.card, borderRadius: 16, overflow: "hidden", boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        {[["🔔","Erinnerungen"],["📊","Exportieren"],["🔗","Fitness-Apps verbinden"],["❓","Hilfe & Support"]].map(([i,l]) => (
          <div key={l} style={{ display: "flex", alignItems: "center", padding: "15px 16px", borderBottom: `1px solid ${COLORS.border}`, cursor: "pointer" }}>
            <span style={{ fontSize: 18, marginRight: 12 }}>{i}</span>
            <span style={{ fontSize: 14, color: COLORS.text, flex: 1 }}>{l}</span>
            <span style={{ color: COLORS.textLight }}>›</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── HOME (DASHBOARD) SCREEN ──────────────────────────────────────────────────
function HomeScreen({ state, dispatch }) {
  const diary = state.diary[today] || { breakfast: [], lunch: [], dinner: [], snack: [] };
  const totals = sumDay(diary);
  const goals = state.goals;
  const [showWeightModal, setShowWeightModal] = useState(false);
  const [weightInput, setWeightInput] = useState("");

  const timeHour = new Date().getHours();
  const greeting = timeHour < 12 ? "Guten Morgen" : timeHour < 18 ? "Guten Tag" : "Guten Abend";

  const lastWeight = state.weight[state.weight.length - 1];

  const meals = [
    { key: "breakfast", label: "Frühstück", icon: "☀️", color: COLORS.breakfast },
    { key: "lunch", label: "Mittagessen", icon: "🌤️", color: COLORS.lunch },
    { key: "dinner", label: "Abendessen", icon: "🌙", color: COLORS.dinner },
    { key: "snack", label: "Snacks", icon: "🍎", color: COLORS.snack },
  ];

  return (
    <div style={{ padding: "0 0 80px" }}>
      {/* Header */}
      <div style={{ background: `linear-gradient(135deg, ${COLORS.primary}, ${COLORS.primaryDark})`, padding: "24px 20px 32px", color: "#fff" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 20 }}>
          <div>
            <div style={{ fontSize: 13, opacity: 0.8, marginBottom: 2 }}>{greeting} 👋</div>
            <div style={{ fontSize: 20, fontWeight: 700 }}>Dein Tag im Überblick</div>
          </div>
          <StreakBadge streak={state.streak} />
        </div>

        {/* Big calorie ring */}
        <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
          <div style={{ position: "relative" }}>
            <svg width={110} height={110}>
              <circle cx={55} cy={55} r={46} fill="none" stroke="rgba(255,255,255,0.25)" strokeWidth={10} />
              <circle cx={55} cy={55} r={46} fill="none" stroke="rgba(255,255,255,0.9)" strokeWidth={10}
                strokeDasharray={`${Math.min(totals.kcal/goals.kcal,1) * 2*Math.PI*46} ${2*Math.PI*46}`}
                strokeLinecap="round" transform="rotate(-90 55 55)" style={{ transition: "stroke-dasharray 0.5s" }} />
            </svg>
            <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
              <span style={{ fontSize: 22, fontWeight: 800, color: "#fff", lineHeight: 1 }}>{Math.max(goals.kcal - totals.kcal, 0)}</span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.8)" }}>kcal übrig</span>
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 10 }}>
              <div style={{ textAlign: "center" }}>
                <div style={{ fontSize: 18, fontWeight: 700, color: "#fff" }}>{totals.kcal}</div>
                <div style={{ fontSize: 10, color: "rgba(255,255,255,0.7)" }}>gegessen</div>
              </div>
              <div style={{ textAlign: "center" }}>
                <div style={{ fontSize: 18, fontWeight: 700, color: "#fff" }}>{goals.kcal}</div>
                <div style={{ fontSize: 10, color: "rgba(255,255,255,0.7)" }}>Ziel</div>
              </div>
            </div>
            {[["Protein", totals.protein, goals.protein, COLORS.protein], ["Kohlenhydr.", totals.carbs, goals.carbs, COLORS.carb], ["Fett", totals.fat, goals.fat, COLORS.fat]].map(([l,v,g,c]) => (
              <div key={l} style={{ marginBottom: 6 }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 2 }}>
                  <span style={{ fontSize: 11, color: "rgba(255,255,255,0.8)" }}>{l}</span>
                  <span style={{ fontSize: 11, color: "#fff", fontWeight: 600 }}>{v}g</span>
                </div>
                <div style={{ background: "rgba(255,255,255,0.25)", borderRadius: 4, height: 5 }}>
                  <div style={{ width: `${Math.min((v/g)*100,100)}%`, height: "100%", background: "#fff", borderRadius: 4, transition: "width 0.4s" }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Quick meal overview cards */}
      <div style={{ margin: "16px 16px 0", display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        {meals.map(({ key, label, icon, color }) => {
          const entries = diary[key] || [];
          const mTotals = sumMeal(entries);
          return (
            <div key={key} style={{ background: COLORS.card, borderRadius: 14, padding: 14, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
                <div style={{ width: 28, height: 28, borderRadius: 8, background: color+"22", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 14 }}>{icon}</div>
                <span style={{ fontSize: 12, fontWeight: 700, color: COLORS.textMid }}>{label}</span>
              </div>
              <div style={{ fontSize: 18, fontWeight: 800, color: entries.length > 0 ? COLORS.primary : COLORS.border }}>{mTotals.kcal} kcal</div>
              <div style={{ fontSize: 11, color: COLORS.textLight }}>{entries.length} Einträge</div>
            </div>
          );
        })}
      </div>

      {/* Streak card */}
      <div style={{ margin: "12px 16px", background: `linear-gradient(135deg, ${COLORS.accent}, #FF8C00)`, borderRadius: 16, padding: 16, display: "flex", alignItems: "center", gap: 14 }}>
        <div style={{ fontSize: 40 }}>🔥</div>
        <div>
          <div style={{ fontSize: 18, fontWeight: 800, color: "#fff" }}>{state.streak}-Tage-Streak!</div>
          <div style={{ fontSize: 13, color: "rgba(255,255,255,0.85)" }}>Du bist auf einem guten Weg. Weiter so!</div>
        </div>
      </div>

      {/* Weight quick entry */}
      <div style={{ margin: "0 16px 12px", background: COLORS.card, borderRadius: 16, padding: 16, boxShadow: "0 1px 6px rgba(0,0,0,0.05)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: COLORS.text }}>⚖️ Aktuelles Gewicht</div>
            <div style={{ fontSize: 22, fontWeight: 800, color: COLORS.primary }}>{lastWeight?.value || "—"} kg</div>
          </div>
          <button onClick={() => setShowWeightModal(true)} style={{ background: COLORS.surface, border: "none", borderRadius: 10, padding: "8px 14px", cursor: "pointer", fontSize: 13, fontWeight: 600, color: COLORS.textMid }}>Eintragen</button>
        </div>
      </div>

      {showWeightModal && (
        <Modal title="Gewicht eintragen" onClose={() => setShowWeightModal(false)}>
          <div style={{ display: "flex", gap: 10 }}>
            <input type="number" step="0.1" value={weightInput} onChange={e => setWeightInput(e.target.value)} placeholder="75.0 kg" autoFocus
              style={{ flex: 1, padding: "12px 14px", border: `1.5px solid ${COLORS.border}`, borderRadius: 10, fontSize: 16, color: COLORS.text, outline: "none", background: COLORS.bg }} />
            <button onClick={() => {
              const v = parseFloat(weightInput);
              if (!isNaN(v) && v > 0) { dispatch({ type: "LOG_WEIGHT", date: today, value: v }); setShowWeightModal(false); setWeightInput(""); }
            }} style={{ padding: "12px 20px", background: COLORS.primary, color: "#fff", border: "none", borderRadius: 10, fontWeight: 700, cursor: "pointer" }}>✓</button>
          </div>
        </Modal>
      )}
    </div>
  );
}

// ─── REDUCER ──────────────────────────────────────────────────────────────────
function reducer(state, action) {
  switch (action.type) {
    case "ADD_ENTRY": {
      const dayDiary = state.diary[action.date] || { breakfast: [], lunch: [], dinner: [], snack: [] };
      return {
        ...state, diary: {
          ...state.diary,
          [action.date]: { ...dayDiary, [action.mealKey]: [...dayDiary[action.mealKey], action.entry] }
        },
        streak: action.date === today ? Math.max(state.streak, 1) : state.streak,
      };
    }
    case "REMOVE_ENTRY": {
      const dayDiary = state.diary[action.date] || { breakfast: [], lunch: [], dinner: [], snack: [] };
      return {
        ...state, diary: {
          ...state.diary,
          [action.date]: { ...dayDiary, [action.mealKey]: dayDiary[action.mealKey].filter(e => e.id !== action.entryId) }
        }
      };
    }
    case "SET_GOALS":
      return { ...state, goals: action.goals };
    case "LOG_WEIGHT":
      return { ...state, weight: [...state.weight.filter(w => w.date !== action.date), { date: action.date, value: action.value }] };
    case "TOGGLE_FAVORITE":
      return { ...state, favorites: state.favorites.includes(action.foodId) ? state.favorites.filter(f => f !== action.foodId) : [...state.favorites, action.foodId] };
    case "ADD_RECIPE":
      return { ...state, recipes: [...state.recipes, action.recipe] };
    case "ADD_CUSTOM_FOOD":
      return { ...state, customFoods: [...state.customFoods, action.food] };
    default:
      return state;
  }
}

// ─── MAIN APP ─────────────────────────────────────────────────────────────────
export default function NutriSnap() {
  const [state, dispatch] = useState(() => INITIAL_STATE);
  const [tab, setTab] = useState("home");

  function dispatchFn(action) {
    setState(prev => reducer(prev, action));
  }
  const [, setState] = useState(INITIAL_STATE);

  // Combined state+dispatch
  const [appState, setAppState] = useState(INITIAL_STATE);
  function appDispatch(action) { setAppState(prev => reducer(prev, action)); }

  const tabs = [
    { key: "home", label: "Home", icon: "🏠" },
    { key: "diary", label: "Tagebuch", icon: "📔" },
    { key: "search", label: "Suchen", icon: "🔍" },
    { key: "recipes", label: "Rezepte", icon: "🍽️" },
    { key: "analysis", label: "Analyse", icon: "📊" },
    { key: "profile", label: "Profil", icon: "👤" },
  ];

  return (
    <div style={{ fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif", background: COLORS.bg, minHeight: "100vh", maxWidth: 480, margin: "0 auto", position: "relative" }}>
      <style>{`
        @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
        * { box-sizing: border-box; }
        ::-webkit-scrollbar { width: 0; }
      `}</style>

      {/* Status bar */}
      <div style={{ background: COLORS.primary, padding: "10px 20px 8px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={{ fontWeight: 800, fontSize: 18, color: "#fff", letterSpacing: -0.5 }}>NutriSnap</span>
        <StreakBadge streak={appState.streak} />
      </div>

      {/* Content */}
      <div style={{ overflowY: "auto", height: "calc(100vh - 120px)" }}>
        {tab === "home" && <HomeScreen state={appState} dispatch={appDispatch} />}
        {tab === "diary" && <DiaryScreen state={appState} dispatch={appDispatch} />}
        {tab === "search" && <SearchScreen state={appState} dispatch={appDispatch} />}
        {tab === "recipes" && <RecipesScreen state={appState} dispatch={appDispatch} />}
        {tab === "analysis" && <AnalysisScreen state={appState} />}
        {tab === "profile" && <ProfileScreen state={appState} dispatch={appDispatch} />}
      </div>

      {/* Bottom nav */}
      <div style={{ position: "fixed", bottom: 0, left: "50%", transform: "translateX(-50%)", width: "100%", maxWidth: 480, background: COLORS.card, borderTop: `1px solid ${COLORS.border}`, display: "flex", zIndex: 50, paddingBottom: 4 }}>
        {tabs.map(({ key, label, icon }) => (
          <button key={key} onClick={() => setTab(key)} style={{
            flex: 1, padding: "8px 4px 6px", border: "none", background: "none", cursor: "pointer",
            display: "flex", flexDirection: "column", alignItems: "center", gap: 2,
          }}>
            <span style={{ fontSize: tab === key ? 22 : 18, transition: "font-size 0.15s" }}>{icon}</span>
            <span style={{ fontSize: 9, fontWeight: 700, color: tab === key ? COLORS.primary : COLORS.textLight }}>{label}</span>
            {tab === key && <div style={{ width: 4, height: 4, borderRadius: "50%", background: COLORS.primary }} />}
          </button>
        ))}
      </div>
    </div>
  );
}
