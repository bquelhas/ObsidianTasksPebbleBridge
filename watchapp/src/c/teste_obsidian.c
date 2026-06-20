#include <pebble.h>

// Physics-based elastic easing (spring-back) function using integer lookup table
// to avoid linking float math.h functions that exceed Pebble RAM limits (e.g. on Aplite).
static int32_t ease_out_elastic(int32_t t) {
  if (t <= 0) return 0;
  if (t >= 1000) return 1000;
  static const int32_t table[11] = { 0, 809, 1295, 1183, 962, 918, 983, 1024, 1015, 997, 1000 };
  int idx = t / 100;
  int rem = t % 100;
  if (idx >= 10) return 1000;
  return table[idx] + ((table[idx + 1] - table[idx]) * rem) / 100;
}


// Color interpolation for smooth details fade-in (Pebble 64-color palette compatible)
static GColor color_interpolate(GColor c1, GColor c2, int percent) {
  #if defined(PBL_COLOR)
  int r = (int)c1.r + (((int)c2.r - (int)c1.r) * percent) / 100;
  int g = (int)c1.g + (((int)c2.g - (int)c1.g) * percent) / 100;
  int b = (int)c1.b + (((int)c2.b - (int)c1.b) * percent) / 100;
  int a = (int)c1.a + (((int)c2.a - (int)c1.a) * percent) / 100;
  GColor color;
  color.r = r;
  color.g = g;
  color.b = b;
  color.a = a;
  return color;
  #else
  return (percent < 50) ? c1 : c2;
  #endif
}

#define MAX_ITEMS      30
#define CHAR_LIMIT     100
#define MAX_DONE_LIST  20

#define ROUND_MARGIN   20   // curve-safe horizontal inset for centred text on chalk
#define ICON_ZONE      22   // right-edge width reserved for the urgency icon

#define KEY_ACTION    90
#define KEY_INDEX     91
#define KEY_DELAY     92
#define KEY_TASK_TEXT 93
#define KEY_BG_COLOR  1000   // config: background colour (argb8), from Clay/Android
#define KEY_VOICE_ON  1001   // config: show the "New note" voice row (bool), from Clay
#define KEY_TL_TOKEN  1002   // timeline token from PebbleKit JS, relayed to Android

// --- Theme (Pebble design guidelines: cohesive accent; red reserved for alerts) ---
#define COLOR_ACCENT      GColorJaegerGreen
#define COLOR_ACCENT_TEXT GColorWhite
#define COLOR_ALERT       GColorDarkCandyAppleRed
#define COLOR_OFFLINE     GColorYellow
// Configurable background (default Obsidian-ish purple); derived contrast colours.
static GColor s_bg_color;   // list + title-bar background
static GColor s_fg_color;   // text/icon on the background (auto black/white)
static GColor s_sel_bg;     // selected-row background (opposite luminance)
static GColor s_sel_fg;     // selected-row text

// Persistent storage keys
#define PERSIST_KEY_COUNT        100
#define PERSIST_KEY_DATA_START   200
#define PERSIST_KEY_DONE_COUNT   299
#define PERSIST_KEY_DONE_START   300  // 300..319
#define PERSIST_KEY_REMINDER_TXT 500
#define PERSIST_KEY_DONE_VERSION 600  // bump DONE_LIST_VERSION to wipe stale done-list
#define PERSIST_KEY_SELECTED     700  // last selected row (restored on next launch)
#define PERSIST_KEY_BG_COLOR     800  // configurable background colour
#define PERSIST_KEY_VOICE_ON     801  // show "New note" voice row (bool)
#define PERSIST_KEY_SYNC_TS      802  // epoch of the last received list (last sync)
#define DONE_LIST_VERSION        2

typedef struct {
  char text[CHAR_LIMIT];  // clean title (or header text)
  char tag[10];           // "HEADER" or urgency code: W/A/C/N
  char due[24];           // relative due text (shown on selected row)
} TaskItem;

// --- Windows ---
static Window    *s_main_window;
static MenuLayer *s_menu_layer;
static TextLayer *s_title_layer;
#if defined(PBL_ROUND)
// On round (Pebble Time Round) the top-centre zone — the only area the circle
// never clips — carries the SELECTED row's icon (mic / urgency / note status)
// instead of the clock. Rows themselves are pure centred text. This layer sits
// on top of the title text layer and stays transparent while a flash message or
// the Offline banner is showing, so those still read through.
static Layer *s_round_icon_layer;
#endif

static Window    *s_action_window;
static MenuLayer *s_action_menu_layer;

static Window    *s_day_window;        // "Pick a day" weekday submenu
static MenuLayer *s_day_menu_layer;

// Full-screen action-feedback overlay (Done check / Remind clock)
static Window *s_fb_window;
static Layer  *s_fb_layer;
static int     s_fb_kind     = 0;   // 0 = done check, 1 = remind clock
static int     s_fb_progress = 0;   // 0..FB_STEPS animation progress

// --- Data ---
static TaskItem s_items[MAX_ITEMS];
static int      s_item_count = 0;

// LECO 2014 font (group titles + open-task title). We use the *system* LECO
// font rather than a bundled custom TTF: the Pebble Time 2 / Core Devices
// firmware crashes on fonts_load_custom_font, and its system LECO font carries
// a full alphabet. On older firmware the same key is numbers-only, so letters
// fall back to tofu boxes there -- an acceptable tradeoff that avoids the crash.
static GFont leco_font(void) {
  return fonts_get_system_font(FONT_KEY_LECO_20_BOLD_NUMBERS);
}

static char s_done_list[MAX_DONE_LIST][CHAR_LIMIT];
static int  s_done_count = 0;

static int       s_selected_row      = 0;
static int       s_action_task_index = 0;
static AppTimer *s_reload_timer      = NULL;
static AppTimer *s_fade_timer        = NULL;
static int       s_fade_progress     = 0;
static bool      s_in_reload         = false;  // re-entrancy guard for deferred_reload
static AppTimer *s_title_timer       = NULL;
static char      s_title_buf[32];
static TaskItem  s_new_items[MAX_ITEMS]; // global to avoid stack overflow in callback
static PropertyAnimation *s_title_anim = NULL; // title attention bounce (change 8)
static bool      s_pt                = false;  // system locale is Portuguese
static bool      s_connected         = true;   // BT/phone connection (change 3)
static char      s_clock_buf[8];               // "HH:MM" for the title bar
static time_t    s_last_sync         = 0;      // when the watch last got a list (#9)
static int16_t   s_menu_w             = 144;   // menu width (set on load; for row layout)

// --- Voice note ("New note" row + pending placeholders) ---
// Only on watches with a microphone (PBL_MICROPHONE). Visibility is also gated
// by a Clay toggle (the SDK gives no way to know if Rebble voice is active, so
// the user controls it). Menu layout, top to bottom:
//   [voice row (0)] [pending placeholders] [task rows...]
// A pending placeholder is shown the instant a note is dictated and removed when
// Android acks the save (KEY_ACTION "NOTE_OK") or a fallback timeout fires.
// s_selected_row stores the *menu row* index; row_to_task() converts to a task.
#if defined(PBL_MICROPHONE)
#define MAX_PENDING 5
static bool s_voice_enabled = true;
static DictationSession *s_dictation = NULL;
static char s_pending[MAX_PENDING][CHAR_LIMIT];
static time_t s_pending_at[MAX_PENDING];   // queued-at time (for the slow-sync warning)
static bool s_pending_warn[MAX_PENDING];   // sync taking too long (>1h) OR Android reported a failure
static char s_pending_msg[MAX_PENDING][48]; // failure reason from Android (NOTE_ERR), else empty
static int  s_pending_count = 0;
static AppTimer *s_pending_timer = NULL;
#define PENDING_WARN_SECS 3600             // flag a sync problem after 1h
#endif

static inline int vrows(void) {
#if defined(PBL_MICROPHONE)
  return s_voice_enabled ? 1 : 0;
#else
  return 0;
#endif
}
static inline int prows(void) {
#if defined(PBL_MICROPHONE)
  return s_pending_count;
#else
  return 0;
#endif
}
// Non-task rows at the top of the list (voice row + pending placeholders).
static inline int head_rows(void) { return vrows() + prows(); }
static inline bool is_voice_row(int row)   { return (vrows() > 0) && (row == 0); }
static inline bool is_pending_row(int row) { return (row >= vrows()) && (row < head_rows()); }
static inline int  pending_index(int row)  { return row - vrows(); }      // 0..prows()-1
static inline int  row_to_task(int row)    { return row - head_rows(); }  // <0 on head rows

// Bilingual strings
static const char *STR_TITLE;
static const char *STR_LOADING;
static const char *STR_EMPTY;
static const char *STR_NEWNOTE;
static const char *s_action_options[6];
static const char *s_day_options[7];   // weekday submenu (Mon..Sun)

static void setup_strings() {
  const char *locale = i18n_get_system_locale();
  bool pt = locale && (strncmp(locale, "pt", 2) == 0);
  s_pt = pt;

  STR_TITLE          = "Obsidian Tasks";
  STR_LOADING        = pt ? "A carregar..."  : "Loading...";
  STR_EMPTY          = pt ? "Tudo feito!"    : "All done!";
  STR_NEWNOTE        = pt ? "Nova nota"      : "New note";

  s_action_options[0] = pt ? "Concluir"         : "Done";
  s_action_options[1] = pt ? "Lembrar 1h"       : "Remind 1h";
  s_action_options[2] = pt ? "Logo à noite"     : "Tonight";
  s_action_options[3] = pt ? "Amanhã de manhã"  : "Tomorrow morning";
  s_action_options[4] = pt ? "Próxima semana"   : "Next week";
  s_action_options[5] = pt ? "Escolher dia..."  : "Pick a day...";

  // Weekday submenu — next occurrence of each day at 09:00 (Mon..Sun).
  s_day_options[0] = pt ? "Segunda"  : "Monday";
  s_day_options[1] = pt ? "Terça"    : "Tuesday";
  s_day_options[2] = pt ? "Quarta"   : "Wednesday";
  s_day_options[3] = pt ? "Quinta"   : "Thursday";
  s_day_options[4] = pt ? "Sexta"    : "Friday";
  s_day_options[5] = pt ? "Sábado"   : "Saturday";
  s_day_options[6] = pt ? "Domingo"  : "Sunday";
}

// ==============================================================
// COLOURS (configurable background + auto-contrast)
// ==============================================================

// Perceived luminance of a GColor (channels are 0..3). Dark → light text.
static bool color_is_dark(GColor c) {
  int lum = c.r * 54 + c.g * 182 + c.b * 18;  // max 3*254 = 762
  return lum < 360;
}

// Recompute text/selection colours from the chosen background and repaint.
static void apply_colors(void) {
  if (color_is_dark(s_bg_color)) {
    s_fg_color = GColorWhite;
    s_sel_bg   = GColorWhite;  s_sel_fg = GColorBlack;
  } else {
    s_fg_color = GColorBlack;
    s_sel_bg   = GColorBlack;  s_sel_fg = GColorWhite;
  }
  if (s_main_window) window_set_background_color(s_main_window, s_bg_color);
  if (s_menu_layer) {
    menu_layer_set_normal_colors(s_menu_layer, s_bg_color, s_fg_color);
    menu_layer_set_highlight_colors(s_menu_layer, s_sel_bg, s_sel_fg);
  }
  if (s_title_layer) {
    text_layer_set_background_color(s_title_layer, s_bg_color);
    text_layer_set_text_color(s_title_layer, s_fg_color);
  }
  if (s_menu_layer)  menu_layer_reload_data(s_menu_layer);
}

static void load_bg_color(void) {
  if (persist_exists(PERSIST_KEY_BG_COLOR)) {
    s_bg_color.argb = (uint8_t)persist_read_int(PERSIST_KEY_BG_COLOR);
  } else {
    s_bg_color = GColorFromRGB(85, 0, 170);  // default Obsidian purple (#5500AA, deeper)
  }
}

// ==============================================================
// CACHE
// ==============================================================

static void save_cache() {
  persist_write_int(PERSIST_KEY_COUNT, s_item_count);
  for (int i = 0; i < s_item_count; i++) {
    char buffer[CHAR_LIMIT + 40];
    // format: tag \x1f due \x1f text
    snprintf(buffer, sizeof(buffer), "%s\x1f%s\x1f%s",
             s_items[i].tag, s_items[i].due, s_items[i].text);
    persist_write_string(PERSIST_KEY_DATA_START + i, buffer);
  }
}

static void load_cache() {
  if (persist_exists(PERSIST_KEY_COUNT)) {
    s_item_count = persist_read_int(PERSIST_KEY_COUNT);
    if (s_item_count > MAX_ITEMS) s_item_count = MAX_ITEMS;
    for (int i = 0; i < s_item_count; i++) {
      s_items[i].tag[0] = '\0';
      s_items[i].due[0] = '\0';
      s_items[i].text[0] = '\0';
      if (!persist_exists(PERSIST_KEY_DATA_START + i)) continue;
      char buffer[CHAR_LIMIT + 40];
      persist_read_string(PERSIST_KEY_DATA_START + i, buffer, sizeof(buffer));
      char *s1 = strchr(buffer, '\x1f');
      if (s1) {
        *s1 = '\0';
        strncpy(s_items[i].tag, buffer, sizeof(s_items[i].tag) - 1);
        s_items[i].tag[sizeof(s_items[i].tag) - 1] = '\0';
        char *s2 = strchr(s1 + 1, '\x1f');
        if (s2) {
          *s2 = '\0';
          strncpy(s_items[i].due,  s1 + 1, sizeof(s_items[i].due) - 1);
          s_items[i].due[sizeof(s_items[i].due) - 1] = '\0';
          strncpy(s_items[i].text, s2 + 1, sizeof(s_items[i].text) - 1);
          s_items[i].text[sizeof(s_items[i].text) - 1] = '\0';
        } else {
          strncpy(s_items[i].text, s1 + 1, sizeof(s_items[i].text) - 1);
          s_items[i].text[sizeof(s_items[i].text) - 1] = '\0';
        }
      } else {
        strncpy(s_items[i].text, buffer, sizeof(s_items[i].text) - 1);
        s_items[i].text[sizeof(s_items[i].text) - 1] = '\0';
      }
    }
  } else {
    s_item_count = 1;
    strncpy(s_items[0].text, STR_LOADING, sizeof(s_items[0].text) - 1);
    s_items[0].text[sizeof(s_items[0].text) - 1] = '\0';
    s_items[0].tag[0] = '\0';
    s_items[0].due[0] = '\0';
  }
}

// Remove item + clean orphaned headers
static void remove_item(int index) {
  if (index < 0 || index >= s_item_count) return;

  for (int i = index; i < s_item_count - 1; i++) s_items[i] = s_items[i + 1];
  s_item_count--;

  int prev = index - 1;
  if (prev >= 0 && strcmp(s_items[prev].tag, "HEADER") == 0) {
    bool next_is_task = (index < s_item_count && strcmp(s_items[index].tag, "HEADER") != 0);
    if (!next_is_task) {
      for (int i = prev; i < s_item_count - 1; i++) s_items[i] = s_items[i + 1];
      s_item_count--;
    }
  }

  s_selected_row = (s_selected_row > 0) ? s_selected_row - 1 : 0;
  save_cache();
}

// ==============================================================
// DONE LIST (persistent — prevents re-appearing after sync)
// ==============================================================

static void load_done_list() {
  // One-time migration: if the stored done-list predates DONE_LIST_VERSION it may
  // contain stale entries (tasks "done" on the watch back when the DONE message
  // never reached Android, so they were never marked [x] and stay hidden forever).
  // Wipe it on a version bump so those tasks reappear.
  int stored_ver = persist_exists(PERSIST_KEY_DONE_VERSION)
                     ? persist_read_int(PERSIST_KEY_DONE_VERSION) : 0;
  if (stored_ver != DONE_LIST_VERSION) {
    for (int i = 0; i < MAX_DONE_LIST; i++) {
      if (persist_exists(PERSIST_KEY_DONE_START + i))
        persist_delete(PERSIST_KEY_DONE_START + i);
    }
    if (persist_exists(PERSIST_KEY_DONE_COUNT)) persist_delete(PERSIST_KEY_DONE_COUNT);
    persist_write_int(PERSIST_KEY_DONE_VERSION, DONE_LIST_VERSION);
    s_done_count = 0;
    return;
  }

  if (!persist_exists(PERSIST_KEY_DONE_COUNT)) return;
  s_done_count = persist_read_int(PERSIST_KEY_DONE_COUNT);
  if (s_done_count > MAX_DONE_LIST) s_done_count = MAX_DONE_LIST;
  for (int i = 0; i < s_done_count; i++) {
    if (persist_exists(PERSIST_KEY_DONE_START + i))
      persist_read_string(PERSIST_KEY_DONE_START + i, s_done_list[i], CHAR_LIMIT);
  }
}

static void save_done_list() {
  persist_write_int(PERSIST_KEY_DONE_COUNT, s_done_count);
  for (int i = 0; i < s_done_count; i++)
    persist_write_string(PERSIST_KEY_DONE_START + i, s_done_list[i]);
}

// NOTE: matches only the first CHAR_LIMIT (100) chars. Two tasks sharing their first
// 100 chars are indistinguishable here, so completing one could hide the other. The
// stored text is already capped at CHAR_LIMIT, and the done-list self-prunes on each
// sync (see filter below), so a stale hide self-corrects — acceptable trade-off.
static bool is_done(const char *text) {
  for (int i = 0; i < s_done_count; i++)
    if (strncmp(s_done_list[i], text, CHAR_LIMIT) == 0) return true;
  return false;
}

static void add_to_done_list(const char *text) {
  if (is_done(text)) return;
  if (s_done_count >= MAX_DONE_LIST) {
    // Evict oldest
    for (int i = 0; i < MAX_DONE_LIST - 1; i++)
      strncpy(s_done_list[i], s_done_list[i + 1], CHAR_LIMIT);
    s_done_count = MAX_DONE_LIST - 1;
  }
  strncpy(s_done_list[s_done_count++], text, CHAR_LIMIT - 1);
  save_done_list();
}

// Self-prune the done-list: drop any entry whose task is no longer present
// (as an open task) in the freshly received list. Once Android writes the ✅
// and the task disappears from a sync, that done-entry is obsolete — so a task
// later re-opened in Obsidian with the same text will show on the watch again
// instead of being silently filtered forever.
// Must run BEFORE filter_done_from_list(), while s_items still holds the full
// incoming list (done items not yet stripped).
static void prune_done_list() {
  int kept = 0;
  for (int i = 0; i < s_done_count; i++) {
    bool present = false;
    for (int j = 0; j < s_item_count; j++) {
      if (strcmp(s_items[j].tag, "HEADER") == 0) continue;
      if (strncmp(s_items[j].text, s_done_list[i], CHAR_LIMIT) == 0) { present = true; break; }
    }
    if (present) {
      if (kept != i) strncpy(s_done_list[kept], s_done_list[i], CHAR_LIMIT);
      kept++;
    }
  }
  if (kept != s_done_count) {
    s_done_count = kept;
    save_done_list();
  }
}

// Filter tasks already completed from a fresh list received from Android.
// Uses the global s_new_items as scratch (a local TaskItem[MAX_ITEMS] would
// overflow the Pebble stack — ~3300 bytes — and crash the app on receive).
static void filter_done_from_list() {
  TaskItem *temp = s_new_items;
  int new_count = 0;

  for (int i = 0; i < s_item_count; i++) {
    if (strcmp(s_items[i].tag, "HEADER") != 0 && is_done(s_items[i].text)) continue;
    temp[new_count++] = s_items[i];
  }

  // Remove orphaned headers
  s_item_count = 0;
  for (int i = 0; i < new_count; i++) {
    if (strcmp(temp[i].tag, "HEADER") == 0) {
      if (i + 1 < new_count && strcmp(temp[i + 1].tag, "HEADER") != 0)
        s_items[s_item_count++] = temp[i];
    } else {
      s_items[s_item_count++] = temp[i];
    }
  }
}

// ==============================================================
// TITLE BAR (baseline / connection state / transient flashes)
// ==============================================================

static void update_clock(void) {
  time_t now = time(NULL);
  struct tm *t = localtime(&now);
  strftime(s_clock_buf, sizeof(s_clock_buf),
           clock_is_24h_style() ? "%H:%M" : "%I:%M", t);
}

// Repaint the round top-centre icon (no-op off round / before it exists).
static void round_icon_refresh(void) {
#if defined(PBL_ROUND)
  if (s_round_icon_layer) layer_mark_dirty(s_round_icon_layer);
#endif
}

// Restore the title to its connection-aware baseline: the clock on the chosen
// background when connected, a yellow "Offline" caution banner when unreachable.
static void apply_baseline_title() {
  if (!s_title_layer) return;
  if (s_connected) {
    update_clock();
    text_layer_set_background_color(s_title_layer, s_bg_color);
    text_layer_set_text_color(s_title_layer, s_fg_color);
    text_layer_set_text(s_title_layer, s_clock_buf);
  } else {
    text_layer_set_background_color(s_title_layer, COLOR_OFFLINE);
    text_layer_set_text_color(s_title_layer, GColorBlack);
    text_layer_set_text(s_title_layer, s_pt ? "Sem ligacao" : "Offline");
  }
  round_icon_refresh();   // baseline → show the selected-row icon (round only)
}

// Write the last-sync time as "HH:MM" (24h-aware) into buf. (#9)
static void format_sync_hm(char *buf, size_t n) {
  if (s_last_sync == 0) { buf[0] = '\0'; return; }
  struct tm *t = localtime(&s_last_sync);
  strftime(buf, n, clock_is_24h_style() ? "%H:%M" : "%I:%M", t);
}

// Minute tick: refresh the clock unless a transient flash is showing.
static void tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (!s_title_timer && s_connected) apply_baseline_title();
}

static void title_restore_callback(void *data) {
  s_title_timer = NULL;
  apply_baseline_title();
}

// Show a transient message in the title bar for 3s. alert=true paints it red
// (reserved for failures, per the Pebble guidelines).
static void flash_title(const char *msg, bool alert) {
  if (!s_title_layer) return;
  strncpy(s_title_buf, msg, sizeof(s_title_buf) - 1);
  s_title_buf[sizeof(s_title_buf) - 1] = '\0';
  text_layer_set_text(s_title_layer, s_title_buf);
  if (alert) {
    text_layer_set_background_color(s_title_layer, COLOR_ALERT);
    text_layer_set_text_color(s_title_layer, GColorWhite);
  }
  if (s_title_timer) app_timer_cancel(s_title_timer);
  s_title_timer = app_timer_register(3000, title_restore_callback, NULL);
  round_icon_refresh();   // a flash owns the title bar → hide the icon (round only)
}

static void title_anim_stopped(Animation *anim, bool finished, void *context) {
  s_title_anim = NULL;  // auto-destroyed by the framework
}

// Slide the title bar down into place to draw the eye to a content change.
static void title_bounce() {
  if (!s_title_layer || !s_main_window) return;
  Layer *tl = text_layer_get_layer(s_title_layer);
  GRect to_frame   = layer_get_frame(tl);
  GRect from_frame = to_frame;
  from_frame.origin.y -= 8;
  s_title_anim = property_animation_create_layer_frame(tl, &from_frame, &to_frame);
  if (!s_title_anim) return;
  Animation *a = property_animation_get_animation(s_title_anim);
  animation_set_duration(a, 280);
  animation_set_curve(a, AnimationCurveEaseOut);
  animation_set_handlers(a, (AnimationHandlers){ .stopped = title_anim_stopped }, NULL);
  animation_schedule(a);
}

// ==============================================================
// ACTION FEEDBACK (full-screen check / clock, Pebble-style)
// ==============================================================

#define FB_BADGE_R 42

static Animation *s_fb_anim = NULL;

// Helper: a point on a circle of radius `rad` around `c` at clock `angle`.
static GPoint fb_polar(GPoint c, int rad, int32_t angle) {
  return GPoint(c.x + sin_lookup(angle) * rad / TRIG_MAX_RATIO,
                c.y - cos_lookup(angle) * rad / TRIG_MAX_RATIO);
}

static void fb_layer_update(Layer *layer, GContext *ctx) {
  GRect  b = layer_get_bounds(layer);
  GPoint c = GPoint(b.size.w / 2, b.size.h / 2);
  GColor accent = (s_fb_kind == 0) ? COLOR_ACCENT : GColorVividCerulean;

  // Full-screen coloured wash.
  graphics_context_set_fill_color(ctx, accent);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_antialiased(ctx, true);

  int p = s_fb_progress;            // 0..1000
  if (p < 0) p = 0; if (p > 1000) p = 1000;

  // White badge disc pops in over the first 60% of the animation (0..600)
  // using our physical spring function.
  int badge_p = (p < 600) ? (p * 1000 / 600) : 1000;
  int eased_badge_p = ease_out_elastic(badge_p);
  int r = FB_BADGE_R * eased_badge_p / 1000;
  if (r < 1) return;
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_circle(ctx, c, r);

  // The glyph is drawn in the accent colour on top of the white badge.
  graphics_context_set_stroke_color(ctx, accent);
  graphics_context_set_fill_color(ctx, accent);

  if (s_fb_kind == 0) {
    // Checkmark draws itself on after the badge has mostly landed (420..1000).
    int cp = (p <= 420) ? 0 : (p - 420) * 1000 / 580;   // 0..1000
    if (cp <= 0) return;
    graphics_context_set_stroke_width(ctx, 6);
    GPoint p1 = GPoint(c.x - 17, c.y + 1);
    GPoint p2 = GPoint(c.x - 5,  c.y + 13);
    GPoint p3 = GPoint(c.x + 19, c.y - 14);

    if (cp <= 450) {
      int t = cp * 1000 / 450;
      GPoint current_end = GPoint(p1.x + (p2.x - p1.x) * t / 1000,
                                  p1.y + (p2.y - p1.y) * t / 1000);
      graphics_draw_line(ctx, p1, current_end);
      graphics_fill_circle(ctx, p1, 3);
      graphics_fill_circle(ctx, current_end, 3);
    } else {
      graphics_draw_line(ctx, p1, p2);
      graphics_fill_circle(ctx, p1, 3);
      graphics_fill_circle(ctx, p2, 3);
      int t = (cp - 450) * 1000 / 550;
      GPoint current_end = GPoint(p2.x + (p3.x - p2.x) * t / 1000,
                                  p2.y + (p3.y - p2.y) * t / 1000);
      graphics_draw_line(ctx, p2, current_end);
      graphics_fill_circle(ctx, current_end, 3);
    }
  } else {
    // Clock: ring + quarter ticks + a minute hand sweeping a full eased turn.
    graphics_context_set_stroke_width(ctx, 3);
    graphics_draw_circle(ctx, c, FB_BADGE_R - 7);
    for (int k = 0; k < 12; k++) {
      int32_t a = TRIG_MAX_ANGLE * k / 12;
      int len = (k % 3 == 0) ? 6 : 3;
      graphics_draw_line(ctx, fb_polar(c, FB_BADGE_R - 7, a),
                              fb_polar(c, FB_BADGE_R - 7 - len, a));
    }
    int sweep = (p <= 420) ? 0 : (p - 420) * 1000 / 580;   // 0..1000 after the pop
    int32_t eased_sweep = ease_out_elastic(sweep);
    int32_t mang = TRIG_MAX_ANGLE * eased_sweep / 1000;        // minute hand: full turn
    int32_t hang = (TRIG_MAX_ANGLE / 6) * eased_sweep / 1000;   // hour hand: to 2 o'clock
    graphics_context_set_stroke_width(ctx, 4);
    graphics_draw_line(ctx, c, fb_polar(c, FB_BADGE_R - 14, mang));
    graphics_draw_line(ctx, c, fb_polar(c, FB_BADGE_R - 22, hang));
    graphics_fill_circle(ctx, c, 3);
  }
}

static void fb_close_cb(void *data) {
  if (window_stack_get_top_window() == s_fb_window) window_stack_pop(true);
}

static void fb_anim_update(Animation *a, const AnimationProgress prog) {
  s_fb_progress = (int)((int32_t)prog * 1000 / ANIMATION_NORMALIZED_MAX);
  if (s_fb_layer) layer_mark_dirty(s_fb_layer);
}

static void fb_anim_stopped(Animation *a, bool finished, void *ctx) {
  s_fb_progress = 1000;
  if (s_fb_layer) layer_mark_dirty(s_fb_layer);
  s_fb_anim = NULL;
  app_timer_register(420, fb_close_cb, NULL);   // hold the finished frame briefly
}

static const AnimationImplementation s_fb_impl = { .update = fb_anim_update };

static void show_feedback(int kind) {
  s_fb_kind = kind;
  s_fb_progress = 0;
  window_stack_push(s_fb_window, false);
  vibes_short_pulse();                          // single tactile confirmation
  s_fb_anim = animation_create();
  animation_set_implementation(s_fb_anim, &s_fb_impl);
  animation_set_duration(s_fb_anim, 650);
  animation_set_curve(s_fb_anim, AnimationCurveEaseOut);
  animation_set_handlers(s_fb_anim, (AnimationHandlers){ .stopped = fb_anim_stopped }, NULL);
  animation_schedule(s_fb_anim);
}

static void fb_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_fb_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_fb_layer, fb_layer_update);
  layer_add_child(root, s_fb_layer);
}
static void fb_window_unload(Window *window) {
  layer_destroy(s_fb_layer);
  s_fb_layer = NULL;
}

// ==============================================================
// COMMUNICATION (DONE -> Android, best-effort/silent)
// ==============================================================

static void outbox_sent_cb(DictionaryIterator *iter, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_INFO, "OUTBOX sent OK");
}
static void outbox_failed_cb(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "OUTBOX FAILED reason=%d", (int)reason);
  vibes_long_pulse();                                  // change 2: alert on failure
  flash_title(s_pt ? "Falha sync" : "Sync failed", true);
}

static void send_to_android(const char *action, int index, int delay) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) return;
  dict_write_cstring(iter, KEY_ACTION, action);
  dict_write_int(iter, KEY_INDEX, &index, sizeof(int), true);
  dict_write_int(iter, KEY_DELAY,  &delay,  sizeof(int), true);
  app_message_outbox_send();
}

// Relay the timeline token (from PebbleKit JS) out to the Android companion so it
// can push timeline pins for dated tasks. Android receives it as a normal action.
static void send_token_to_android(const char *token) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) return;
  dict_write_cstring(iter, KEY_ACTION,    "TL_TOKEN");
  dict_write_cstring(iter, KEY_TASK_TEXT, token);
  app_message_outbox_send();
}

// Relay a packed timeline-pin payload (built by Android) out to PebbleKit JS.
// The JS pushes it to the Rebble timeline API; the Core app intercepts that HTTP
// call locally and syncs the pins to the watch (cloud->watch sync does not exist).
static void send_pins_to_js(const char *payload) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) return;
  dict_write_cstring(iter, KEY_ACTION,    "PINJS");
  dict_write_cstring(iter, KEY_TASK_TEXT, payload);
  app_message_outbox_send();
}

// The app was launched from a timeline pin action: forward the launch code, which
// packs the action type (top 4 bits) and the task hash (low 28 bits), so Android can
// run the matching action (Done / Remind ...) just like the in-app action menu.
static void send_pin_action_to_android(int code) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) return;
  dict_write_cstring(iter, KEY_ACTION, "PIN_ACT");
  dict_write_int(iter, KEY_INDEX, &code, sizeof(int), true);
  app_message_outbox_send();
}

// DONE sends task TEXT so Android can match reliably regardless of list differences
static void send_done_to_android(const char *task_text) {
  DictionaryIterator *iter;
  // If the outbox is busy/full the begin fails BEFORE send, so outbox_failed_cb
  // never fires. Without feedback the task looks done on the watch but Obsidian
  // never hears about it — the worst silent failure. Alert + bail so the user retries.
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) {
    vibes_long_pulse();
    flash_title(s_pt ? "Falha: repetir" : "Failed: retry", true);
    return;
  }
  dict_write_cstring(iter, KEY_ACTION,    "DONE");
  dict_write_cstring(iter, KEY_TASK_TEXT, task_text);
  app_message_outbox_send();
}

// REMIND backup sends task TEXT (not index) so Android's reminder shows the
// correct task even though its regenerated list differs from Pebble's filtered one
static void send_remind_to_android(const char *task_text, int delay) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) {
    vibes_long_pulse();
    flash_title(s_pt ? "Falha: repetir" : "Failed: retry", true);
    return;
  }
  dict_write_cstring(iter, KEY_ACTION,    "REMIND");
  dict_write_cstring(iter, KEY_TASK_TEXT, task_text);
  dict_write_int(iter, KEY_DELAY, &delay, sizeof(int), true);
  app_message_outbox_send();
}

static void deferred_reload(void *data);

#if defined(PBL_MICROPHONE)
// NOTE sends the dictated text; Android appends it to a predefined .md note.
static void send_note_to_android(const char *text) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK || !iter) {
    vibes_long_pulse();
    flash_title(s_pt ? "Falha: repetir" : "Failed: retry", true);
    return;
  }
  dict_write_cstring(iter, KEY_ACTION,    "NOTE");
  dict_write_cstring(iter, KEY_TASK_TEXT, text);
  app_message_outbox_send();
}

// --- Pending placeholders (shown until Android acks the save) ---
// A placeholder is removed ONLY when Android confirms the note reached Obsidian
// (KEY_ACTION "NOTE_OK"). It never auto-disappears; if the save takes too long
// (>1h) we flag it as a sync problem so the user notices, but keep it visible.
static void pending_check_cb(void *data);

static void schedule_pending_check(void) {
  if (s_pending_timer) { app_timer_cancel(s_pending_timer); s_pending_timer = NULL; }
  if (s_pending_count > 0)
    s_pending_timer = app_timer_register(60000, pending_check_cb, NULL);  // re-check each minute
}

static void pending_check_cb(void *data) {
  s_pending_timer = NULL;
  bool changed = false;
  time_t now = time(NULL);
  for (int i = 0; i < s_pending_count; i++) {
    if (!s_pending_warn[i] && (now - s_pending_at[i]) >= PENDING_WARN_SECS) {
      s_pending_warn[i] = true;
      changed = true;
    }
  }
  if (changed && s_menu_layer) menu_layer_reload_data(s_menu_layer);
  schedule_pending_check();              // keep watching while any remain
}

static void remove_pending_front(void) {
  if (s_pending_count <= 0) return;
  for (int i = 1; i < s_pending_count; i++) {
    strncpy(s_pending[i - 1], s_pending[i], CHAR_LIMIT);
    s_pending_at[i - 1]   = s_pending_at[i];
    s_pending_warn[i - 1] = s_pending_warn[i];
    strncpy(s_pending_msg[i - 1], s_pending_msg[i], sizeof(s_pending_msg[0]));
  }
  s_pending_count--;
  if (s_pending_count == 0 && s_pending_timer) {
    app_timer_cancel(s_pending_timer);
    s_pending_timer = NULL;
  }
  if (s_menu_layer) menu_layer_reload_data(s_menu_layer);
}

static void add_pending(const char *text) {
  if (s_pending_count >= MAX_PENDING) remove_pending_front();  // drop oldest
  strncpy(s_pending[s_pending_count], text, CHAR_LIMIT - 1);
  s_pending[s_pending_count][CHAR_LIMIT - 1] = '\0';
  s_pending_at[s_pending_count]   = time(NULL);
  s_pending_warn[s_pending_count] = false;
  s_pending_msg[s_pending_count][0] = '\0';
  s_pending_count++;
  schedule_pending_check();
  if (s_menu_layer) menu_layer_reload_data(s_menu_layer);
}

// Drop placeholders whose dictated text has come back as a real task in the
// freshly synced list — that round-trip (Android wrote it to Obsidian and
// re-sent the list) is what confirms the note landed, so the "?" placeholder
// waits for it rather than vanishing on the save itself.
static void reconcile_pending(void) {
  if (s_pending_count == 0) return;
  int w = 0;
  for (int r = 0; r < s_pending_count; r++) {
    bool landed = false;
    for (int j = 0; j < s_item_count; j++) {
      if (strcmp(s_items[j].tag, "HEADER") != 0 &&
          strcmp(s_items[j].text, s_pending[r]) == 0) { landed = true; break; }
    }
    if (!landed) {                       // keep the still-unconfirmed ones, compacted
      if (w != r) {
        strncpy(s_pending[w], s_pending[r], CHAR_LIMIT);
        s_pending_at[w]   = s_pending_at[r];
        s_pending_warn[w] = s_pending_warn[r];
        strncpy(s_pending_msg[w], s_pending_msg[r], sizeof(s_pending_msg[0]));
      }
      w++;
    }
  }
  s_pending_count = w;
  if (s_pending_count == 0 && s_pending_timer) {
    app_timer_cancel(s_pending_timer);
    s_pending_timer = NULL;
  }
}

static void dictation_status_cb(DictationSession *session, DictationSessionStatus status,
                                char *transcription, void *context) {
  if (status == DictationSessionStatusSuccess && transcription && transcription[0]) {
    add_pending(transcription);            // show the placeholder immediately
    send_note_to_android(transcription);
    flash_title(s_pt ? "Nota enviada" : "Note sent", false);
  } else {
    // Covers no-speech, recogniser/connectivity errors (e.g. Rebble voice off).
    flash_title(s_pt ? "Sem nota" : "No note", true);
  }
}

static void start_voice_note(void) {
  if (!s_dictation) {
    s_dictation = dictation_session_create(CHAR_LIMIT, dictation_status_cb, NULL);
  }
  if (s_dictation) dictation_session_start(s_dictation);   // starts recording immediately
}
#endif

static void inbox_received_callback(DictionaryIterator *iterator, void *ctx) {
  // Timeline token from PebbleKit JS: forward it straight to Android, nothing to
  // display. JS only runs while the app is open, so this fires on each launch.
  Tuple *t_token = dict_find(iterator, KEY_TL_TOKEN);
  if (t_token && t_token->value->cstring[0]) {
    send_token_to_android(t_token->value->cstring);
    return;
  }

  // Packed timeline pins from Android: relay straight out to PebbleKit JS, which
  // PUTs them to the Rebble timeline API (Core app intercepts -> syncs to watch).
  // Handled before the list fallthrough below because TLPIN uses keys 90/93 which
  // are >= 10 and would otherwise be misparsed as an (empty) task list.
  Tuple *t_act = dict_find(iterator, KEY_ACTION);
  if (t_act && strcmp(t_act->value->cstring, "TLPIN") == 0) {
    Tuple *t_payload = dict_find(iterator, KEY_TASK_TEXT);
    send_pins_to_js(t_payload && t_payload->value->cstring[0]
                      ? t_payload->value->cstring : "");
    return;
  }

  // Config message (from Clay or Android): may carry background colour and/or
  // the voice-row toggle. Handle every config tuple present, then return.
  Tuple *t_color = dict_find(iterator, KEY_BG_COLOR);
  Tuple *t_voice = dict_find(iterator, KEY_VOICE_ON);
  if (t_color || t_voice) {
    if (t_color) {
      uint32_t v = (uint32_t)t_color->value->int32;
      APP_LOG(APP_LOG_LEVEL_INFO, "BG_COLOR received: 0x%06lx", (unsigned long)(v & 0xFFFFFF));
      // Clay always sends a 24-bit 0xRRGGBB value. Snap each channel to the
      // Pebble palette; GColorFromRGB forces opaque alpha so black (0x000000)
      // stays opaque black instead of becoming transparent (which renders white).
      s_bg_color = GColorFromRGB((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
      persist_write_int(PERSIST_KEY_BG_COLOR, s_bg_color.argb);
      apply_colors();
    }
    if (t_voice) {
#if defined(PBL_MICROPHONE)
      s_voice_enabled = (t_voice->value->int32 != 0);
      persist_write_bool(PERSIST_KEY_VOICE_ON, s_voice_enabled);
      if (s_menu_layer) menu_layer_reload_data(s_menu_layer);
#endif
    }
    return;
  }

#if defined(PBL_MICROPHONE)
  // Android failed to save a dictated note: flag the matching placeholder (or the
  // oldest one) as a problem and stash the reason so a tap can show it.
  Tuple *t_action = dict_find(iterator, KEY_ACTION);
  if (t_action && strcmp(t_action->value->cstring, "NOTE_ERR") == 0) {
    Tuple *t_reason = dict_find(iterator, KEY_TASK_TEXT);
    const char *reason = (t_reason && t_reason->value->cstring[0])
                           ? t_reason->value->cstring
                           : (s_pt ? "Falha ao guardar" : "Save failed");
    if (s_pending_count > 0) {
      int idx = s_pending_count - 1;   // most recently dictated note
      s_pending_warn[idx] = true;
      strncpy(s_pending_msg[idx], reason, sizeof(s_pending_msg[0]) - 1);
      s_pending_msg[idx][sizeof(s_pending_msg[0]) - 1] = '\0';
      if (s_menu_layer) menu_layer_reload_data(s_menu_layer);
    }
    return;
  }
#endif

  Tuple *t_first = dict_read_first(iterator);
  if (!t_first || t_first->key < 10) return;

  // Build new list into global buffer (local array would overflow Pebble stack)
  int new_count = 0;
  for (int i = 0; i < MAX_ITEMS; i++) {
    Tuple *t_txt = dict_find(iterator, 10 + (i * 10));
    Tuple *t_tag = dict_find(iterator, 11 + (i * 10));
    Tuple *t_due = dict_find(iterator, 12 + (i * 10));
    if (!t_txt) break;
    strncpy(s_new_items[new_count].text, t_txt->value->cstring, CHAR_LIMIT - 1);
    s_new_items[new_count].text[CHAR_LIMIT - 1] = '\0';
    if (t_tag) strncpy(s_new_items[new_count].tag, t_tag->value->cstring, sizeof(s_new_items[0].tag) - 1);
    else       s_new_items[new_count].tag[0] = '\0';
    s_new_items[new_count].tag[sizeof(s_new_items[0].tag) - 1] = '\0';
    if (t_due) strncpy(s_new_items[new_count].due, t_due->value->cstring, sizeof(s_new_items[0].due) - 1);
    else       s_new_items[new_count].due[0] = '\0';
    s_new_items[new_count].due[sizeof(s_new_items[0].due) - 1] = '\0';
    new_count++;
  }

  // Count new tasks not in old list
  int added = 0;
  for (int i = 0; i < new_count; i++) {
    if (strcmp(s_new_items[i].tag, "HEADER") == 0) continue;
    bool found = false;
    for (int j = 0; j < s_item_count; j++) {
      if (strcmp(s_items[j].text, s_new_items[i].text) == 0) { found = true; break; }
    }
    if (!found) added++;
  }

  // Apply new list
  s_item_count = new_count;
  for (int i = 0; i < new_count; i++) s_items[i] = s_new_items[i];

  prune_done_list();
  filter_done_from_list();
  save_cache();

#if defined(PBL_MICROPHONE)
  reconcile_pending();   // clear "?" placeholders now confirmed in the synced list
#endif

  // change 7: preserve the selection across refreshes — clamp into the new
  // range and step off any header row instead of snapping back to the top.
  // s_selected_row is a *menu row* index (task rows offset by head_rows()).
  int total = head_rows() + s_item_count;
  if (s_selected_row >= total) s_selected_row = total - 1;
  if (s_selected_row < 0) s_selected_row = 0;
  if (s_item_count > 0 && !is_voice_row(s_selected_row)) {
    int si = row_to_task(s_selected_row);
    if (si >= 0 && si < s_item_count && strcmp(s_items[si].tag, "HEADER") == 0) {
      if (s_selected_row + 1 < total)      s_selected_row++;
      else if (s_selected_row - 1 >= 0)    s_selected_row--;
    }
  }


  s_fade_progress = 100; // start details fully visible

  if (window_stack_get_top_window() == s_action_window) window_stack_pop(true);
  menu_layer_reload_data(s_menu_layer);
  if (s_item_count > 0)
    menu_layer_set_selected_index(s_menu_layer, MenuIndex(0, s_selected_row), MenuRowAlignCenter, false);

  // No haptics on sync — a refresh is a background event; new tasks are shown
  // visually (title flash + bounce) without buzzing the wrist.

  // #9: remember when we last received a list (= last sync) and surface it
  // briefly in the title. If there are new tasks, show the count + time and
  // bounce; otherwise just confirm "sync HH:MM".
  s_last_sync = time(NULL);
  persist_write_int(PERSIST_KEY_SYNC_TS, (int)s_last_sync);
  if (s_title_layer) {
    char hm[8];  format_sync_hm(hm, sizeof(hm));
    char msg[32];
    if (added > 0) {
      char nw[16];
      if (s_pt) snprintf(nw, sizeof(nw), "%d nova%s", added, (added == 1 ? "" : "s"));
      else      snprintf(nw, sizeof(nw), "%d new", added);
      snprintf(msg, sizeof(msg), "%s %s", nw, hm);
      flash_title(msg, false);
      title_bounce();
    } else {
      snprintf(msg, sizeof(msg), "sync %s", hm);
      flash_title(msg, false);
    }
  }
}

// ==============================================================
// REMINDERS (delivered as a NATIVE Android notification)
// ==============================================================

// Reminder type codes sent to Android (KEY_DELAY field).
// Android computes the actual delay using its configured hours.
//   0 = 1 hour
//   1 = tonight  (Android setting: evening hour, default 20h)
//   2 = tomorrow morning  (Android setting: morning hour, default 9h)
//   3 = next week (+7 days exactly from now)
//  10+wday = next weekday at morning hour (wday: 0=Sun..6=Sat)
#define REMIND_TYPE_1H       0
#define REMIND_TYPE_TONIGHT  1
#define REMIND_TYPE_MORNING  2
#define REMIND_TYPE_WEEK     3
#define REMIND_TYPE_WDAY    10  // + wday offset (10=Sun, 11=Mon, ... 16=Sat)

// The watch hands the task text + a TYPE CODE to Android, which owns all
// time computation and uses the user's configured hours from the companion app.
static void schedule_reminder(int remind_type, int task_index) {
  if (task_index < 0 || task_index >= s_item_count ||
      strcmp(s_items[task_index].tag, "HEADER") == 0) return;
  send_remind_to_android(s_items[task_index].text, remind_type);
}

// ==============================================================
// ACTION WINDOW
// ==============================================================

// Group titles and the open-task title are drawn in the custom LECO 2014 font,
// which is subset to UPPERCASE + digits + symbols only. So we transform the text
// into ASCII upper-case and strip Portuguese accents (á→A, ç→C, õ→O, …) into a
// scratch buffer first. Accented bytes arrive as 2-byte UTF-8 sequences (0xC3 +
// continuation); we map the common Latin-1 set to its base uppercase letter.
static const char *leco_upper(const char *src) {
#if defined(PBL_PLATFORM_APLITE)
  // aplite renders headers/titles with the Gothic fallback (no custom LECO
  // font) and is critically RAM-tight, so skip the conversion buffer entirely.
  return src;
#else
  static char buf[CHAR_LIMIT];
  size_t o = 0;
  for (size_t i = 0; src[i] != '\0' && o < sizeof(buf) - 1; i++) {
    unsigned char c = (unsigned char)src[i];
    if (c == 0xC3 && src[i + 1] != '\0') {                 // UTF-8 Latin-1 accent
      unsigned char d = (unsigned char)src[++i];
      char base = '\0';
      unsigned char lo = (d >= 0xA0) ? (unsigned char)(d - 0x20) : d;  // fold to UC
      if      (lo >= 0x80 && lo <= 0x85) base = 'A';        // À Á Â Ã Ä Å
      else if (lo == 0x87)               base = 'C';        // Ç
      else if (lo >= 0x88 && lo <= 0x8B) base = 'E';        // È É Ê Ë
      else if (lo >= 0x8C && lo <= 0x8F) base = 'I';        // Ì Í Î Ï
      else if (lo == 0x91)               base = 'N';        // Ñ
      else if (lo >= 0x92 && lo <= 0x96) base = 'O';        // Ò Ó Ô Õ Ö
      else if (lo >= 0x99 && lo <= 0x9C) base = 'U';        // Ù Ú Û Ü
      else if (lo == 0x9D)               base = 'Y';        // Ý
      if (base) buf[o++] = base;                            // else drop the char
      continue;
    }
    if (c >= 'a' && c <= 'z') c = (unsigned char)(c - 'a' + 'A');
    if (c < 0x80) buf[o++] = (char)c;                       // keep ASCII, drop other multibyte
  }
  buf[o] = '\0';
  return buf;
#endif
}

static uint16_t action_menu_get_num_sections(MenuLayer *ml, void *data) { return 1; }
static uint16_t action_menu_get_num_rows(MenuLayer *ml, uint16_t section, void *data) { return 6; }

static int16_t action_menu_get_header_height(MenuLayer *ml, uint16_t section, void *data) {
  if (s_action_task_index < s_item_count) {
    GSize size = graphics_text_layout_get_content_size(
      leco_upper(s_items[s_action_task_index].text),
      leco_font(),
      GRect(0, 0, 132, 1000),
      GTextOverflowModeWordWrap, GTextAlignmentLeft);
    int h = size.h + 14;
    return (int16_t)((h < 36) ? 36 : (h > 80 ? 80 : h));
  }
  return 36;
}

static void action_menu_draw_header(GContext *ctx, const Layer *cell_layer, uint16_t section, void *data) {
  GRect bounds = layer_get_bounds(cell_layer);
  // The task title uses the normal list colours (only the selected option row is
  // highlighted). A bottom divider keeps it visually separated from the options.
  graphics_context_set_fill_color(ctx, s_bg_color);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_text_color(ctx, s_fg_color);
  const char *task_text = leco_upper((s_action_task_index < s_item_count) ? s_items[s_action_task_index].text : "");
#if defined(PBL_ROUND)
  graphics_draw_text(ctx, task_text,
    leco_font(),
    GRect(ROUND_MARGIN, 4, bounds.size.w - 2 * ROUND_MARGIN, bounds.size.h - 6),
    GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
#else
  graphics_draw_text(ctx, task_text,
    leco_font(),
    GRect(6, 4, bounds.size.w - 8, bounds.size.h - 6),
    GTextOverflowModeWordWrap, GTextAlignmentLeft, NULL);
#endif
  graphics_context_set_stroke_color(ctx, s_fg_color);
  graphics_draw_line(ctx, GPoint(0, bounds.size.h - 1), GPoint(bounds.size.w, bounds.size.h - 1));
}

static void action_menu_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *data) {
  // Custom draw so the option rows use Gothic 18 Bold (menu_cell_basic_draw would
  // default to Gothic 24 Bold). Highlight colour is handled manually.
  GRect bounds = layer_get_bounds(cell_layer);
  bool hl = menu_cell_layer_is_highlighted(cell_layer);
  graphics_context_set_text_color(ctx, hl ? s_sel_fg : s_fg_color);
  int th = 22;                                   // ~line height of Gothic 18 Bold
  int y  = (bounds.size.h - th) / 2;             // vertically centred in the row
  graphics_draw_text(ctx, s_action_options[cell_index->row],
    fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
    GRect(6, y, bounds.size.w - 12, th),
    GTextOverflowModeTrailingEllipsis,
    PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
}

static void action_menu_select(MenuLayer *ml, MenuIndex *cell_index, void *data) {
  if (cell_index->row == 0) {
    // Done — copy text before remove_item shifts the array
    char done_text[CHAR_LIMIT];
    strncpy(done_text, s_items[s_action_task_index].text, CHAR_LIMIT - 1);
    done_text[CHAR_LIMIT - 1] = '\0';
    add_to_done_list(done_text);
    remove_item(s_action_task_index);
    send_done_to_android(done_text);
    menu_layer_reload_data(s_menu_layer);
    window_stack_remove(s_action_window, false);
    show_feedback(0);   // ✓ check animation (also pulses once)
    return;
  }
  switch (cell_index->row) {
    case 1: schedule_reminder(REMIND_TYPE_1H,      s_action_task_index); break;
    case 2: schedule_reminder(REMIND_TYPE_TONIGHT, s_action_task_index); break;
    case 3: schedule_reminder(REMIND_TYPE_MORNING, s_action_task_index); break;
    case 4: schedule_reminder(REMIND_TYPE_WEEK,    s_action_task_index); break;
    case 5:
      // Escolher dia — abre o submenu; só fecha/feedback depois de escolher o dia.
      window_stack_push(s_day_window, true);
      return;
  }
  window_stack_remove(s_action_window, false);
  show_feedback(1);     // clock animation
}

// --- Weekday submenu ("Pick a day") ---

static uint16_t day_menu_get_num_rows(MenuLayer *ml, uint16_t section, void *data) { return 7; }

static void day_menu_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *data) {
  menu_cell_basic_draw(ctx, cell_layer, s_day_options[cell_index->row], NULL, NULL);
}

static void day_menu_select(MenuLayer *ml, MenuIndex *cell_index, void *data) {
  // Rows 0..6 = Monday..Sunday → wday 1,2,3,4,5,6,0.
  // Type = REMIND_TYPE_WDAY + wday; Android resolves the actual next occurrence.
  static const int row_to_wday[7] = { 1, 2, 3, 4, 5, 6, 0 };
  schedule_reminder(REMIND_TYPE_WDAY + row_to_wday[cell_index->row], s_action_task_index);
  window_stack_remove(s_day_window, false);
  window_stack_remove(s_action_window, false);
  show_feedback(1);     // clock animation
}

static void day_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_day_menu_layer = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_day_menu_layer, NULL, (MenuLayerCallbacks){
    .get_num_sections = action_menu_get_num_sections,
    .get_num_rows     = day_menu_get_num_rows,
    .draw_row         = day_menu_draw_row,
    .select_click     = day_menu_select,
  });
  menu_layer_set_normal_colors(s_day_menu_layer, s_bg_color, s_fg_color);
  menu_layer_set_highlight_colors(s_day_menu_layer, s_sel_bg, s_sel_fg);
  menu_layer_set_click_config_onto_window(s_day_menu_layer, window);
  layer_add_child(root, menu_layer_get_layer(s_day_menu_layer));
}

static void day_window_unload(Window *window) {
  menu_layer_destroy(s_day_menu_layer);
}

static void action_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_action_menu_layer = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_action_menu_layer, NULL, (MenuLayerCallbacks){
    .get_num_sections  = action_menu_get_num_sections,
    .get_num_rows      = action_menu_get_num_rows,
    .get_header_height = action_menu_get_header_height,
    .draw_header       = action_menu_draw_header,
    .draw_row          = action_menu_draw_row,
    .select_click      = action_menu_select,
  });
  menu_layer_set_normal_colors(s_action_menu_layer, s_bg_color, s_fg_color);
  menu_layer_set_highlight_colors(s_action_menu_layer, s_sel_bg, s_sel_fg);
  menu_layer_set_click_config_onto_window(s_action_menu_layer, window);
  layer_add_child(root, menu_layer_get_layer(s_action_menu_layer));
}

static void action_window_unload(Window *window) {
  menu_layer_destroy(s_action_menu_layer);
}

// ==============================================================
// MAIN WINDOW
// ==============================================================


// Draw a small monochrome urgency glyph in `box` (16x16) using `color`.
// code: 'W' warning(overdue) · 'A' alarm(<=7d) · 'C' calendar(>7d) · 'N' note(no date)
static void draw_urgency_icon(GContext *ctx, char code, GRect box, GColor color) {
  int x = box.origin.x, y = box.origin.y;
  graphics_context_set_stroke_color(ctx, color);
  graphics_context_set_fill_color(ctx, color);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_context_set_antialiased(ctx, true);

  switch (code) {
    case 'W': {  // warning triangle + exclamation
      graphics_draw_line(ctx, GPoint(x+8, y+1),  GPoint(x+1, y+15));
      graphics_draw_line(ctx, GPoint(x+1, y+15), GPoint(x+15, y+15));
      graphics_draw_line(ctx, GPoint(x+15, y+15),GPoint(x+8, y+1));
      graphics_draw_line(ctx, GPoint(x+8, y+6),  GPoint(x+8, y+10));
      graphics_fill_rect(ctx, GRect(x+7, y+12, 2, 2), 0, GCornerNone);
      break;
    }
    case 'A': {  // alarm clock
      GPoint c = GPoint(x+8, y+9);
      graphics_draw_circle(ctx, c, 6);
      graphics_draw_line(ctx, GPoint(x+1, y+2),  GPoint(x+4, y+4));   // left bell
      graphics_draw_line(ctx, GPoint(x+15, y+2), GPoint(x+12, y+4));  // right bell
      graphics_draw_line(ctx, c, GPoint(x+8, y+5));                   // hand → 12
      graphics_draw_line(ctx, c, GPoint(x+11, y+9));                  // hand → 3
      break;
    }
    case 'C': {  // calendar
      graphics_draw_rect(ctx, GRect(x+1, y+3, 14, 12));
      graphics_fill_rect(ctx, GRect(x+1, y+3, 14, 3), 0, GCornerNone);
      graphics_draw_line(ctx, GPoint(x+5, y+1),  GPoint(x+5, y+4));
      graphics_draw_line(ctx, GPoint(x+11, y+1), GPoint(x+11, y+4));
      break;
    }
    case 'N': {  // note / document
      graphics_draw_rect(ctx, GRect(x+2, y+1, 11, 14));
      graphics_draw_line(ctx, GPoint(x+4, y+5),  GPoint(x+11, y+5));
      graphics_draw_line(ctx, GPoint(x+4, y+8),  GPoint(x+11, y+8));
      graphics_draw_line(ctx, GPoint(x+4, y+11), GPoint(x+9, y+11));
      break;
    }
    default: break;
  }
}

// Small microphone glyph in `box` (~16x16) using `color`.
static void draw_mic_icon(GContext *ctx, GRect box, GColor color) {
  int x = box.origin.x, y = box.origin.y;
  graphics_context_set_stroke_color(ctx, color);
  graphics_context_set_fill_color(ctx, color);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_context_set_antialiased(ctx, true);
  graphics_fill_rect(ctx, GRect(x+5, y+1, 6, 9), 3, GCornersAll);   // capsule body
  graphics_draw_line(ctx, GPoint(x+3, y+7),  GPoint(x+3, y+9));     // left arm
  graphics_draw_line(ctx, GPoint(x+12, y+7), GPoint(x+12, y+9));    // right arm
  graphics_draw_line(ctx, GPoint(x+3, y+9),  GPoint(x+8, y+13));    // arc → stem
  graphics_draw_line(ctx, GPoint(x+12, y+9), GPoint(x+8, y+13));
  graphics_draw_line(ctx, GPoint(x+8, y+13), GPoint(x+8, y+15));    // stem
  graphics_draw_line(ctx, GPoint(x+5, y+15), GPoint(x+11, y+15));   // base
}

#if defined(PBL_ROUND)
// The top-centre icon is visible only in the steady baseline state — hidden
// while a transient flash message or the Offline banner owns the title bar.
static bool round_icon_active(void) {
  return s_connected && (s_title_timer == NULL);
}

// Draw the SELECTED row's icon in the (never-clipped) top-centre band: the mic
// for the voice row, the urgency glyph for a task, or the note-status ?/! for a
// pending placeholder. Transparent (returns) when a flash/banner is showing.
static void round_icon_update(Layer *layer, GContext *ctx) {
  if (!round_icon_active()) return;
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, s_bg_color);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  int row = s_selected_row;
  GRect box = GRect((b.size.w - 16) / 2, (b.size.h - 16) / 2, 16, 16);

  if (is_voice_row(row)) { draw_mic_icon(ctx, box, s_fg_color); return; }

#if defined(PBL_MICROPHONE)
  if (is_pending_row(row)) {
    int p = pending_index(row);
    graphics_context_set_text_color(ctx, s_fg_color);
    graphics_draw_text(ctx, s_pending_warn[p] ? "!" : "?",
      fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
      GRect(0, -4, b.size.w, b.size.h + 4),
      GTextOverflowModeFill, GTextAlignmentCenter, NULL);
    return;
  }
#endif

  int i = row_to_task(row);
  if (i >= 0 && i < s_item_count && strcmp(s_items[i].tag, "HEADER") != 0) {
    char code = s_items[i].tag[0];
    if (code == 'W' || code == 'A' || code == 'C' || code == 'N')
      draw_urgency_icon(ctx, code, box, s_fg_color);
  }
}
#endif

static int16_t get_cell_height(MenuLayer *ml, MenuIndex *cell_index, void *ctx) {
  int row = cell_index->row;
  if (s_item_count == 0) return 60;          // empty-state cell
  
  if (s_selected_row == row) {
    if (is_voice_row(row) || is_pending_row(row)) return 44;
    int i = row_to_task(row);
    if (i >= 0 && i < s_item_count) {
      if (strcmp(s_items[i].tag, "HEADER") == 0) return 44;
#if defined(PBL_ROUND)
      int text_w = s_menu_w - 2 * ROUND_MARGIN;     // centred, no edge icon
      GTextAlignment align = GTextAlignmentCenter;
#else
      int text_w = s_menu_w - 6 - ICON_ZONE;        // must match the draw width exactly
      GTextAlignment align = GTextAlignmentLeft;
#endif
      GSize size = graphics_text_layout_get_content_size(
        s_items[i].text,
        fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
        GRect(0, 0, text_w, 1000),
        GTextOverflowModeWordWrap, align);
      int h = size.h + 8;                       // snug top/bottom padding
      if (s_items[i].due[0] != '\0') h += 16;   // room for the relative-due line
      return (int16_t)((h < 44) ? 44 : h);
    }
  }
  return 44; // Uniform height of 44px for all unselected cells, enabling smooth native slide animation.
}

static void menu_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *data) {
  int row = cell_index->row;
  GRect bounds = layer_get_bounds(cell_layer);

  // Voice "New note" row (microphone glyph + label, divider below).
  if (is_voice_row(row)) {
    bool sel = (s_selected_row == row);
    graphics_context_set_fill_color(ctx, sel ? s_sel_bg : s_bg_color);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    GColor fg = sel ? s_sel_fg : s_fg_color;
    graphics_context_set_text_color(ctx, fg);
#if defined(PBL_ROUND)
    // Round: just the centred label — the mic shows in the top-centre icon band.
    graphics_draw_text(ctx, STR_NEWNOTE,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(ROUND_MARGIN, 0, bounds.size.w - 2 * ROUND_MARGIN, bounds.size.h),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
#else
    draw_mic_icon(ctx, GRect(6, (bounds.size.h - 16) / 2, 16, 16), fg);
    graphics_draw_text(ctx, STR_NEWNOTE,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(28, 0, bounds.size.w - 32, bounds.size.h),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
#endif
    graphics_context_set_stroke_color(ctx, fg);
    graphics_draw_line(ctx, GPoint(0, bounds.size.h - 1), GPoint(bounds.size.w, bounds.size.h - 1));
    return;
  }

#if defined(PBL_MICROPHONE)
  // Pending dictated note: transcribed text + status subtitle, with the sync
  // glyph at the right edge (aligned exactly like the urgency icons on tasks).
  if (is_pending_row(row)) {
    int p = pending_index(row);
    bool sel  = (s_selected_row == row);
    bool warn = s_pending_warn[p];
    GColor pbg = sel ? s_sel_bg : s_bg_color;
    GColor pfg = sel ? s_sel_fg : s_fg_color;
    graphics_context_set_fill_color(ctx, pbg);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, pfg);
    const char *sub = warn ? (s_pt ? "Falha ao sincronizar" : "Sync failed")
                           : (s_pt ? "A sincronizar\u2026"   : "Syncing\u2026");
#if defined(PBL_ROUND)
    // Round: centred text + subtitle; the ?/! status shows in the top icon band.
    int tw = bounds.size.w - 2 * ROUND_MARGIN;
    graphics_draw_text(ctx, s_pending[p],
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(ROUND_MARGIN, 1, tw, 20),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
    graphics_draw_text(ctx, sub,
      fonts_get_system_font(FONT_KEY_GOTHIC_14),
      GRect(ROUND_MARGIN, 20, tw, 18),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
#else
    // Status glyph at the right edge (aligned like the urgency icons): "?" while
    // we wait for the note to come back from Obsidian, "!" if it's overdue (>1h).
    graphics_draw_text(ctx, warn ? "!" : "?",
      fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
      GRect(bounds.size.w - ICON_ZONE, 4, ICON_ZONE, 28),
      GTextOverflowModeFill, GTextAlignmentCenter, NULL);
    int tw = bounds.size.w - 6 - ICON_ZONE;
    graphics_draw_text(ctx, s_pending[p],
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(6, 1, tw, 20),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    graphics_draw_text(ctx, sub,
      fonts_get_system_font(FONT_KEY_GOTHIC_14),
      GRect(6, 20, tw, 18),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
#endif
    return;
  }
#endif

  int i = row_to_task(row);

  // Empty state: all tasks done / nothing to show
  if (s_item_count == 0) {
    graphics_context_set_text_color(ctx, s_fg_color);
    graphics_draw_text(ctx, STR_EMPTY,
      fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
      bounds, GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
    return;
  }

  if (i < 0 || i >= s_item_count) return;

  bool is_header   = (strcmp(s_items[i].tag, "HEADER") == 0);
  bool is_selected = (s_selected_row == row);

  // Header: same background as rows, distinguished by a top divider + bold text.
  if (is_header) {
    graphics_context_set_fill_color(ctx, s_bg_color);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_stroke_color(ctx, s_fg_color);
    graphics_draw_line(ctx, GPoint(0, 0), GPoint(bounds.size.w, 0));
    graphics_context_set_text_color(ctx, s_fg_color);
#if defined(PBL_ROUND)
    graphics_draw_text(ctx, leco_upper(s_items[i].text),
      leco_font(),
      GRect(ROUND_MARGIN, 3, bounds.size.w - 2 * ROUND_MARGIN, bounds.size.h),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
#else
    graphics_draw_text(ctx, leco_upper(s_items[i].text),
      leco_font(),
      GRect(6, 3, bounds.size.w - 8, bounds.size.h),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
#endif
    return;
  }

  GColor row_bg = is_selected ? s_sel_bg : s_bg_color;
  GColor row_fg = is_selected ? s_sel_fg : s_fg_color;
  graphics_context_set_fill_color(ctx, row_bg);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_text_color(ctx, row_fg);

#if defined(PBL_ROUND)
  // Round: centred title (no edge icon — the urgency glyph rides the top band);
  // the selected row adds a centred relative-due line beneath.
  int text_w = bounds.size.w - 2 * ROUND_MARGIN;
  if (is_selected) {
    GSize ts = graphics_text_layout_get_content_size(
      s_items[i].text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(0, 0, text_w, 1000), GTextOverflowModeWordWrap, GTextAlignmentCenter);
    graphics_draw_text(ctx, s_items[i].text,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(ROUND_MARGIN, 2, text_w, ts.h + 2),
      GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    if (s_items[i].due[0] != '\0') {
      GColor due_color = color_interpolate(row_bg, row_fg, s_fade_progress);
      graphics_context_set_text_color(ctx, due_color);
      graphics_draw_text(ctx, s_items[i].due,
        fonts_get_system_font(FONT_KEY_GOTHIC_14),
        GRect(ROUND_MARGIN, ts.h + 2, text_w, 16),
        GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
    }
  } else {
    graphics_draw_text(ctx, s_items[i].text,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(ROUND_MARGIN, 4, text_w, bounds.size.h - 4),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }
#else
  int text_w = bounds.size.w - 6 - ICON_ZONE;

  // Urgency icon at the right edge (aligned to the first line).
  char code = s_items[i].tag[0];
  if (code == 'W' || code == 'A' || code == 'C' || code == 'N') {
    int icon_y = is_selected ? 8 : (bounds.size.h - 16) / 2;
    draw_urgency_icon(ctx, code, GRect(bounds.size.w - ICON_ZONE + 3, icon_y, 16, 16), row_fg);
  }

  if (is_selected) {
    // Title (wrapped) + small relative-due line beneath it.
    GSize ts = graphics_text_layout_get_content_size(
      s_items[i].text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(0, 0, text_w, 1000), GTextOverflowModeWordWrap, GTextAlignmentLeft);
    graphics_draw_text(ctx, s_items[i].text,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(6, 2, text_w, ts.h + 2),
      GTextOverflowModeWordWrap, GTextAlignmentLeft, NULL);
    if (s_items[i].due[0] != '\0') {
      GColor due_color = color_interpolate(row_bg, row_fg, s_fade_progress);
      graphics_context_set_text_color(ctx, due_color);
      graphics_draw_text(ctx, s_items[i].due,
        fonts_get_system_font(FONT_KEY_GOTHIC_14),
        GRect(6, ts.h + 2, text_w, 16),
        GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    }
  } else {
    graphics_draw_text(ctx, s_items[i].text,
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(6, 4, text_w, bounds.size.h - 4),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  }
#endif
}

// Reload to recompute variable row heights (the selected row grows to show its
// full wrapped text). reload_data + set_selected_index BOTH fire
// selection_changed synchronously, so we guard with s_in_reload to stop those
// nested callbacks from scheduling yet another reload — otherwise the app spins
// in an endless reload→selection_changed→reload loop (thousands of 1 ms timers
// per second), pegging the CPU and starving AppMessage (DONE never gets sent).
static void fade_timer_callback(void *data) {
  s_fade_progress += 10;
  if (s_fade_progress > 100) {
    s_fade_progress = 100;
    s_fade_timer = NULL;
  } else {
    s_fade_timer = app_timer_register(20, fade_timer_callback, NULL);
  }
  if (s_menu_layer) {
    layer_mark_dirty(menu_layer_get_layer(s_menu_layer));
  }
}

static void deferred_reload(void *data) {
  s_reload_timer = NULL;

  // Reset and start fade-in animation for task details
  s_fade_progress = 0;
  if (s_fade_timer) {
    app_timer_cancel(s_fade_timer);
  }
  s_fade_timer = app_timer_register(20, fade_timer_callback, NULL);

  // Instantly expand the selected row's height
  s_in_reload = true;
  menu_layer_reload_data(s_menu_layer);
  menu_layer_set_selected_index(s_menu_layer, MenuIndex(0, s_selected_row), MenuRowAlignCenter, false);
  s_in_reload = false;
}

static void menu_selection_changed(MenuLayer *ml, MenuIndex new_idx, MenuIndex old_idx, void *ctx) {
  if (s_in_reload) return;  // ignore callbacks triggered by our own reload
  s_selected_row = new_idx.row;
  round_icon_refresh();     // top-centre icon tracks the selected row (round only)

  if (s_fade_timer) { app_timer_cancel(s_fade_timer); s_fade_timer = NULL; }
  s_fade_progress = 0;

  if (is_voice_row(new_idx.row) || is_pending_row(new_idx.row)) {  // head rows: nothing expands
    if (s_reload_timer) { app_timer_cancel(s_reload_timer); s_reload_timer = NULL; }
    s_reload_timer = app_timer_register(10, deferred_reload, NULL);
    return;
  }
  if (s_item_count == 0) return;
  int total = head_rows() + s_item_count;
  int i = row_to_task(s_selected_row);
  if (i >= 0 && i < s_item_count && strcmp(s_items[i].tag, "HEADER") == 0) {
    int dir = (new_idx.row > old_idx.row) ? 1 : -1;
    // Only force downward when this header is the very top menu row (nothing
    // above to land on). When a voice row sits above it (row 0), moving up must
    // be allowed to reach it, so don't override the natural -1 direction.
    if (new_idx.row == 0) dir = 1;
    s_selected_row += dir;
    if (s_selected_row < 0)            s_selected_row = 0;   // voice row is selectable
    if (s_selected_row >= total)       s_selected_row = total - 1;
    menu_layer_set_selected_index(ml, MenuIndex(0, s_selected_row), MenuRowAlignCenter, true);
    return;
  }



  if (s_reload_timer) { app_timer_cancel(s_reload_timer); s_reload_timer = NULL; }
  s_reload_timer = app_timer_register(150, deferred_reload, NULL);
}

static void menu_select(MenuLayer *ml, MenuIndex *cell_index, void *data) {
  int row = cell_index->row;
#if defined(PBL_MICROPHONE)
  if (is_voice_row(row)) { start_voice_note(); return; }
  // Tapping a placeholder explains its state: a reported reason, the >1h slow-sync
  // warning, no phone link, or that it's simply still syncing.
  if (is_pending_row(row)) {
    int p = pending_index(row);
    if (s_pending_msg[p][0])
      flash_title(s_pending_msg[p], true);
    else if (!s_connected)
      flash_title(s_pt ? "Sem ligacao ao telemovel" : "No phone link", true);
    else if (s_pending_warn[p])
      flash_title(s_pt ? "Sincronizacao lenta" : "Slow sync", true);
    else
      flash_title(s_pt ? "A sincronizar\u2026" : "Syncing\u2026", false);
    return;
  }
#endif
  int i = row_to_task(row);
  if (s_item_count == 0 || i < 0 || i >= s_item_count) return;
  if (strcmp(s_items[i].tag, "HEADER") == 0) return;
  s_action_task_index = i;
  window_stack_push(s_action_window, true);
}

static uint16_t menu_get_num_rows(MenuLayer *ml, uint16_t section, void *data) {
  // When there are no tasks we still show one empty-state cell — unless pending
  // placeholders are present, in which case those rows stand in for it.
  int tasks = (s_item_count == 0) ? (prows() > 0 ? 0 : 1) : s_item_count;
  return head_rows() + tasks;
}

static void main_window_load(Window *window) {
  Layer *root   = window_get_root_layer(window);
  GRect  bounds = layer_get_bounds(root);

  s_title_layer = text_layer_create(GRect(0, 0, bounds.size.w, 26));
  text_layer_set_font(s_title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_title_layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_title_layer));

#if defined(PBL_ROUND)
  // Icon band on top of the title text layer: shows the selected-row icon in the
  // baseline state, stays transparent so flash messages read through.
  s_round_icon_layer = layer_create(GRect(0, 0, bounds.size.w, 26));
  layer_set_update_proc(s_round_icon_layer, round_icon_update);
  layer_add_child(root, s_round_icon_layer);
#endif

  s_menu_w = bounds.size.w;   // row layout needs the real width (varies by platform)
  s_menu_layer = menu_layer_create(GRect(0, 26, bounds.size.w, bounds.size.h - 26));
  menu_layer_set_callbacks(s_menu_layer, NULL, (MenuLayerCallbacks){
    .get_num_rows      = menu_get_num_rows,
    .get_cell_height   = get_cell_height,
    .draw_row          = menu_draw_row,
    .select_click      = menu_select,
    .selection_changed = menu_selection_changed,
  });
  menu_layer_set_click_config_onto_window(s_menu_layer, window);
  layer_add_child(root, menu_layer_get_layer(s_menu_layer));

  apply_colors();          // paint title bar + menu with the chosen background
  apply_baseline_title();  // show the clock (or Offline banner)

  load_cache();

  // #9: restore the last-sync time and show how fresh the cached list is. A
  // live FETCH fires right after init, so when online this is replaced within
  // seconds; when offline it tells the user when the list was last updated.
  if (persist_exists(PERSIST_KEY_SYNC_TS)) {
    s_last_sync = (time_t)persist_read_int(PERSIST_KEY_SYNC_TS);
    if (s_last_sync != 0 && s_title_layer) {
      char hm[8];  format_sync_hm(hm, sizeof(hm));
      char msg[24];
      snprintf(msg, sizeof(msg), s_pt ? "lista %s" : "list %s", hm);
      flash_title(msg, false);
    }
  }

  // change 7: restore the row that was selected last time (preserve state).
  // Stored as a menu row index (task rows offset by vrows()).
  if (persist_exists(PERSIST_KEY_SELECTED) && s_item_count > 0) {
    int sel = persist_read_int(PERSIST_KEY_SELECTED);
    int total = vrows() + s_item_count;
    if (sel < vrows()) sel = vrows();
    if (sel >= total)  sel = total - 1;
    if (!is_voice_row(sel)) {
      int si = row_to_task(sel);
      if (si >= 0 && si < s_item_count && strcmp(s_items[si].tag, "HEADER") == 0) {
        if (sel + 1 < total)      sel++;
        else if (sel - 1 >= 0)    sel--;
      }
    }
    s_selected_row = sel;

    menu_layer_set_selected_index(s_menu_layer, MenuIndex(0, s_selected_row), MenuRowAlignCenter, false);
  }
  s_fade_progress = 100; // start details fully visible
}

static void main_window_unload(Window *window) {
  menu_layer_destroy(s_menu_layer);
#if defined(PBL_ROUND)
  if (s_round_icon_layer) { layer_destroy(s_round_icon_layer); s_round_icon_layer = NULL; }
#endif
  text_layer_destroy(s_title_layer);
}

// ==============================================================
// INIT / DEINIT
// ==============================================================

static int  s_timeline_action_code = 0;
static bool s_have_timeline_action = false;

static void fetch_on_init(void *data) {
  // If launched from a pin's "Complete" action, forward the task code first so
  // Android marks it done; the FETCH that follows pulls the updated list back.
  if (s_have_timeline_action) {
    s_have_timeline_action = false;
    send_pin_action_to_android(s_timeline_action_code);
  }
  send_to_android("FETCH", 0, 0);
}

// change 3: surface BT/phone connectivity. A yellow "Offline" banner warns the
// user the list may be stale; on reconnect we silently pull fresh data.
static void connection_handler(bool connected) {
  bool was_connected = s_connected;
  s_connected = connected;
  if (s_title_timer) { app_timer_cancel(s_title_timer); s_title_timer = NULL; }
  apply_baseline_title();
  if (connected && !was_connected) send_to_android("FETCH", 0, 0);
}

static void init() {
  setup_strings();
  // LECO is the system font (see leco_font()); nothing to load here.
  if (launch_reason() == APP_LAUNCH_TIMELINE_ACTION) {
    s_timeline_action_code = (int)launch_get_args();
    s_have_timeline_action = true;
  }
  load_done_list();
  load_bg_color();
#if defined(PBL_MICROPHONE)
  if (persist_exists(PERSIST_KEY_VOICE_ON))
    s_voice_enabled = persist_read_bool(PERSIST_KEY_VOICE_ON);
#endif
  apply_colors();   // seed s_fg/s_sel before any window loads

  s_main_window = window_create();
  window_set_window_handlers(s_main_window, (WindowHandlers){
    .load = main_window_load, .unload = main_window_unload
  });

  s_fb_window = window_create();
  window_set_window_handlers(s_fb_window, (WindowHandlers){
    .load = fb_window_load, .unload = fb_window_unload
  });

  s_action_window = window_create();
  window_set_window_handlers(s_action_window, (WindowHandlers){
    .load = action_window_load, .unload = action_window_unload
  });

  s_day_window = window_create();
  window_set_window_handlers(s_day_window, (WindowHandlers){
    .load = day_window_load, .unload = day_window_unload
  });

  window_stack_push(s_main_window, true);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_outbox_sent(outbox_sent_cb);
  app_message_register_outbox_failed(outbox_failed_cb);
  app_message_open(4096, 2048);

  // Ask Android for fresh data — deferred so AppMessage stack is fully ready
  app_timer_register(500, fetch_on_init, NULL);

  // change 3: track connection state and reflect it in the title bar.
  s_connected = connection_service_peek_pebble_app_connection();
  connection_service_subscribe((ConnectionHandlers){
    .pebble_app_connection_handler = connection_handler,
  });
  if (!s_connected) apply_baseline_title();

  // change 1 (this round): keep the title-bar clock ticking.
  tick_timer_service_subscribe(MINUTE_UNIT, tick_handler);
}

static void deinit() {
  persist_write_int(PERSIST_KEY_SELECTED, s_selected_row);  // change 7
  if (s_fade_timer) { app_timer_cancel(s_fade_timer); s_fade_timer = NULL; }
#if defined(PBL_MICROPHONE)
  if (s_dictation) { dictation_session_destroy(s_dictation); s_dictation = NULL; }
  if (s_pending_timer) { app_timer_cancel(s_pending_timer); s_pending_timer = NULL; }
#endif
  tick_timer_service_unsubscribe();
  connection_service_unsubscribe();
  window_destroy(s_main_window);
  window_destroy(s_action_window);
  window_destroy(s_day_window);
  window_destroy(s_fb_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
