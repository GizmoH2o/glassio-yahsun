package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live TV's own settings, drawn over the video like the channel list (LiveView hands it the screen): the stream
 * format, the buffer, the categories hidden, the favorites' order, the parental PIN and the categories it locks, and
 * signing out. Each list has one white focus gliding between rows (the text under it turns dark), on springs.
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
final class LiveSettings {
    // MAIN: the sections; ROWS: a section's settings; KEYS: the remote's buttons; the rest, lists a setting opens.
    private static final int MAIN = 0, HIDDEN = 1, ORDER = 2, LOCKED = 3, SCHEDULE = 4, SEPARATE = 5, ROWS = 6, KEYS = 7,
            SERIES = 8, PLAYLISTS = 9, FOLLOWING = 10;
    // The first list: playback, the lists' order, the guide, opening by itself (all of these step with ← → and OK),
    // then the lists that open (hidden, favorites), the PIN, signing out.
    private static final int FORMAT_ROW = 0, BUFFER_ROW = 1, FRAME_RATE_ROW = 2, PASSTHROUGH_ROW = 3, TUNNELING_ROW = 4,
            MERGE_ROW = 5, CHANNEL_SORT_ROW = 6, CATEGORY_SORT_ROW = 7, GUIDE_HOURS_ROW = 8, PAST_DAYS_ROW = 9,
            GUIDE_START_ROW = 10, FULL_GUIDE_ROW = 11, DAYS_AHEAD_ROW = 12, GUIDE_SOURCE_ROW = 13, GUIDE_NOW_ROW = 14,
            BOOT_ROW = 15, WAKE_ROW = 16, PREVIEW_ROW = 17, SCHEDULE_ROW = 18, SEPARATE_ROW = 19, HIDDEN_ROW = 20,
            ORDER_ROW = 21, PIN_ROW = 22, LOCKED_ROW = 23, SIGN_OUT_ROW = 24, REMIND_LEAD_ROW = 25, REMIND_AUTO_ROW = 26,
            SERIES_ROW = 27, THEME_ROW = 28, HISTORY_ROW = 29, CLEAR_HISTORY_ROW = 30, EXPORT_ROW = 31, IMPORT_ROW = 32,
            PLAYLISTS_ROW = 33, ADD_PLAYLIST_ROW = 34, OPACITY_ROW = 35, CC_ON_ROW = 36, CC_SIZE_ROW = 37,
            CC_STYLE_ROW = 38, CC_COLOR_ROW = 39, AUDIO_LANG_ROW = 40, HOME_ROW = 41, PROFILES_ROW = 42, ASK_PROFILE_ROW = 43,
            FOLLOW_ROW = 44, AGE_ROW = 45, SUBSCRIPTION_ROW = 46, HEALTH_ROW = 47, STYLE_ROW = 48, NIGHT_ROW = 49,
            CUSTOM_BG_ROW = 50, CUSTOM_ROUND_ROW = 51, CUSTOM_LAYOUT_ROW = 52, MORNING_ROW = 53, LATE_ROW = 54, CODE_ROW = 55,
            RESET_ROW = 56, MATCH_ROW = 57, MATCH_STYLE_ROW = 58, REMIND_WATCHING_ROW = 59, GUIDE_SHIFT_ROW = 60, GUIDE_SIZE_ROW = 61, SCORES_ROW = 62, GOALS_ROW = 63, RECORD_LIST_ROW = 64, RECORDINGS_ROW = 65,
            SERIES_RECORD_ROW = 66, RECORD_EARLY_ROW = 67, RECORD_LATE_ROW = 68, COUNTRY_ROW = 69,
            VOICE_ROW = 70, MAIN_ROWS = 71;
    // The sections in a column at the left (each with its symbol); the page at the right: on the first list, the
    // focused section's details and its settings, in a section its rows (the launcher settings' layout).
    private static final float X = 780, R = 1824, TOP = 236, ROW_H = 66, STEP = 74, PANEL_W = 1920;
    private static final float RAIL_X = 72, RAIL_R = 660, RAIL_TOP = 236, RAIL_H = 70, RAIL_STEP = 78;
    private static final String[] SECTION_ICONS = {"play", "list", "guide", "sparkles", "gamepad", "bell", "recordings", "lock", "account",
            "captions"};
    private static final int REMINDERS_SECTION = 5, RECORDINGS_SECTION = 6, CAPTIONS_SECTION = 9;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final String[] FORMATS = {LiveOptions.AUTO, LiveOptions.TS, LiveOptions.HLS};
    /** Each section's settings (the remote's buttons have their own list). */
    private static final int KEYS_SECTION = 4;
    /** Theme and start-up without Custom's own rows (the style isn't Custom). */
    private static final int[] THEME_PLAIN = {STYLE_ROW, MORNING_ROW, NIGHT_ROW, LATE_ROW, THEME_ROW, CODE_ROW, OPACITY_ROW,
            BOOT_ROW, WAKE_ROW, PREVIEW_ROW, RESET_ROW};

    /** A section's rows (Custom's background, corners and layout only while Custom is the style). */
    /** An M3U or MAG playlist in use: no subscription to show (set as the settings open). */
    private static boolean noSubscription;
    private static int[] withoutSubscription;

    private static int[] rowsOf(int s) {
        int[] rows = SECTION_ROWS[s];
        if (noSubscription && rows.length > 0 && rows[0] == SUBSCRIPTION_ROW) {
            if (withoutSubscription == null) withoutSubscription = java.util.Arrays.copyOfRange(rows, 1, rows.length);
            return withoutSubscription;
        }
        if (LiveTheme.style() == LiveTheme.CUSTOM) return rows;
        for (int id : rows) if (id == CUSTOM_BG_ROW) return THEME_PLAIN;
        return rows;
    }

    private static boolean ownColor() {
        return LiveTheme.style() == LiveTheme.ZERO || LiveTheme.style() == LiveTheme.CUSTOM;
    }

    private static final int[][] SECTION_ROWS = {
            {FORMAT_ROW, BUFFER_ROW, FRAME_RATE_ROW, PASSTHROUGH_ROW, AUDIO_LANG_ROW, TUNNELING_ROW, MERGE_ROW, SEPARATE_ROW, HEALTH_ROW,
                    RESET_ROW},
            {CHANNEL_SORT_ROW, CATEGORY_SORT_ROW, COUNTRY_ROW, VOICE_ROW, HISTORY_ROW, CLEAR_HISTORY_ROW, HIDDEN_ROW, ORDER_ROW,
                    RESET_ROW},
            {GUIDE_SIZE_ROW, GUIDE_HOURS_ROW, PAST_DAYS_ROW, GUIDE_START_ROW, FULL_GUIDE_ROW, DAYS_AHEAD_ROW, GUIDE_SOURCE_ROW, GUIDE_SHIFT_ROW, GUIDE_NOW_ROW,
                    RESET_ROW},
            {STYLE_ROW, MORNING_ROW, NIGHT_ROW, LATE_ROW, THEME_ROW, CUSTOM_BG_ROW, CUSTOM_ROUND_ROW, CUSTOM_LAYOUT_ROW, CODE_ROW,
                    OPACITY_ROW, BOOT_ROW, WAKE_ROW, PREVIEW_ROW, RESET_ROW},
            {},
            {REMIND_LEAD_ROW, REMIND_AUTO_ROW, REMIND_WATCHING_ROW, SCHEDULE_ROW, SERIES_ROW, FOLLOW_ROW, MATCH_ROW, MATCH_STYLE_ROW, SCORES_ROW, GOALS_ROW,
                    RESET_ROW},
            {RECORD_EARLY_ROW, RECORD_LATE_ROW, RECORD_LIST_ROW, SERIES_RECORD_ROW, RECORDINGS_ROW, RESET_ROW},
            {PIN_ROW, LOCKED_ROW, AGE_ROW, RESET_ROW},
            {SUBSCRIPTION_ROW, PROFILES_ROW, ASK_PROFILE_ROW, EXPORT_ROW, IMPORT_ROW, PLAYLISTS_ROW, ADD_PLAYLIST_ROW, SIGN_OUT_ROW, RESET_ROW},
            {CC_ON_ROW, CC_SIZE_ROW, CC_STYLE_ROW, CC_COLOR_ROW, RESET_ROW}};
    /** The section open (ROWS). */
    private int section;

    private final LiveView view;
    private final Live live;
    private int screen = MAIN, focus, top;
    /** In the favorites' order: the focused one is picked up (↑ ↓ move it). */
    private boolean carrying;
    private final Spring row = new Spring(0.22f, 0.86f, 0), scroll = new Spring(0.3f, 1f, 0);

    private float k;
    private android.text.TextPaint styleHint;
    private android.text.StaticLayout styleHintLayout;
    private String styleHintText;
    private android.text.TextPaint plainHint;
    private android.text.StaticLayout plainHintLayout;
    private String plainHintText;
    private Paint heading, label, labelOn, value, valueOn, hint, fill, glow, railLabel, detailTitle, previewLabel, previewValue;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        stroke.setStyle(Paint.Style.STROKE);
    }
    private TextPaint detailText;
    private android.text.StaticLayout aboutLayout;
    /** The background's opacity in percent (Settings background), read when the settings open. */
    private int opacity = 50;
    private String aboutShown;
    private final TextPaint fit = new TextPaint();
    private final RectF r = new RectF(), focusRect = new RectF();

    LiveSettings(LiveView view, Live live) {
        this.view = view;
        this.live = live;
    }

    void size(float scale) {
        k = scale;
        styleHint = null;       // the style's lettering may have changed
        styleHintLayout = null;
        plainHint = null;
        plainHintLayout = null;
        heading = Ui.text(Ui.displaySemibold, 46 * k, WHITE);
        label = Ui.text(Ui.medium, 28 * k, 0xF2FFFFFF);
        labelOn = Ui.text(Ui.semibold, 28 * k, WHITE);
        value = Ui.text(Ui.medium, 26 * k, 0xBFFFFFFF);
        value.setTextAlign(Paint.Align.RIGHT);
        valueOn = Ui.text(Ui.semibold, 26 * k, 0xEBFFFFFF);
        valueOn.setTextAlign(Paint.Align.RIGHT);
        hint = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(WHITE);
        glow.setShadowLayer(26 * k, 0, 10 * k, 0x80000000);
        railLabel = Ui.text(Ui.medium, 30 * k, WHITE);
        detailTitle = Ui.text(Ui.displaySemibold, 54 * k, WHITE);
        detailText = new TextPaint(Ui.text(Ui.regular, 29 * k, 0xCCFFFFFF));
        previewLabel = Ui.text(Ui.regular, 27 * k, 0xE6FFFFFF);
        previewValue = Ui.text(Ui.regular, 27 * k, 0xB3FFFFFF);
        previewValue.setTextAlign(Paint.Align.RIGHT);
        aboutLayout = null;
    }

    /** Opening: where it was left last time (its section, and inside one the row), else the first list's first row. */
    void open() {
        screen = MAIN;
        focus = top = 0;
        carrying = false;
        onSearch = false;
        row.snap(0);
        scroll.snap(0);
        opacity = LiveOptions.panelOpacity(context());
        noSubscription = !Xtream.kind(context()).equals(Xtream.XTREAM);
        android.content.SharedPreferences p = context().getSharedPreferences("live-ui", Context.MODE_PRIVATE);
        int sec = p.getInt("settingsSection", 0), at = p.getInt("settingsRow", -1);
        if (at >= 0 && at != RESET_ROW) {       // (Reset is in every section: its section alone says where)
            showRow(at);
        } else {
            focus = Math.max(0, Math.min(SECTION_ROWS.length - 1, sec));
            if (at == RESET_ROW && sec >= 0 && sec < SECTION_ROWS.length && rowsOf(sec).length > 0) {
                section = sec;
                show(ROWS, rowsOf(sec).length - 1);
            } else {
                row.snap(focus);
            }
        }
        announce();
    }

    /** Closing: where it is, for next time. */
    void closed() {
        int sec = screen == MAIN ? focus : marked();
        context().getSharedPreferences("live-ui", Context.MODE_PRIVATE).edit()
                .putInt("settingsSection", sec).putInt("settingsRow", screen == ROWS ? id() : -1).apply();
    }

    boolean step(float dt) {
        return row.step(dt) | scroll.step(dt);
    }

    private Context context() {
        return view.getContext();
    }

    // MARK: - The lists

    private int count() {
        switch (screen) {
            case HIDDEN:
            case LOCKED: return view.providerCategories().size();
            case ORDER: return favorites().size();
            case SCHEDULE: return scheduled.size();
            case SERIES: return series.size();
            case FOLLOWING: return following.size();
            case PLAYLISTS: return playlists.size();
            case SEPARATE: return separate.size();
            case ROWS: return rowsOf(section).length;
            case KEYS: return LiveKeys.SLOTS.length;
            default: return SECTION_ROWS.length;
        }
    }

    /** Reminders and recordings set for later (read when their list opens). */
    private List<LiveSchedule.Item> scheduled = new ArrayList<>();
    /** The schedule and series lists open on the recordings (else the reminders). */
    private boolean listRecordings;

    /** What's set for later: the recordings, or everything else (reminders and their switches). */
    private static List<LiveSchedule.Item> scheduled(Context c, boolean recordings) {
        List<LiveSchedule.Item> out = new ArrayList<>();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) if (it.kind.equals(LiveSchedule.RECORD) == recordings) out.add(it);
        return out;
    }

    /** The series rules: the recording ones, or the reminder ones. */
    private static List<LiveSeries.Rule> series(Context c, boolean recordings) {
        List<LiveSeries.Rule> out = new ArrayList<>();
        for (LiveSeries.Rule r : LiveSeries.all(c)) if (r.kind.equals(LiveSchedule.RECORD) == recordings) out.add(r);
        return out;
    }
    /** The series (read when their list opens). */
    private List<LiveSeries.Rule> series = new ArrayList<>();
    private List<String> following = new ArrayList<>();
    /** The playlists (read when their list opens). */
    private List<LivePlaylists.Playlist> playlists = new ArrayList<>();
    /** The channels kept apart from their similar ones, {group, name} (read when their list opens). */
    private List<String[]> separate = new ArrayList<>();

    private List<Xtream.Channel> favorites() {
        Xtream.Lineup l = view.lineup();
        return l == null || l.categories.isEmpty() ? new ArrayList<>() : l.categories.get(0).channels;
    }

    private String title() {
        switch (screen) {
            case HIDDEN: return Ui.tr("Categorias ocultas", "Hidden categories");
            case ORDER: return Ui.tr("Ordem dos favoritos", "Favorites order");
            case LOCKED: return Ui.tr("Categorias bloqueadas", "Locked categories");
            case SCHEDULE: return listRecordings ? Ui.tr("Gravações marcadas", "Scheduled recordings") : Ui.tr("Lembretes marcados", "Reminders set");
            case SERIES: return listRecordings ? Ui.tr("Gravações de séries", "Series recordings") : Ui.tr("Lembretes de séries", "Series reminders");
            case FOLLOWING: return Ui.tr("Seguindo", "Following");
            case PLAYLISTS: return Ui.tr("Listas", "Playlists");
            case SEPARATE: return Ui.tr("Mantidos separados", "Kept separate");
            case ROWS: return sectionName(section);
            case KEYS: return sectionName(KEYS_SECTION);
            default: return Ui.tr("Ajustes da TV ao vivo", "Live TV settings");
        }
    }

    private String label(int i) {
        if (screen == HIDDEN || screen == LOCKED) return view.providerCategories().get(i).name;
        if (screen == SCHEDULE) {
            LiveSchedule.Item it = scheduled.get(i);
            return it.title.isEmpty() ? it.ch.name : it.title;
        }
        if (screen == SEPARATE) return separate.get(i)[1];
        if (screen == SERIES) return series.get(i).title;
        if (screen == FOLLOWING) return following.get(i);
        if (screen == PLAYLISTS) return playlists.get(i).name();
        if (screen == ORDER) {
            Xtream.Channel ch = favorites().get(i);
            return (ch.num > 0 ? ch.num + "   " : "") + ch.name;
        }
        if (screen == MAIN) return sectionName(i);
        if (screen == KEYS) return LiveKeys.slotName(LiveKeys.SLOTS[i]);
        return rowLabel(rowsOf(section)[i]);
    }

    private static String sectionName(int s) {
        switch (s) {
            case 0: return Ui.tr("Reprodução", "Playback");
            case 1: return Ui.tr("Canais e categorias", "Channels and categories");
            case 2: return Ui.tr("Guia", "Guide");
            case 3: return Ui.tr("Tema e abertura", "Theme and start-up");
            case KEYS_SECTION: return Ui.tr("Botões do controle", "Remote buttons");
            case REMINDERS_SECTION: return Ui.tr("Lembretes", "Reminders");
            case RECORDINGS_SECTION: return Ui.tr("Gravações", "Recordings");
            case 7: return Ui.tr("Controle dos pais", "Parental controls");
            case CAPTIONS_SECTION: return Ui.tr("Legendas", "Captions");
            default: return Ui.tr("Perfis e conta", "Profiles and account");
        }
    }

    /** The setting row `i` (a section's ROWS id). */
    private String rowLabel(int i) {
        switch (i) {
            case FORMAT_ROW: return Ui.tr("Formato do vídeo", "Stream format");
            case BUFFER_ROW: return Ui.tr("Buffer", "Buffer");
            case FRAME_RATE_ROW: return Ui.tr("Taxa de quadros automática", "Auto frame rate");
            case PASSTHROUGH_ROW: return Ui.tr("Áudio direto (passthrough)", "Audio passthrough");
            case AUDIO_LANG_ROW: return Ui.tr("Idioma do áudio", "Audio language");
            case TUNNELING_ROW: return Ui.tr("Reprodução em túnel", "Tunneled playback");
            case MERGE_ROW: return Ui.tr("Juntar canais parecidos", "Merge similar channels");
            case GUIDE_HOURS_ROW: return Ui.tr("Atualizar o guia a cada", "Update the guide every");
            case PAST_DAYS_ROW: return Ui.tr("Dias passados no guia", "Past days to keep in the guide");
            case GUIDE_START_ROW: return Ui.tr("Atualizar o guia ao abrir", "Update the guide on start");
            case FULL_GUIDE_ROW: return Ui.tr("Guia completo (XMLTV)", "Full guide (XMLTV)");
            case DAYS_AHEAD_ROW: return Ui.tr("Dias à frente no guia", "Days ahead in the guide");
            case GUIDE_SHIFT_ROW: return Ui.tr("Ajustar o horário do guia", "Guide time offset");
            case GUIDE_SIZE_ROW: return Ui.tr("Tamanho do guia", "Guide size");
            case GUIDE_SOURCE_ROW: return Ui.tr("Fonte do guia", "Guide source");
            case GUIDE_NOW_ROW: return Ui.tr("Atualizar o guia completo agora", "Update the full guide now");
            case BOOT_ROW: return Ui.tr("Abrir quando a TV liga", "Open when the TV starts");
            case WAKE_ROW: return Ui.tr("Abrir quando a TV acorda", "Open when the TV wakes up");
            case HOME_ROW: return Ui.tr("Abrir em", "Open on");
            case PREVIEW_ROW: return Ui.tr("Prévias no Início", "Previews on the home screen");
            case CHANNEL_SORT_ROW: return Ui.tr("Ordenar canais", "Sort channels");
            case CATEGORY_SORT_ROW: return Ui.tr("Ordenar categorias", "Sort categories");
            case COUNTRY_ROW: return Ui.tr("Etiquetas de país", "Country tags");
            case VOICE_ROW: return Ui.tr("Canais por voz e na busca da TV", "Channels by voice and TV search");
            case SCHEDULE_ROW: return Ui.tr("Lembretes marcados", "Reminders set");
            case RECORD_LIST_ROW: return Ui.tr("Gravações marcadas", "Scheduled recordings");
            case RECORDINGS_ROW: return Ui.tr("Programas gravados", "Recorded shows");
            case REMIND_LEAD_ROW: return Ui.tr("Lembrar antes", "Remind me before");
            case RECORD_EARLY_ROW: return Ui.tr("Começar a gravar", "Start recordings");
            case RECORD_LATE_ROW: return Ui.tr("Continuar gravando", "Keep recording");
            case SERIES_ROW: return Ui.tr("Lembretes de séries", "Series reminders");
            case SERIES_RECORD_ROW: return Ui.tr("Gravações de séries", "Series recordings");
            case FOLLOW_ROW: return Ui.tr("Seguindo", "Following");
            case MATCH_ROW: return Ui.tr("Alertas de jogos", "Match alerts");
            case MATCH_STYLE_ROW: return Ui.tr("Alerta de jogo mostra", "A match alert shows");
            case REMIND_WATCHING_ROW: return Ui.tr("Lembrar no canal que estou vendo", "Remind me on the channel I'm watching");
            case SCORES_ROW: return Ui.tr("Placar ao vivo", "Live scores");
            case GOALS_ROW: return Ui.tr("Avisos de gol", "Goal alerts");
            case THEME_ROW: return Ui.tr("Cor do foco", "Focus color");
            case STYLE_ROW: return Ui.tr("Estilo", "Style");
            case NIGHT_ROW: return Ui.tr("Estilo à noite", "Style at night");
            case MORNING_ROW: return Ui.tr("Estilo de manhã", "Style in the morning");
            case LATE_ROW: return Ui.tr("Estilo de madrugada", "Style late at night");
            case CODE_ROW: return Ui.tr("Código do estilo", "Style code");
            case RESET_ROW: return Ui.tr("Voltar ao padrão desta seção", "Reset this section");
            case CUSTOM_BG_ROW: return Ui.tr("Fundo", "Background");
            case CUSTOM_ROUND_ROW: return Ui.tr("Cantos", "Corners");
            case CUSTOM_LAYOUT_ROW: return Ui.tr("Layout", "Layout");
            case OPACITY_ROW: return Ui.tr("Fundo dos ajustes", "Settings background");
            case CC_ON_ROW: return Ui.tr("Legendas", "Captions");
            case CC_SIZE_ROW: return Ui.tr("Tamanho", "Size");
            case CC_STYLE_ROW: return Ui.tr("Fundo", "Background");
            case CC_COLOR_ROW: return Ui.tr("Cor do texto", "Text color");
            case PLAYLISTS_ROW: return Ui.tr("Listas", "Playlists");
            case PROFILES_ROW: return Ui.tr("Perfis", "Profiles");
            case SUBSCRIPTION_ROW: return Ui.tr("Assinatura", "Subscription");
            case HEALTH_ROW: return Ui.tr("Saúde dos canais", "Stream health");
            case ASK_PROFILE_ROW: return Ui.tr("Perguntar quem está assistindo", "Ask who's watching");
            case ADD_PLAYLIST_ROW: return Ui.tr("Adicionar uma lista", "Add a playlist");
            case HISTORY_ROW: return Ui.tr("Canais no Histórico", "Channels in History");
            case CLEAR_HISTORY_ROW: return Ui.tr("Limpar o Histórico", "Clear History");
            case EXPORT_ROW: return Ui.tr("Fazer backup de tudo", "Back up everything");
            case IMPORT_ROW: return Ui.tr("Restaurar o backup", "Restore the backup");
            case REMIND_AUTO_ROW: return Ui.tr("Quando o programa começa", "When the show starts");
            case SEPARATE_ROW: return Ui.tr("Mantidos separados", "Kept separate");
            case HIDDEN_ROW: return Ui.tr("Categorias ocultas", "Hidden categories");
            case ORDER_ROW: return Ui.tr("Ordem dos favoritos", "Favorites order");
            case PIN_ROW: return Ui.tr("PIN dos pais", "Parental PIN");
            case LOCKED_ROW: return Ui.tr("Categorias bloqueadas", "Locked categories");
            case AGE_ROW: return Ui.tr("Bloquear programas para maiores de", "Lock shows rated");
            default: return Ui.tr("Sair da conta", "Sign out");
        }
    }

    private String value(int i) {
        Context c = context();
        if (screen == SCHEDULE) {
            LiveSchedule.Item it = scheduled.get(i);
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE d", Ui.locale());
            return (LiveSchedule.reminder(it) ? Ui.tr("Lembrete", "Reminder") : Ui.tr("Gravação", "Recording"))
                    + "  ·  " + day.format(new java.util.Date(it.start)) + ", " + Ui.time(it.start) + "  ·  " + it.ch.name;
        }
        if (screen == SEPARATE) return Ui.tr("Separado", "Separate");
        if (screen == PLAYLISTS) {
            return playlists.get(i).id.equals(LivePlaylists.active(c)) ? Ui.tr("Em uso", "In use") : "";
        }
        if (screen == FOLLOWING) return Ui.tr("Lembretes", "Reminders");
        if (screen == SERIES) {
            LiveSeries.Rule r = series.get(i);
            return (r.kind.equals(LiveSchedule.REMIND) ? Ui.tr("Lembrete", "Reminder") : Ui.tr("Gravação", "Recording"))
                    + "  ·  " + (r.newOnly ? Ui.tr("Episódios novos", "New episodes") : Ui.tr("Todos os episódios", "Every episode"))
                    + "  ·  " + r.ch.name;
        }
        if (screen == HIDDEN) {
            return LiveOptions.hidden(c).contains(view.providerCategories().get(i).id) ? Ui.tr("Oculta", "Hidden") : Ui.tr("Visível", "Shown");
        }
        if (screen == LOCKED) {
            return LiveOptions.locked(c).contains(view.providerCategories().get(i).id) ? Ui.tr("Bloqueada", "Locked") : "—";
        }
        if (screen == ORDER) return carrying && i == focus ? "▲ ▼" : String.valueOf(i + 1);
        if (screen == MAIN) return "›";
        if (screen == KEYS) return LiveKeys.actionName(LiveKeys.action(c, LiveKeys.SLOTS[i]));
        return rowValue(rowsOf(section)[i]);
    }

    // Some values read files (the schedule, what's followed): drawn every frame, they're kept a second, and dropped on
    // any key (a change shows at once).
    private final android.util.SparseArray<String> valueCache = new android.util.SparseArray<>();
    private long valueCacheAt;

    private String rowValue(int id) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - valueCacheAt > 1000) {
            valueCache.clear();
            valueCacheAt = now;
        }
        String v = valueCache.get(id);
        if (v == null) {
            v = computeRowValue(id);
            valueCache.put(id, v);
        }
        return v;
    }

    private String computeRowValue(int i) {
        Context c = context();
        switch (i) {
            case FORMAT_ROW: {
                String f = LiveOptions.format(c);
                return f.equals(LiveOptions.TS) ? "MPEG-TS" : f.equals(LiveOptions.HLS) ? "HLS" : Ui.tr("Automático", "Automatic");
            }
            case BUFFER_ROW: {
                int b = LiveOptions.buffer(c);
                return b == LiveOptions.LARGE ? Ui.tr("Grande", "Large") : b == LiveOptions.EXTRA ? Ui.tr("Extra grande", "Extra large")
                        : Ui.tr("Normal", "Normal");
            }
            case FRAME_RATE_ROW: return onOff(LiveOptions.frameRate(c));
            case PASSTHROUGH_ROW: return onOff(LiveOptions.passthrough(c));
            case AUDIO_LANG_ROW: {
                String l = LiveOptions.audioLanguage(c);
                if (l.isEmpty()) return Ui.tr("Automático", "Automatic");
                String name = new java.util.Locale(l).getDisplayLanguage(Ui.locale());
                return name.isEmpty() ? l : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
            case TUNNELING_ROW: return onOff(LiveOptions.tunneling(c));
            case MERGE_ROW: return onOff(LiveOptions.merge(c));
            case COUNTRY_ROW: return onOff(LiveOptions.countryTags(c));
            case VOICE_ROW: return onOff(LiveOptions.voiceChannels(c));
            case REMIND_LEAD_ROW: {
                int m = LiveOptions.remindLead(c);
                return m == 0 ? Ui.tr("Na hora", "As it starts") : m + " min";
            }
            case RECORD_EARLY_ROW: return numberName(RECORD_EARLY_ROW, LiveOptions.recordEarly(c));
            case RECORD_LATE_ROW: return numberName(RECORD_LATE_ROW, LiveOptions.recordLate(c));
            case REMIND_AUTO_ROW:
                return LiveOptions.remindAuto(c) ? Ui.tr("Mudar de canal sozinho", "Switch automatically") : Ui.tr("Perguntar", "Ask");
            case GUIDE_HOURS_ROW: return LiveOptions.guideHours(c) + " h";
            case PAST_DAYS_ROW: {
                int d = LiveOptions.pastDays(c);
                return d == 0 ? Ui.tr("Nenhum", "None") : d + " " + (d == 1 ? Ui.tr("dia", "day") : Ui.tr("dias", "days"));
            }
            case GUIDE_START_ROW: return onOff(LiveOptions.guideOnStart(c));
            case FULL_GUIDE_ROW: return onOff(LiveOptions.fullGuide(c));
            case DAYS_AHEAD_ROW: {
                int d = LiveOptions.daysAhead(c);
                return d + " " + (d == 1 ? Ui.tr("dia", "day") : Ui.tr("dias", "days"));
            }
            case GUIDE_SHIFT_ROW: return LiveOptions.shiftName(LiveOptions.guideShiftMinutes(c));
            case GUIDE_SIZE_ROW: return LiveOptions.guideCompact(c) ? Ui.tr("Compacto", "Compact") : Ui.tr("Confortável", "Comfortable");
            case GUIDE_SOURCE_ROW:
                return LiveOptions.guideUrl(c).isEmpty() ? Ui.tr("Provedor", "Provider") : Ui.tr("Link próprio", "Custom link");
            case GUIDE_NOW_ROW: {
                if (!LiveOptions.fullGuide(c)) return "—";
                if (XmltvStore.running()) {
                    String p = XmltvStore.progress();
                    return Ui.tr("Atualizando…", "Updating…") + (p.isEmpty() ? "" : " " + p);
                }
                String p = XmltvStore.progress();
                if (!p.isEmpty()) return p;      // the last try failed: why
                long at = XmltvStore.updatedAt(c);
                return at == 0 ? Ui.tr("Ainda não", "Not yet") : Ui.tr("Atualizado ", "Updated ") + Ui.time(at);
            }
            case BOOT_ROW: return onOff(LiveOptions.startOnBoot(c));
            case WAKE_ROW: return onOff(LiveOptions.startOnWake(c));
            case HOME_ROW: return LiveOptions.homeOnStart(c) ? Ui.tr("Início", "Home") : Ui.tr("Último canal", "Last channel");
            case MATCH_ROW: return LiveOptions.followAll(c) ? Ui.tr("Todas as exibições", "All airings") : Ui.tr("Só ao vivo", "Live events only");
            case MATCH_STYLE_ROW: return LiveOptions.matchCard(c) ? Ui.tr("Um aviso", "A card") : Ui.tr("Todos os canais", "Every channel");
            case REMIND_WATCHING_ROW: return onOff(LiveOptions.remindWatching(c));
            case SCORES_ROW: return onOff(LiveOptions.scores(c));
            case GOALS_ROW: return LiveOptions.scores(c) ? onOff(LiveOptions.goalAlerts(c)) : "—";
            case PREVIEW_ROW: return onOff(LiveOptions.homePreviews(c));
            case CHANNEL_SORT_ROW: {
                int s = LiveOptions.channelSort(c);
                return s == LiveOptions.BY_NUMBER ? Ui.tr("Por número", "By number") : s == LiveOptions.BY_NAME
                        ? Ui.tr("Por nome (A–Z)", "By name (A–Z)") : Ui.tr("Ordem do provedor", "Provider's order");
            }
            case CATEGORY_SORT_ROW:
                return LiveOptions.categorySort(c) == LiveOptions.BY_NAME ? Ui.tr("Por nome (A–Z)", "By name (A–Z)")
                        : Ui.tr("Ordem do provedor", "Provider's order");
            case SCHEDULE_ROW:
            case RECORD_LIST_ROW: {
                int n = scheduled(c, i == RECORD_LIST_ROW).size();
                return n == 0 ? Ui.tr("Nenhum", "None") : String.valueOf(n);
            }
            case RECORDINGS_ROW: {
                int n = LiveRecordings.all(c).size();
                if (n == 0) return Ui.tr("Nenhum", "None");
                long bytes = LiveRecordings.bytes(c);
                return n + "  ·  " + (bytes >= 1L << 30 ? String.format(Ui.locale(), "%.1f GB", bytes / (double) (1L << 30))
                        : (bytes >> 20) + " MB");
            }
            case THEME_ROW: return ownColor() ? LiveTheme.name(LiveTheme.current()) : Ui.tr("Do estilo", "From the style");
            case CUSTOM_BG_ROW: return LiveTheme.bgName(LiveTheme.customBg());
            case CUSTOM_ROUND_ROW: return LiveTheme.roundName(LiveTheme.customRound());
            case CUSTOM_LAYOUT_ROW: return LiveTheme.styleName(LiveTheme.customLayout());
            case STYLE_ROW: return LiveTheme.styleName(LiveTheme.style());
            case NIGHT_ROW: return LiveTheme.nightStyle() < 0 ? Ui.tr("O mesmo", "The same") : LiveTheme.styleName(LiveTheme.nightStyle());
            case MORNING_ROW: return LiveTheme.morningStyle() < 0 ? Ui.tr("O mesmo", "The same") : LiveTheme.styleName(LiveTheme.morningStyle());
            case LATE_ROW: return LiveTheme.lateStyle() < 0 ? Ui.tr("Como à noite", "Like the night") : LiveTheme.styleName(LiveTheme.lateStyle());
            case CODE_ROW: return LiveTheme.style() == LiveTheme.CUSTOM ? LiveTheme.code() : Ui.tr("Digitar um", "Type one in");
            case RESET_ROW: return "";
            case CC_ON_ROW: return onOff(LiveOptions.subtitles(c));
            case CC_SIZE_ROW: {
                int s = LiveOptions.ccSize(c);
                return s == 0 ? Ui.tr("Pequeno", "Small") : s == 1 ? Ui.tr("Médio", "Medium") : s == 2 ? Ui.tr("Grande", "Large")
                        : Ui.tr("Muito grande", "Extra large");
            }
            case CC_STYLE_ROW: {
                int s = LiveOptions.ccStyle(c);
                return s == LiveOptions.CC_BAND ? Ui.tr("Faixa escura", "Dark band") : s == LiveOptions.CC_BOX ? Ui.tr("Caixa preta", "Black box")
                        : Ui.tr("Contorno", "Outline");
            }
            case CC_COLOR_ROW: {
                int s = LiveOptions.ccColor(c);
                return s == 0 ? Ui.tr("Branco", "White") : s == 1 ? Ui.tr("Amarelo", "Yellow") : Ui.tr("Ciano", "Cyan");
            }
            case OPACITY_ROW: {
                int pct = LiveOptions.panelOpacity(c);
                return pct >= 100 ? Ui.tr("Sólido", "Solid") : pct + "%";
            }
            case HEALTH_ROW: {
                int n = LiveHealth.week(c).size();
                return n == 0 ? Ui.tr("Tudo bem esta semana", "All good this week")
                        : n + (n == 1 ? Ui.tr(" canal com problemas", " channel with trouble") : Ui.tr(" canais com problemas", " channels with trouble"));
            }
            case SUBSCRIPTION_ROW: {
                Xtream.Account a = Xtream.savedAccount(c);
                if (a == null) return "—";
                if (a.expires <= 0) return Ui.tr("Sem data de fim", "No end date");
                String when = new java.text.SimpleDateFormat("d MMM yyyy", Ui.locale()).format(new java.util.Date(a.expires));
                return (a.expires < System.currentTimeMillis() ? Ui.tr("Acabou em ", "Ended ") : Ui.tr("Acaba em ", "Ends ")) + when;
            }
            case PROFILES_ROW: {
                int n = LiveProfiles.all(c).size();
                return LiveProfiles.active(c).name + (n > 1 ? "  ·  " + n : "");
            }
            case ASK_PROFILE_ROW: return onOff(LiveOptions.askProfile(c));
            case PLAYLISTS_ROW: {
                int n = LivePlaylists.all(c).size();
                return n <= 1 ? Xtream.user(c) : n + Ui.tr(" listas", " playlists");
            }
            case ADD_PLAYLIST_ROW: return "";
            case HISTORY_ROW: return String.valueOf(LiveOptions.historyMax(c));
            case CLEAR_HISTORY_ROW: {
                int n = Xtream.recent(c).size();
                return n == 0 ? Ui.tr("Vazio", "Empty") : n + Ui.tr(" canais", " channels");
            }
            case EXPORT_ROW:
            case IMPORT_ROW: return LiveBackup.available() ? Ui.tr("Downloads", "Downloads") : "—";
            case SERIES_ROW:
            case SERIES_RECORD_ROW: {
                int n = series(c, i == SERIES_RECORD_ROW).size();
                return n == 0 ? Ui.tr("Nenhuma", "None") : String.valueOf(n);
            }
            case AGE_ROW: {
                int age = LiveOptions.ageLock(c);
                return age <= 0 ? Ui.tr("Desligado", "Off") : age + "+";
            }
            case FOLLOW_ROW: {
                List<String> f = LiveFollow.all(c);
                return f.isEmpty() ? Ui.tr("Nada", "Nothing") : f.size() == 1 ? f.get(0) : f.get(0) + " +" + (f.size() - 1);
            }
            case SEPARATE_ROW: {
                if (!LiveOptions.merge(c)) return "—";
                int n = LiveOptions.separate(c).size();
                return n == 0 ? Ui.tr("Nenhum", "None") : String.valueOf(n);
            }
            case HIDDEN_ROW: return countOf(LiveOptions.hidden(c));
            case ORDER_ROW: return favorites().size() + Ui.tr(" canais", " channels");
            case PIN_ROW: return LiveOptions.hasPin(c) ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
            case LOCKED_ROW: return LiveOptions.hasPin(c) ? countOf(LiveOptions.locked(c)) : "—";
            default: return "";
        }
    }

    /** How many of the provider's categories are in `ids` (a category it dropped no longer counts). */
    private String countOf(Set<String> ids) {
        int n = 0;
        for (Xtream.Category cat : view.providerCategories()) if (ids.contains(cat.id)) n++;
        return n == 0 ? Ui.tr("Nenhuma", "None") : String.valueOf(n);
    }

    /** What the focused row does, under the list. */
    private String hint() {
        String t = hintRaw();
        if (screen != ROWS || !pickable(id())) return t;
        t = t.replaceAll("\\s*\\([^)]*←[^)]*\\)", "").replaceAll("^←[^;.]*[;.]\\s*", "").trim();
        if (!t.isEmpty() && !t.endsWith(".")) t += ".";
        // What's left of "← → to change it; the sample…" starts mid-sentence: a capital, as a sentence of its own.
        if (!t.isEmpty()) t = t.substring(0, 1).toUpperCase(Ui.locale()) + t.substring(1);
        return (t.isEmpty() ? "" : t + " ") + Ui.tr("OK mostra as opções.", "OK shows the choices.");
    }

    /** Whether OK on row `id` opens a picker (choose / words). */
    private boolean pickable(int id) {
        switch (id) {
            case STYLE_ROW: case MORNING_ROW: case NIGHT_ROW: case LATE_ROW: case CUSTOM_LAYOUT_ROW: case CUSTOM_BG_ROW:
            case CUSTOM_ROUND_ROW: case CC_SIZE_ROW: case CC_STYLE_ROW: case CC_COLOR_ROW: case FORMAT_ROW: case BUFFER_ROW:
            case CHANNEL_SORT_ROW: case CATEGORY_SORT_ROW: case REMIND_AUTO_ROW: case HOME_ROW: case MATCH_ROW: case MATCH_STYLE_ROW: case GUIDE_SIZE_ROW: case GUIDE_HOURS_ROW:
            case PAST_DAYS_ROW: case DAYS_AHEAD_ROW: case REMIND_LEAD_ROW: case HISTORY_ROW: case OPACITY_ROW: case AGE_ROW:
            case RECORD_EARLY_ROW: case RECORD_LATE_ROW:
            case GUIDE_SHIFT_ROW:
                return true;
            case THEME_ROW:
                return ownColor();
            default:
                return false;
        }
    }

    private String hintRaw() {
        switch (screen) {
            case SCHEDULE: return Ui.tr("OK cancela o que está em foco. Segure OK num programa do guia para marcar.",
                    "OK cancels the one in focus. Hold OK on a show in the guide to set one.");
            case PLAYLISTS: return Ui.tr("OK numa lista: usar ou tirar (a que está em uso sai por Sair da conta).",
                    "OK on a playlist: use it or remove it (the one in use goes with Sign out).");
            case SERIES: return Ui.tr("OK para a série em foco (os episódios já marcados saem também).",
                    "OK stops the series in focus (with its airings already set).");
            case FOLLOWING: return Ui.tr("OK deixa de seguir (os lembretes que ele marcou saem também).",
                    "OK stops following it (with the reminders it set).");
            case SEPARATE: return Ui.tr("OK junta o canal em foco com os parecidos de novo.",
                    "OK merges the one in focus with its similar channels again.");
            case HIDDEN: return Ui.tr("As ocultas saem da lista e do guia; os canais continuam em Todos os canais.",
                    "Hidden ones leave the list and the guide; their channels stay in All channels.");
            case LOCKED: return Ui.tr("Os canais delas pedem o PIN, uma vez a cada vez que a TV ao vivo abre.",
                    "Their channels ask for the PIN, once each time Live TV opens.");
            case ORDER: return carrying ? Ui.tr("▲ ▼ movem o canal, OK solta.", "▲ ▼ move the channel, OK puts it down.")
                    : Ui.tr("OK pega um canal. A fileira do Início segue esta ordem.", "OK picks a channel up. The home screen's row follows this order.");
            case MAIN:
                switch (focus) {
                    case 0: return Ui.tr("Formato, buffer, taxa de quadros, áudio, canais juntados.", "Format, buffer, frame rate, sound, merged channels.");
                    case 1: return Ui.tr("A ordem dos canais e categorias, as ocultas, os favoritos.", "The order of channels and categories, hidden ones, favorites.");
                    case 2: return Ui.tr("Quando o guia atualiza, quantos dias, o guia completo.", "When the guide updates, how many days, the full guide.");
                    case 3: return Ui.tr("A cor da TV ao vivo, abrir sozinha ao ligar ou acordar, as prévias no Início.",
                            "Live TV's color, opening by itself on start or wake, previews on the home screen.");
                    case KEYS_SECTION: return Ui.tr("O que cada botão faz enquanto você assiste.", "What each button does while you watch.");
                    case REMINDERS_SECTION: return Ui.tr("Os lembretes, os times e programas seguidos, os alertas de jogo e o placar ao vivo.",
                            "Reminders, the teams and shows you follow, match alerts and live scores.");
                    case RECORDINGS_SECTION: return Ui.tr("O que vai ser gravado, as séries gravadas e o que já foi gravado.",
                            "What's set to record, series recordings, and what's been recorded.");
                    case 7: return Ui.tr("Um PIN e as categorias que ele bloqueia.", "A PIN and the categories it locks.");
                    case CAPTIONS_SECTION: return Ui.tr("Legendas ligadas ou não, o tamanho, o fundo e a cor delas.",
                            "Captions on or off, their size, background and color.");
                    default: return Ui.tr("Perfis, suas listas e sair do seu provedor de IPTV.", "Profiles, your playlists, and signing out of your IPTV provider.");
                }
            case KEYS: return Ui.tr("← → trocam o que o botão faz enquanto você assiste. Segurar é outro botão.",
                    "← → change what the button does while you watch. Holding it is its own button.");
            default:
                switch (id()) {
                    case FORMAT_ROW: return Ui.tr("Automático usa o que o provedor prefere. Se canais não abrem, tente o outro.",
                            "Automatic uses what your provider prefers. If channels don't start, try the other one.");
                    case BUFFER_ROW: return Ui.tr("Maior: mais estável numa conexão lenta, demora um pouco mais para começar.",
                            "Larger: steadier on a slow connection, a little slower to start.");
                    case FRAME_RATE_ROW: return Ui.tr("A tela muda para a taxa do vídeo (50 Hz, 24 Hz…), quando o canal informa a dele.",
                            "The screen switches to the video's rate (50 Hz, 24 Hz…), when the channel tells its own.");
                    case AUDIO_LANG_ROW: return LiveOptions.audioLanguage(context()).isEmpty()
                            ? Ui.tr("Escolha um idioma no botão Áudio do player e todo canal começa nele.",
                                    "Pick a language with the player's Audio button and every channel starts in it.")
                            : Ui.tr("Todo canal que tem este idioma começa nele. OK volta para automático.",
                                    "Every channel that has this language starts in it. OK goes back to automatic.");
                    case PASSTHROUGH_ROW: return Ui.tr("Dolby e DTS vão direto para a soundbar. Sem som? Desligue.",
                            "Dolby and DTS go straight to your soundbar. No sound? Turn it off.");
                    case TUNNELING_ROW: return Ui.tr("Vídeo e som mais sincronizados em algumas TVs. Se o vídeo travar, desligue.",
                            "Smoother picture and sound in step on some TVs. If video stutters, turn it off.");
                    case MERGE_ROW: return Ui.tr("HD, FHD, 4K e HEVC do mesmo nome num canal só, no melhor que a sua internet aguenta.",
                            "HD, FHD, 4K and HEVC of one name as one channel, playing the best your internet carries.");
                    case COUNTRY_ROW: return Ui.tr("O país do canal (US, UK, CA…) numa etiqueta ao lado do nome, lido do nome ou da categoria.",
                            "The channel's country (US, UK, CA…) on a tag beside its name, read from its name or its category.");
                    case VOICE_ROW: return Ui.tr("\"Play TLC USA on Glassio Yahsun\" abre o canal. Os canais também podem aparecer na busca da TV.",
                            "\"Play TLC USA on Glassio Yahsun\" opens the channel. Channels can also show in the TV's search.");
                    case GUIDE_HOURS_ROW: return Ui.tr("Quanto tempo o guia salvo de um canal vale antes de buscar de novo.",
                            "How long a channel's saved guide lasts before it's fetched again.");
                    case PAST_DAYS_ROW: return Ui.tr("Programas que já passaram ficam no guia (canais com reprise: pelo menos a reprise).",
                            "Shows that are over stay in the guide (catch-up channels: at least their catch-up).");
                    case FULL_GUIDE_ROW: return Ui.tr("O guia inteiro do provedor de uma vez: o guia abre na hora e a busca acha programas.",
                            "Your provider's whole guide at once: the guide fills instantly and search finds shows.");
                    case DAYS_AHEAD_ROW: return Ui.tr("Quantos dias à frente o guia completo guarda.", "How many days ahead the full guide keeps.");
                    case GUIDE_SIZE_ROW: return Ui.tr("Linhas maiores e fáceis de ler, ou menores e mais canais na tela.",
                            "Bigger rows, easy to read from the couch, or smaller ones with more channels on screen.");
                    case GUIDE_SHIFT_ROW: return Ui.tr("Os programas começam antes ou depois do que o guia diz? Move o guia todo (só desta lista).",
                            "Shows start earlier or later than the guide says? Moves the whole guide (this playlist only).");
                    case GUIDE_SOURCE_ROW: return Ui.tr("O guia do seu provedor, ou um link XMLTV (.xml ou .xml.gz).",
                            "Your provider's guide, or an XMLTV link (.xml or .xml.gz).");
                    case GUIDE_NOW_ROW: {
                        long n = XmltvStore.count(context());
                        return n > 0 ? String.format(Locale.ROOT, "%,d", n) + Ui.tr(" programas no guia.", " programs in the guide.")
                                : Ui.tr("Busca o guia completo agora.", "Fetches the full guide now.");
                    }
                    case GUIDE_START_ROW: return Ui.tr("Busca o guia de novo cada vez que a TV ao vivo abre.",
                            "Fetches the guide again each time Live TV opens.");
                    case BOOT_ROW: return Ui.tr("A TV ao vivo abre sozinha quando a TV liga (com o Glassio como Início).",
                            "Live TV opens by itself when the TV starts (with Glassio as the home screen).");
                    case PREVIEW_ROW: return Ui.tr("No Início, o canal em foco na fileira Passando agora toca, sem som.",
                            "On the home screen, the channel in focus on the On now row plays, muted.");
                    case HOME_ROW: return Ui.tr("A TV ao vivo abre no Início (o que passa nos seus canais) ou direto no último canal.",
                            "Live TV opens on Home (what's on your channels) or straight on the last channel.");
                    case WAKE_ROW: return Ui.tr("A TV ao vivo abre sozinha quando a TV sai do modo de espera.",
                            "Live TV opens by itself when the TV wakes up from standby.");
                    case CHANNEL_SORT_ROW: return Ui.tr("Em cada categoria e no guia. Os favoritos ficam na ordem que você escolheu.",
                            "In every category and the guide. Favorites keep the order you chose.");
                    case CATEGORY_SORT_ROW: return Ui.tr("Favoritos, Todos os canais e Buscar continuam primeiro.",
                            "Favorites, All channels and Search stay first.");
                    case SCHEDULE_ROW: return Ui.tr("Os lembretes marcados para depois. OK mostra e cancela.", "The reminders set for later. OK shows them, to cancel one.");
                    case RECORD_LIST_ROW: return Ui.tr("As gravações marcadas para depois. OK mostra e cancela.", "The recordings set for later. OK shows them, to cancel one.");
                    case RECORDINGS_ROW: return Ui.tr("O que já foi gravado e o espaço que ocupa. OK abre.", "What's been recorded and the room it takes. OK opens them.");
                    case SERIES_RECORD_ROW: return Ui.tr("Programas gravados sempre que passam (OK num programa futuro no guia → Todos os episódios…).",
                            "Shows recorded every time they air (OK on a later show in the guide → Every episode…).");
                    case CLEAR_HISTORY_ROW: return Ui.tr("Tira todos os canais do Histórico (um só: segure OK nele no Histórico).",
                            "Takes every channel out of History (just one: hold OK on it in History).");
                    case EXPORT_ROW: return Ui.tr("Favoritos, perfis, lembretes, séries e ajustes num arquivo em Downloads (sem o login nem o PIN).",
                            "Favorites, profiles, reminders, series and settings in a file in Downloads (not your login or PIN).");
                    case IMPORT_ROW: return Ui.tr("Tudo do backup volta, no lugar do que está agora; os favoritos achados pelo nome.",
                            "Everything in the backup comes back, in place of what's here now; favorites found by name.");
                    case HISTORY_ROW: return Ui.tr("Quantos canais assistidos por último o Histórico guarda (os mais antigos saem).",
                            "How many of the channels you watched last History keeps (the oldest go).");
                    case HEALTH_ROW: return Ui.tr("Canais que travaram ou reconectaram nos últimos 7 dias. OK para a lista.",
                            "Channels that froze or reconnected in the last 7 days. OK for the list.");
                    case SUBSCRIPTION_ROW: {
                        Xtream.Account a = Xtream.savedAccount(context());
                        if (a == null) return Ui.tr("OK pergunta ao provedor.", "OK asks your provider.");
                        String s = (a.status.isEmpty() ? "" : a.status + (a.trial ? Ui.tr(" (teste)", " (trial)") : "") + "  ·  ")
                                + (a.max > 0 ? a.active + Ui.tr(" de ", " of ") + a.max + Ui.tr(" conexões em uso", " connections in use") + "  ·  " : "")
                                + Ui.tr("visto às ", "checked at ") + Ui.time(a.at);
                        return s + Ui.tr("  ·  OK para ver de novo", "  ·  OK to check again");
                    }
                    case PROFILES_ROW: return Ui.tr("Cada pessoa com seus favoritos, Histórico e Continue assistindo; o infantil só com canais infantis.",
                            "Each person with their own favorites, History and Continue watching; a Kids one with children's channels only.");
                    case ASK_PROFILE_ROW: return Ui.tr("Ao abrir, a TV ao vivo pergunta quem está assistindo (com mais de um perfil).",
                            "On opening, Live TV asks who's watching (with more than one profile).");
                    case PLAYLISTS_ROW: return Ui.tr("Suas listas (Xtream, M3U ou MAG): cada uma com seus canais, favoritos, Histórico e guia.",
                            "Your playlists (Xtream, M3U or MAG): each with its own channels, favorites, History and guide.");
                    case ADD_PLAYLIST_ROW: return Ui.tr("Outra lista: Xtream, M3U ou MAG (Voltar na tela de entrada volta para esta).",
                            "Another playlist: Xtream, M3U or MAG (Back on the sign-in screen returns to this one).");
                    case CC_ON_ROW: return Ui.tr("Legendas em todo canal que tem (o botão CC do controle também troca).",
                            "Captions on every channel that has them (the remote's CC button switches them too).");
                    case CC_SIZE_ROW:
                    case CC_STYLE_ROW:
                    case CC_COLOR_ROW: return Ui.tr("← → para trocar; o exemplo abaixo mostra como ficam.",
                            "← → to change it; the sample below shows how they look.");
                    case OPACITY_ROW: return Ui.tr("Quanto o canal aparece atrás: menos é mais transparente (← → para trocar).",
                            "How much the channel shows through: lower is clearer (← → to change it).");
                    case CUSTOM_BG_ROW: return Ui.tr("O fundo e as cores do guia do Personalizado (← → para trocar).",
                            "Custom's background and guide colors (← → to change it).");
                    case CUSTOM_ROUND_ROW: return Ui.tr("Quanto os blocos e o foco são redondos (← → para trocar).",
                            "How round the tiles and the focus are (← → to change it).");
                    case CUSTOM_LAYOUT_ROW: return Ui.tr("De qual estilo o Personalizado usa o layout do guia e as letras (← →).",
                            "Whose guide layout and lettering Custom uses (← → to change it).");
                    case THEME_ROW: return ownColor()
                            ? Ui.tr("A cor do foco e dos destaques na TV ao vivo (← → para trocar).",
                                    "The color of the focus and the highlights in Live TV (← → to change it).")
                            : Ui.tr("Os outros estilos têm as cores deles; escolha Glassio ou Personalizado para escolher uma.",
                                    "The other styles bring their own colors; pick Glassio or Custom to choose one.");
                    case STYLE_ROW: return LiveTheme.styleAbout(LiveTheme.style());
                    case NIGHT_ROW: return Ui.tr("Do pôr do sol ao nascer do sol, outro estilo (← → para trocar).",
                            "From sunset to sunrise, another style (← → to change it).");
                    case MORNING_ROW: return Ui.tr("Do nascer do sol ao meio-dia, outro estilo (← → para trocar).",
                            "From sunrise to noon, another style (← → to change it).");
                    case LATE_ROW: return Ui.tr("Da meia-noite ao nascer do sol, outro estilo (← → para trocar).",
                            "From midnight to sunrise, another style (← → to change it).");
                    case RESET_ROW: return Ui.tr("Os ajustes desta seção como eram no começo (o PIN, as contas e as listas ficam).",
                            "This section's settings as they first were (your PIN, accounts and lists stay).");
                    case CODE_ROW: return LiveTheme.style() == LiveTheme.CUSTOM
                            ? Ui.tr("O código do seu Personalizado: digite-o em outra TV para ter o mesmo. OK para digitar um.",
                                    "Your Custom style's code: type it on another TV to get the same. OK to type one in.")
                            : Ui.tr("OK para digitar o código de estilo de outra TV.", "OK to type a style code from another TV.");
                    case MATCH_ROW: return Ui.tr("Quando o que você segue tem lembrete: só quando passa ao vivo, ou também nas reprises.",
                            "When what you follow gets a reminder: only when it's on live, or for replays too.");
                    case MATCH_STYLE_ROW: return Ui.tr("Quando um time seguido começa: um painel com todos os canais que mostram o jogo, ou um aviso só.",
                            "When a team you follow comes on: a panel with every channel showing the match, or one card.");
                    case SCORES_ROW: return Ui.tr("O placar e o tempo de jogo dos times que você segue, na barra do canal e no painel do jogo.",
                            "The score and match minute for the teams you follow, on the info bar and the match panel.");
                    case GOALS_ROW: return Ui.tr("Um aviso quando sai um gol num jogo de um time que você segue, enquanto a TV ao vivo está aberta.",
                            "A card when there's a goal in a followed team's match, while Live TV is open.");
                    case REMIND_WATCHING_ROW: return Ui.tr("Um lembrete do canal que já está passando na TV ao vivo: aparece mesmo assim, ou não.",
                            "A reminder for the channel already on in Live TV: shows anyway, or not at all.");
                    case FOLLOW_ROW: return Ui.tr("Times e programas seguidos: um lembrete a cada vez que passam, em qualquer canal (Buscar → Seguir).",
                            "Teams and shows you follow: a reminder each time they're on, on any channel (Search → Follow).");
                    case SERIES_ROW: return Ui.tr("Programas lembrados sempre que passam (OK num programa futuro no guia → Todos os episódios…).",
                            "Shows you're reminded of every time they air (OK on a later show in the guide → Every episode…).");
                    case RECORD_EARLY_ROW: return Ui.tr("Quanto antes do programa a gravação começa, para não perder o começo.",
                            "How long before the show a recording starts, so the beginning isn't missed.");
                    case RECORD_LATE_ROW: return Ui.tr("Quanto tempo a gravação continua depois do fim: um jogo com acréscimos ou prorrogação.",
                            "How long a recording keeps going after the end: for a match's stoppage time or extra time.");
                    case REMIND_LEAD_ROW: return Ui.tr("Quanto antes do programa o aviso aparece (OK num programa futuro no guia marca um).",
                            "How early before a show its reminder pops up (OK on a later show in the guide sets one).");
                    case REMIND_AUTO_ROW: return Ui.tr("Perguntar: Assistir ou Dispensar. Sozinho: a TV muda para o canal quando começa, a não ser que você dispense.",
                            "Ask: Watch or Dismiss. Automatically: the TV switches to the channel as it starts, unless you dismiss it.");
                    case SEPARATE_ROW: return Ui.tr("Canais juntados que você mostra separados (segure OK num canal no guia).",
                            "Merged channels you show separately (hold OK on a channel in the guide).");
                    case HIDDEN_ROW: return Ui.tr("Categorias do provedor que você não usa.", "Your provider's categories you don't use.");
                    case ORDER_ROW: return Ui.tr("A ordem dos favoritos, aqui e no Início.", "Your favorites' order, here and on the home screen.");
                    case PIN_ROW: return Ui.tr("Um PIN para as categorias bloqueadas.", "A PIN for the locked categories.");
                    case AGE_ROW: return Ui.tr("Programas com essa classificação ou mais pedem o PIN (as classificações do guia completo).",
                            "Shows rated this age or older ask for the PIN (from your full guide's ratings).");
                    case LOCKED_ROW: return LiveOptions.hasPin(context()) ? Ui.tr("Categorias que pedem o PIN.", "Categories that ask for the PIN.")
                            : Ui.tr("Primeiro ligue o PIN dos pais.", "Turn on the parental PIN first.");
                    default: return Ui.tr("Os canais somem até você entrar de novo.", "The channels go away until you sign in again.");
                }
        }
    }

    // MARK: - Drawing

    void draw(Canvas c, float a, int w, int h) {
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        c.translate(-(1 - a) * 40 * k, 0);
        c.save();       // the style's background, as see-through as set (Settings background)
        c.translate(-90 * k, 0);
        LiveTheme.background(c, Math.round(w + 180 * k), h, opacity / 100f);
        c.restore();
        if (LiveTheme.rim != 0) {       // glass: the column a pane of it, with its light edge
            r.set((RAIL_X - 40) * k, 40 * k, (RAIL_R + 24) * k, h - 40 * k);
            fill.setColor(LiveTheme.surface);
            c.drawRoundRect(r, LiveTheme.panelRound(40 * k), LiveTheme.panelRound(40 * k), fill);
            stroke.setStrokeWidth(1.5f * k);
            LiveTheme.drawRim(c, r, LiveTheme.panelRound(40 * k), stroke);
        } else {
            fill.setColor(0x33000000);       // the column, a shade darker
            c.drawRect(-90 * k, 0, (RAIL_R + 40) * k, h, fill);
        }
        drawRail(c);
        if (screen == MAIN) {
            drawDetail(c);
            c.restoreToCount(save);
            return;
        }
        c.drawText(title(), X * k, 150 * k, heading);
        int n = count(), rows = visibleRows();
        c.save();
        c.clipRect(0, (TOP - 12) * k, PANEL_W * k, (TOP + rows * STEP) * k);
        rowRect(row.value, focusRect);
        grow(focusRect);
        glow.setColor(LiveTheme.tile);      // the focus in the theme's color, its text in the style's ink
        if (n > 0) c.drawRoundRect(focusRect, 16 * k * LiveTheme.round, 16 * k * LiveTheme.round, glow);
        labelOn.setColor(LiveTheme.ink);
        valueOn.setColor(Ui.alpha(LiveTheme.ink, 0.92f));
        c.save();
        c.clipOutRect(focusRect);       // the plain text around the focus only: under it, just the bold (no double text)
        drawRows(c, n, false);
        c.restore();
        c.save();
        c.clipRect(focusRect);
        drawRows(c, n, true);
        c.restore();
        c.restore();
        String text = n == 0 ? Ui.tr("Nada aqui ainda.", "Nothing here yet.") : hint();
        int id = id();
        if (id == STYLE_ROW || id == NIGHT_ROW || id >= CUSTOM_BG_ROW && id <= CODE_ROW) {
            // The guide in miniature as the style draws it, its words beside it.
            float py = TOP + Math.min(n, rows) * STEP + 16, ph = Math.min(216, 1080 - 24 - py), pw = ph * 16 / 9;
            // The style being changed: the night one on its row (the day's when it's "the same"), else the day's.
            int shown = id == NIGHT_ROW ? LiveTheme.styleAt(LiveTheme.EVENING) : id == MORNING_ROW ? LiveTheme.styleAt(LiveTheme.MORNING)
                    : id == LATE_ROW ? LiveTheme.styleAt(LiveTheme.LATE) : LiveTheme.style();
            final float px0 = (X + 22) * k, py0 = py * k, pw0 = pw * k;
            LiveTheme.asStyle(shown, () -> view.drawStylePreview(c, px0, py0, pw0));
            float tx = X + 22 + pw + 32;
            if (styleHint == null) styleHint = new android.text.TextPaint(hint);
            if (!text.equals(styleHintText) || styleHintLayout == null) {
                styleHintText = text;
                styleHintLayout = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), styleHint,
                        Math.max(1, Math.round((R - tx) * k))).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(tx * k, (py + 8) * k);
            styleHintLayout.draw(c);
            c.restore();
        } else {        // two lines at most, so a long one shows whole
            if (plainHint == null) plainHint = new android.text.TextPaint(hint);
            if (!text.equals(plainHintText) || plainHintLayout == null) {
                plainHintText = text;
                plainHintLayout = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), plainHint,
                        Math.max(1, Math.round((R - X - 22) * k))).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate((X + 22) * k, (TOP + Math.min(n, rows) * STEP + 44) * k - plainHintLayout.getLineBaseline(0));
            plainHintLayout.draw(c);
            c.restore();
        }
        if (screen == ROWS && section == CAPTIONS_SECTION) {     // how the captions look, as set
            view.drawCaptionSample(c, (X + R) / 2 * k, (TOP + n * STEP + 300) * k);
        }
        c.restoreToCount(save);
        if (XmltvStore.running()) view.postInvalidateDelayed(500);   // the full guide's progress moves on
    }

    /** The section a page belongs to (the one marked in the column). */
    private int marked() {
        switch (screen) {
            case MAIN: return focus;
            case ROWS: return section;
            case KEYS: return KEYS_SECTION;
            case HIDDEN:
            case ORDER: return 1;
            case SEPARATE: return 0;
            case LOCKED: return 6;
            case SCHEDULE:
            case FOLLOWING:
            case SERIES: return 5;
            case PLAYLISTS: return 7;
            default: return section;
        }
    }

    /**
     * The column: Live TV at the top, then the sections, each with its symbol. On the first list it has the focus (the
     * theme's tile gliding); in a section, that one stays marked (a light tile and a bar in the theme's color).
     */
    private void drawRail(Canvas c) {
        Symbols.draw(view.getContext(), c, "live_tv", (RAIL_X + 30) * k, 140 * k, 36 * k, 0xEBFFFFFF);
        c.drawText(Ui.tr("TV ao vivo", "Live TV"), (RAIL_X + 72) * k, 154 * k, heading);
        boolean main = screen == MAIN;
        int marked = marked();
        // Search, at the header's end: a pill, the focus' tile on it (↑ from the first section).
        float sw = 170, sx = RAIL_R - sw;
        r.set(sx * k, 108 * k, RAIL_R * k, 168 * k);
        glow.setColor(main && onSearch ? LiveTheme.tile : LiveTheme.surface);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, glow);
        int sInk = main && onSearch ? LiveTheme.ink : 0xD9FFFFFF;
        Symbols.draw(view.getContext(), c, "search", r.left + 36 * k, r.centerY(), 26 * k, sInk);
        railLabel.setColor(sInk);
        float ts = railLabel.getTextSize();
        railLabel.setTextSize(24 * k);
        c.drawText(Ui.tr("Buscar", "Search"), r.left + 62 * k, r.centerY() + Ui.halfCap(railLabel), railLabel);
        railLabel.setTextSize(ts);
        if (main && !onSearch) {
            float fy = (RAIL_TOP + row.value * RAIL_STEP) * k;
            r.set(RAIL_X * k, fy, RAIL_R * k, fy + RAIL_H * k);
            glow.setColor(LiveTheme.tile);
            c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, glow);
        }
        for (int i = 0; i < SECTION_ROWS.length; i++) {
            float y = (RAIL_TOP + i * RAIL_STEP) * k;
            r.set(RAIL_X * k, y, RAIL_R * k, y + RAIL_H * k);
            if (!main && i == marked) {
                fill.setColor(LiveTheme.surface | 0x05000000);
                c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, fill);
                fill.setColor(LiveTheme.text);
                c.drawRoundRect(r.left, r.top + 18 * k, r.left + 6 * k, r.bottom - 18 * k, 3 * k, 3 * k, fill);
            }
            boolean on = main ? i == focus && !onSearch : i == marked;
            int ink = on && main ? LiveTheme.ink : on ? WHITE : main ? 0xE6FFFFFF : 0xB3FFFFFF;
            Symbols.draw(view.getContext(), c, SECTION_ICONS[i], r.left + 46 * k, r.centerY(), 28 * k, ink);
            railLabel.setColor(ink);
            c.drawText(ellipsize(sectionName(i), railLabel, r.width() - 130 * k), r.left + 92 * k, r.centerY() + Ui.halfCap(railLabel), railLabel);
            if (main && i != KEYS_SECTION) {
                float cw = Symbols.width(view.getContext(), "chevron_right", 22 * k);
                Symbols.draw(view.getContext(), c, "chevron_right", r.right - 28 * k - cw / 2, r.centerY(), 22 * k, on ? Ui.alpha(LiveTheme.ink, 0.9f) : 0x99FFFFFF);
            }
        }
    }

    /** The focused section at the right: its symbol, name and what it's for, then its settings and their values. */
    private void drawDetail(Canvas c) {
        int s = Math.max(0, Math.min(SECTION_ROWS.length - 1, focus));
        float x = (X + 10) * k, width = (R - X - 20) * k;
        Symbols.draw(view.getContext(), c, SECTION_ICONS[s], x + 40 * k, 246 * k, 60 * k, 0xEBFFFFFF);
        c.drawText(sectionName(s), x, 352 * k, detailTitle);
        String about = hint();
        if (aboutLayout == null || !about.equals(aboutShown) || aboutLayout.getWidth() != Math.round(width)) {
            aboutShown = about;
            aboutLayout = android.text.StaticLayout.Builder.obtain(about, 0, about.length(), detailText, Math.max(1, Math.round(width)))
                    .setLineSpacing(0, 1.15f).build();
        }
        c.save();
        c.translate(x, 384 * k);
        aboutLayout.draw(c);
        c.restore();
        float y = 384 * k + aboutLayout.getHeight() + 70 * k;
        // What's in it, as it's set now (OK opens it).
        int[] ids = rowsOf(s);
        for (int i = 0; i < ids.length && y < 1010 * k; i++) {
            String lv = rowLabel(ids[i]), vv = rowValue(ids[i]);
            Boolean sw = switchState(ids[i]);
            if (sw != null) drawSwitch(c, x + width, y - Ui.halfCap(previewLabel), sw, switchUsable(ids[i]), false, 0.72f);
            else c.drawText(ellipsize(vv, previewValue, width * 0.4f), x + width, y, previewValue);
            c.drawText(ellipsize(lv, previewLabel, width * 0.58f), x, y, previewLabel);
            y += 50 * k;
        }
        if (s == KEYS_SECTION) {
            c.drawText(Ui.tr("OK para escolher o que cada botão faz.", "OK to choose what each button does."), x, y, previewLabel);
        }
    }

    private void drawRows(Canvas c, int n, boolean ink) {
        int rows = visibleRows();
        int first = Math.max(0, (int) Math.floor(scroll.value)), last = Math.min(n, first + rows + 2);
        boolean lockedOff = screen == ROWS && !LiveOptions.hasPin(context());
        for (int i = first; i < last; i++) {
            rowRect(i, r);
            Paint p = ink ? labelOn : label, v = ink ? valueOn : value;
            float base = r.centerY() + Ui.halfCap(p);
            boolean dim = lockedOff && rowsOf(section)[i] == LOCKED_ROW;
            if (dim && !ink) p.setAlpha(0x66);
            Boolean sw = screen == ROWS ? switchState(rowsOf(section)[i]) : null;
            float valueW;
            if (sw != null) {
                valueW = drawSwitch(c, r.right - 22 * k, r.centerY(), sw, switchUsable(rowsOf(section)[i]), ink, 1);
            } else {
                String val = value(i);
                c.drawText(val, r.right - 22 * k, base, v);
                valueW = v.measureText(val);
            }
            c.drawText(ellipsize(label(i), p, r.width() - 80 * k - valueW), r.left + 22 * k, base, p);
            p.setAlpha(ink ? 255 : 0xCC);
        }
    }

    /** On or off, for a row that's a switch; null for the others (they show their value). */
    private Boolean switchState(int id) {
        Context c = context();
        switch (id) {
            case FRAME_RATE_ROW: return LiveOptions.frameRate(c);
            case PASSTHROUGH_ROW: return LiveOptions.passthrough(c);
            case TUNNELING_ROW: return LiveOptions.tunneling(c);
            case MERGE_ROW: return LiveOptions.merge(c);
            case GUIDE_START_ROW: return LiveOptions.guideOnStart(c);
            case FULL_GUIDE_ROW: return LiveOptions.fullGuide(c);
            case BOOT_ROW: return LiveOptions.startOnBoot(c);
            case WAKE_ROW: return LiveOptions.startOnWake(c);
            case PREVIEW_ROW: return LiveOptions.homePreviews(c);
            case REMIND_WATCHING_ROW: return LiveOptions.remindWatching(c);
            case SCORES_ROW: return LiveOptions.scores(c);
            case GOALS_ROW: return LiveOptions.scores(c) && LiveOptions.goalAlerts(c);
            case CC_ON_ROW: return LiveOptions.subtitles(c);
            case ASK_PROFILE_ROW: return LiveOptions.askProfile(c);
            case PIN_ROW: return LiveOptions.hasPin(c);
            case COUNTRY_ROW: return LiveOptions.countryTags(c);
            case VOICE_ROW: return LiveOptions.voiceChannels(c);
            default: return null;
        }
    }

    /** A switch that can't change now (goal alerts without live scores) shows faint. */
    private boolean switchUsable(int id) {
        return id != GOALS_ROW || LiveOptions.scores(context());
    }

    private final Paint switchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF switchRect = new RectF();
    /** Glassio's switch: its purple when on, faint when off, the white knob at the side it's set to. */
    private static final int SWITCH_ON = 0xFF7B61FF;

    /**
     * A switch with its right edge at `right`, centered on `cy`, `scale` of a row's size; returns how wide it is.
     * `ink`: on the focus' plate.
     */
    private float drawSwitch(Canvas c, float right, float cy, boolean on, boolean usable, boolean ink, float scale) {
        float w = 76 * k * scale, h = 44 * k * scale, pad = 6 * k * scale, knob = h - 2 * pad;
        switchRect.set(right - w, cy - h / 2, right, cy + h / 2);
        boolean lightPlate = ink && ((LiveTheme.ink >>> 16 & 255) < 128);     // dark text on the focus: a light plate
        int off = lightPlate ? 0x33000000 : 0x38FFFFFF;
        int track = on ? SWITCH_ON : off;
        if (!usable) track = (track & 0x00FFFFFF) | 0x59000000;
        switchPaint.setStyle(Paint.Style.FILL);
        switchPaint.setColor(track);
        c.drawRoundRect(switchRect, h / 2, h / 2, switchPaint);
        float kx = on ? switchRect.right - pad - knob / 2 : switchRect.left + pad + knob / 2;
        switchPaint.setColor(usable ? 0xFFFFFFFF : 0xB3FFFFFF);
        c.drawCircle(kx, cy, knob / 2, switchPaint);
        if (lightPlate) {        // a fine edge, so the white knob reads on the white plate
            switchPaint.setStyle(Paint.Style.STROKE);
            switchPaint.setStrokeWidth(Math.max(1, 1.2f * k));
            switchPaint.setColor(0x26000000);
            c.drawCircle(kx, cy, knob / 2, switchPaint);
            switchPaint.setStyle(Paint.Style.FILL);
        }
        return w;
    }

    private void rowRect(float i, RectF out) {
        float y = (TOP + (i - scroll.value) * STEP) * k;
        out.set(X * k, y, R * k, y + ROW_H * k);
    }

    private static void grow(RectF rect) {
        float dx = rect.width() * 0.0175f / 2, dy = rect.height() * 0.035f / 2;
        rect.inset(-dx, -dy);
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }

    private static int visibleRows() {
        return (int) ((1080 - TOP - 110) / STEP);
    }

    // MARK: - Keys

    /** On the first list, the Search pill (↑ from the first section) has the focus. */
    private boolean onSearch;
    /** The section last left for the column, and its row then. */
    private int leftSection = -1, leftRow;

    /** A key (down) while the settings show. */
    void key(int code) {
        valueCache.clear();
        int n = count();
        if (screen == MAIN && (onSearch || focus == 0 && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_SEARCH))) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_SEARCH) {
                onSearch = true;
                if (code == KeyEvent.KEYCODE_SEARCH) search();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                onSearch = false;
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                search();
            }
            view.invalidate();
            return;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (screen == ROWS && steps()) cycle(-1);
                else if (screen == KEYS) cycleKey(-1);
                else if (screen != MAIN && screen != ORDER) back();      // out to the column (a list: to its row)
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (screen == ROWS && steps()) cycle(1);
                else if (screen == KEYS) cycleKey(1);
                else if (screen == MAIN && n > 0) activate();      // into the section, as its › says
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (n > 0) activate();
                break;
            default:
                break;
        }
        view.invalidate();
    }

    /** Back: from a list to the first one (putting down a channel being moved), from the first to the categories. */
    void back() {
        if (screen == MAIN) {
            view.closeSettings();
            return;
        }
        if (screen == ROWS) {
            leftSection = section;
            leftRow = focus;
            show(MAIN, section);
            return;
        }
        if (screen == KEYS) {
            show(MAIN, KEYS_SECTION);
            return;
        }
        if (screen == ORDER && carrying) saveOrder();
        int from = screen == HIDDEN ? HIDDEN_ROW : screen == ORDER ? ORDER_ROW
                : screen == SCHEDULE ? (listRecordings ? RECORD_LIST_ROW : SCHEDULE_ROW)
                : screen == SERIES ? (listRecordings ? SERIES_RECORD_ROW : SERIES_ROW) : screen == FOLLOWING ? FOLLOW_ROW : screen == PLAYLISTS ? PLAYLISTS_ROW
                : screen == SEPARATE ? SEPARATE_ROW : LOCKED_ROW;
        showRow(from);
    }

    /** The setting row in focus (ROWS). */
    private int id() {
        return screen == ROWS && focus < rowsOf(section).length ? rowsOf(section)[focus] : -1;
    }

    /** Whether ← → step the focused setting (the rest open something). */
    private boolean steps() {
        int id = id();
        return id >= 0 && id <= PREVIEW_ROW && id != GUIDE_SOURCE_ROW && id != GUIDE_NOW_ROW || id == REMIND_LEAD_ROW || id == REMIND_AUTO_ROW
                || id == RECORD_EARLY_ROW || id == RECORD_LATE_ROW
                || id == THEME_ROW || id == STYLE_ROW || id == NIGHT_ROW || id >= CUSTOM_BG_ROW && id <= LATE_ROW
                || id == HISTORY_ROW || id == OPACITY_ROW || id >= CC_ON_ROW && id <= CC_COLOR_ROW || id == HOME_ROW
                || id == AGE_ROW || id == MATCH_ROW || id == MATCH_STYLE_ROW || id == REMIND_WATCHING_ROW || id == GUIDE_SIZE_ROW || id == SCORES_ROW || id == GOALS_ROW
                || id == COUNTRY_ROW || id == VOICE_ROW;
    }

    /** Setting row `id`, in its section, in focus. */
    private void showRow(int id) {
        for (int s = 0; s < SECTION_ROWS.length; s++) {
            for (int i = 0; i < rowsOf(s).length; i++) {
                if (rowsOf(s)[i] != id) continue;
                section = s;
                show(ROWS, i);
                return;
            }
        }
        show(MAIN, 0);
    }

    /** ← → (and OK) on a button: what it does next. */
    private void cycleKey(int dir) {
        String slot = LiveKeys.SLOTS[focus];
        int[] actions = LiveKeys.ACTIONS;
        int now = LiveKeys.action(context(), slot), at = 0;
        for (int i = 0; i < actions.length; i++) if (actions[i] == now) at = i;
        LiveKeys.set(context(), slot, actions[(at + dir + actions.length) % actions.length]);
        announce();
        view.invalidate();
    }

    private void move(int dir) {
        int n = count();
        if (n == 0) return;
        int next = Math.max(0, Math.min(n - 1, focus + dir));
        if (next == focus) return;
        if (screen == ORDER && carrying) {     // the channel picked up moves with the focus
            List<Xtream.Channel> list = favorites();
            Xtream.Channel ch = list.remove(focus);
            list.add(next, ch);
        }
        focus = next;
        aim(false);
        announce();
    }

    private void show(int next, int at) {
        screen = next;
        carrying = false;
        onSearch = false;
        focus = Math.max(0, Math.min(at, count() - 1));
        top = 0;
        aim(true);
        announce();
        view.invalidate();
    }

    private void aim(boolean snap) {
        int rows = visibleRows(), n = count();
        if (focus < top + 1) top = focus - 1;
        else if (focus > top + rows - 2) top = focus - rows + 2;
        top = Math.max(0, Math.min(Math.max(0, n - rows), top));
        row.target = focus;
        scroll.target = top;
        if (snap) {
            row.snap(focus);
            scroll.snap(top);
        }
    }

    /** ← → (and OK) step the row's value: playback ones start the player again with it; sorts re-sort the lists. */
    /**
     * OK on a theme setting: all its choices at once, each with its picture (LiveChooser), instead of stepping through
     * them with ← →. False for the other rows.
     */
    private boolean choose(final Context c, final int id) {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        switch (id) {
            case STYLE_ROW:
            case MORNING_ROW:
            case NIGHT_ROW:
            case LATE_ROW: {
                // The styles; the times of day start with "the same" (the style shown before them then).
                final boolean timed = id != STYLE_ROW;
                final int inherit = id == LATE_ROW ? (LiveTheme.nightStyle() >= 0 ? LiveTheme.nightStyle() : LiveTheme.style()) : LiveTheme.style();
                // In the lists' order (Custom last): place i is LiveTheme.ordered(i), after "the same" for a time of day.
                int n = LiveTheme.STYLES + (timed ? 1 : 0);
                String[] labels = new String[n];
                for (int i = 0; i < n; i++) {
                    int s = timed ? (i == 0 ? -1 : LiveTheme.ordered(i - 1)) : LiveTheme.ordered(i);
                    labels[i] = s < 0 ? (id == LATE_ROW ? Ui.tr("Como à noite", "Like the night") : Ui.tr("O mesmo", "The same"))
                            : LiveTheme.styleName(s);
                }
                int chosen = id == STYLE_ROW ? LiveTheme.style() : id == MORNING_ROW ? LiveTheme.morningStyle()
                        : id == NIGHT_ROW ? LiveTheme.nightStyle() : LiveTheme.lateStyle();
                int now = timed ? (chosen < 0 ? 0 : LiveTheme.placeOf(chosen) + 1) : LiveTheme.placeOf(chosen);
                String[] about = new String[n];
                for (int i = 0; i < n; i++) {
                    int s = timed ? (i == 0 ? -1 : LiveTheme.ordered(i - 1)) : LiveTheme.ordered(i);
                    about[i] = s < 0 ? "" : LiveTheme.styleAbout(s);
                }
                // Full rows, so the tiles stay big: the 14 styles 7 across, a time of day's 15 (with "the same") 5 across.
                LiveChooser.show(c, rowLabel(id), labels, about, now, timed ? 5 : 7, 16 / 9f, (cv, r, i) -> {
                    int s = timed ? (i == 0 ? -1 : LiveTheme.ordered(i - 1)) : LiveTheme.ordered(i);
                    final int shown = s < 0 ? inherit : s;
                    LiveTheme.asStyle(shown, () -> view.drawStylePreview(cv, r.left, r.top, r.width()));
                }, i -> {
                    int s = timed ? (i == 0 ? -1 : LiveTheme.ordered(i - 1)) : LiveTheme.ordered(i);
                    if (id == STYLE_ROW) LiveTheme.setStyle(c, s);
                    else if (id == MORNING_ROW) LiveTheme.setMorning(c, s);
                    else if (id == NIGHT_ROW) LiveTheme.setNight(c, s);
                    else LiveTheme.setLate(c, s);
                    restyled(c);
                });
                return true;
            }
            case CUSTOM_LAYOUT_ROW: {
                String[] labels = new String[LiveTheme.CUSTOM];
                for (int i = 0; i < labels.length; i++) labels[i] = LiveTheme.styleName(i);
                LiveChooser.show(c, rowLabel(id), labels, LiveTheme.customLayout(), 4, 16 / 9f,
                        (cv, r, i) -> LiveTheme.asStyle(i, () -> view.drawStylePreview(cv, r.left, r.top, r.width())),
                        i -> {
                            LiveTheme.setCustom(c, LiveTheme.customBg(), LiveTheme.customRound(), i);
                            restyled(c);
                        });
                return true;
            }
            case THEME_ROW: {
                if (!ownColor()) return false;      // the message, from cycle
                String[] labels = new String[LiveTheme.COUNT];
                for (int i = 0; i < labels.length; i++) labels[i] = LiveTheme.name(i);
                LiveChooser.show(c, rowLabel(id), labels, LiveTheme.current(), 4, 16 / 9f, (cv, r, i) -> {
                    // A focused tile in that color, among quiet ones, on the background in use.
                    p.setColor(LiveTheme.bg);
                    cv.drawRect(r, p);
                    float u = r.width() / 12, h = r.height() / 5;
                    for (int j = 0; j < 3; j++) {
                        float y = r.top + h * (0.8f + j * 1.3f);
                        int tile = LiveTheme.tileOf(i);
                        int lum = ((tile >> 16) & 255) * 3 + ((tile >> 8) & 255) * 6 + (tile & 255);
                        p.setColor(j == 1 ? tile : 0x1FFFFFFF);
                        cv.drawRoundRect(r.left + u, y, r.right - u, y + h, h / 3, h / 3, p);
                        p.setColor(j == 1 ? (lum > 1700 ? 0xFF1C1C1E : 0xFFF5F5F7) : 0x99FFFFFF);
                        cv.drawRoundRect(r.left + u * 1.6f, y + h * 0.4f, r.left + u * 6, y + h * 0.6f, h / 10, h / 10, p);
                    }
                }, i -> {
                    LiveTheme.set(c, i);
                    restyled(c);
                });
                return true;
            }
            case CUSTOM_BG_ROW: {
                String[] labels = new String[LiveTheme.BG_COUNT];
                for (int i = 0; i < labels.length; i++) labels[i] = LiveTheme.bgName(i);
                LiveChooser.show(c, rowLabel(id), labels, LiveTheme.customBg(), 3, 16 / 9f,
                        (cv, r, i) -> LiveTheme.drawBgSample(cv, r, i, p),
                        i -> {
                            LiveTheme.setCustom(c, i, LiveTheme.customRound(), LiveTheme.customLayout());
                            restyled(c);
                        });
                return true;
            }
            case CUSTOM_ROUND_ROW: {
                String[] labels = new String[LiveTheme.ROUND_COUNT];
                for (int i = 0; i < labels.length; i++) labels[i] = LiveTheme.roundName(i);
                LiveChooser.show(c, rowLabel(id), labels, LiveTheme.customRound(), 3, 16 / 9f, (cv, r, i) -> {
                    p.setColor(LiveTheme.bg);
                    cv.drawRect(r, p);
                    float u = r.width() / 10, rad = 10 * LiveTheme.roundOf(i) * r.width() / 480;
                    p.setColor(LiveTheme.surface);
                    cv.drawRoundRect(r.left + u, r.top + u, r.left + u * 4.6f, r.bottom - u, rad, rad, p);
                    p.setColor(LiveTheme.tile);
                    cv.drawRoundRect(r.left + u * 5.4f, r.top + u, r.right - u, r.bottom - u, rad, rad, p);
                }, i -> {
                    LiveTheme.setCustom(c, LiveTheme.customBg(), i, LiveTheme.customLayout());
                    restyled(c);
                });
                return true;
            }
            case CC_SIZE_ROW:
            case CC_STYLE_ROW:
            case CC_COLOR_ROW: {
                // Captions: each choice as a sample line, as it would look over the picture.
                final int n = id == CC_SIZE_ROW ? LiveOptions.CC_SIZES.length : id == CC_COLOR_ROW ? LiveOptions.CC_COLORS.length : LiveOptions.CC_STYLES;
                String[] labels = new String[n];
                int now = id == CC_SIZE_ROW ? LiveOptions.ccSize(c) : id == CC_COLOR_ROW ? LiveOptions.ccColor(c) : LiveOptions.ccStyle(c);
                for (int i = 0; i < n; i++) labels[i] = ccName(id, i);
                LiveChooser.show(c, rowLabel(id), labels, now, Math.min(4, n), 16 / 9f, (cv, r, i) -> {
                    int size = id == CC_SIZE_ROW ? i : LiveOptions.ccSize(c), color = id == CC_COLOR_ROW ? i : LiveOptions.ccColor(c);
                    int style = id == CC_STYLE_ROW ? i : LiveOptions.ccStyle(c);
                    p.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, 0xFF3A4A5C, 0xFF1A2230, android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRect(r, p);
                    p.setShader(null);
                    float s = r.width() / 1920 * 2.2f;
                    android.text.TextPaint t = new android.text.TextPaint(Ui.text(Ui.medium, LiveOptions.CC_SIZES[size] * s, LiveOptions.CC_COLORS[color]));
                    t.setTextAlign(Paint.Align.CENTER);
                    String sample = Ui.tr("Legendas assim", "Captions like this");
                    float tw = t.measureText(sample), cy = r.bottom - r.height() * 0.28f;
                    if (style == LiveOptions.CC_BAND) {
                        p.setColor(0xB3000000);
                        cv.drawRect(r.left, cy - t.getTextSize(), r.right, cy + t.getTextSize() * 0.4f, p);
                    } else if (style == LiveOptions.CC_BOX) {
                        p.setColor(0xFF000000);
                        cv.drawRect(r.centerX() - tw / 2 - 8 * s, cy - t.getTextSize(), r.centerX() + tw / 2 + 8 * s, cy + t.getTextSize() * 0.4f, p);
                    } else {
                        t.setStyle(Paint.Style.STROKE);
                        t.setStrokeWidth(t.getTextSize() * 0.12f);
                        int keep = t.getColor();
                        t.setColor(0xFF000000);
                        cv.drawText(sample, r.centerX(), cy, t);
                        t.setStyle(Paint.Style.FILL);
                        t.setColor(keep);
                    }
                    cv.drawText(sample, r.centerX(), cy, t);
                }, i -> {
                    int size = LiveOptions.ccSize(c), color = LiveOptions.ccColor(c), style = LiveOptions.ccStyle(c);
                    if (id == CC_SIZE_ROW) size = i; else if (id == CC_COLOR_ROW) color = i; else style = i;
                    LiveOptions.setCaptions(c, size, color, style);
                    view.captionsChanged();
                    announce();
                });
                return true;
            }
            default:
                return words(c, id);
        }
    }

    /** A caption setting's choice `i`, in words. */
    private static String ccName(int id, int i) {
        if (id == CC_SIZE_ROW) return i == 0 ? Ui.tr("Pequeno", "Small") : i == 1 ? Ui.tr("Médio", "Medium") : i == 2 ? Ui.tr("Grande", "Large")
                : Ui.tr("Muito grande", "Extra large");
        if (id == CC_COLOR_ROW) return i == 0 ? Ui.tr("Branco", "White") : i == 1 ? Ui.tr("Amarelo", "Yellow") : Ui.tr("Ciano", "Cyan");
        return i == LiveOptions.CC_BAND ? Ui.tr("Faixa escura", "Dark band") : i == LiveOptions.CC_BOX ? Ui.tr("Caixa preta", "Black box")
                : Ui.tr("Contorno", "Outline");
    }

    /**
     * The settings with a few choices that are words or numbers (the format, the buffer, how often, how many, how
     * long…): all of them as tiles. On/off ones stay a press of OK. False for the rest.
     */
    private boolean words(final Context c, final int id) {
        String[] labels;
        int now;
        final int[] values;
        switch (id) {
            case FORMAT_ROW:
                labels = new String[] {Ui.tr("Automático", "Automatic"), "MPEG-TS", "HLS"};
                now = Math.max(0, java.util.Arrays.asList(FORMATS).indexOf(LiveOptions.format(c)));
                values = null;
                break;
            case BUFFER_ROW:
                labels = new String[] {Ui.tr("Normal", "Normal"), Ui.tr("Grande", "Large"), Ui.tr("Extra grande", "Extra large")};
                now = LiveOptions.buffer(c);
                values = null;
                break;
            case CHANNEL_SORT_ROW:
                labels = new String[] {Ui.tr("Ordem do provedor", "Provider's order"), Ui.tr("Por número", "By number"), Ui.tr("Por nome (A–Z)", "By name (A–Z)")};
                now = LiveOptions.channelSort(c);
                values = null;
                break;
            case CATEGORY_SORT_ROW:
                labels = new String[] {Ui.tr("Ordem do provedor", "Provider's order"), Ui.tr("Por nome (A–Z)", "By name (A–Z)")};
                now = LiveOptions.categorySort(c) == LiveOptions.BY_NAME ? 1 : 0;
                values = null;
                break;
            case REMIND_AUTO_ROW:
                labels = new String[] {Ui.tr("Perguntar", "Ask"), Ui.tr("Mudar de canal sozinho", "Switch automatically")};
                now = LiveOptions.remindAuto(c) ? 1 : 0;
                values = null;
                break;
            case HOME_ROW:
                labels = new String[] {Ui.tr("Último canal", "Last channel"), Ui.tr("Início", "Home")};
                now = LiveOptions.homeOnStart(c) ? 1 : 0;
                values = null;
                break;
            case MATCH_ROW:
                labels = new String[] {Ui.tr("Só ao vivo", "Live events only"), Ui.tr("Todas as exibições", "All airings")};
                now = LiveOptions.followAll(c) ? 1 : 0;
                values = null;
                break;
            case GUIDE_SIZE_ROW:
                labels = new String[] {Ui.tr("Confortável", "Comfortable"), Ui.tr("Compacto", "Compact")};
                now = LiveOptions.guideCompact(c) ? 1 : 0;
                values = null;
                break;
            case MATCH_STYLE_ROW:
                labels = new String[] {Ui.tr("Todos os canais", "Every channel"), Ui.tr("Um aviso", "A card")};
                now = LiveOptions.matchCard(c) ? 1 : 0;
                values = null;
                break;
            case GUIDE_HOURS_ROW: values = LiveOptions.GUIDE_HOURS; now = LiveOptions.guideHours(c); labels = null; break;
            case PAST_DAYS_ROW: values = LiveOptions.PAST_DAYS; now = LiveOptions.pastDays(c); labels = null; break;
            case DAYS_AHEAD_ROW: values = LiveOptions.DAYS_AHEAD; now = LiveOptions.daysAhead(c); labels = null; break;
            case GUIDE_SHIFT_ROW: values = LiveOptions.GUIDE_SHIFTS; now = LiveOptions.guideShiftMinutes(c); labels = null; break;
            case REMIND_LEAD_ROW: values = LiveOptions.REMIND_LEAD; now = LiveOptions.remindLead(c); labels = null; break;
            case RECORD_EARLY_ROW: values = LiveOptions.RECORD_EARLY; now = LiveOptions.recordEarly(c); labels = null; break;
            case RECORD_LATE_ROW: values = LiveOptions.RECORD_LATE; now = LiveOptions.recordLate(c); labels = null; break;
            case HISTORY_ROW: values = LiveOptions.HISTORY_MAX; now = LiveOptions.historyMax(c); labels = null; break;
            case OPACITY_ROW: values = LiveOptions.PANEL_OPACITY; now = LiveOptions.panelOpacity(c); labels = null; break;
            case AGE_ROW: values = LiveOptions.AGE_LOCKS; now = LiveOptions.ageLock(c); labels = null; break;
            default:
                return false;
        }
        if (values != null) {       // numbers: each one worded as the row words it, the one in use found by value
            labels = new String[values.length];
            int at = 0;
            for (int i = 0; i < values.length; i++) {
                labels[i] = numberName(id, values[i]);
                if (values[i] == now) at = i;
            }
            now = at;
        }
        LiveChooser.showWords(c, rowLabel(id), labels, details(id), now, false, i -> {
            switch (id) {
                case FORMAT_ROW: LiveOptions.setFormat(c, FORMATS[i]); live.playbackChanged(); break;
                case BUFFER_ROW: LiveOptions.setBuffer(c, i); live.playbackChanged(); break;
                case CHANNEL_SORT_ROW: LiveOptions.setChannelSort(c, i); view.sortChanged(); break;
                case CATEGORY_SORT_ROW: LiveOptions.setCategorySort(c, i == 1 ? LiveOptions.BY_NAME : LiveOptions.PROVIDER); view.sortChanged(); break;
                case REMIND_AUTO_ROW: LiveOptions.setRemind(c, LiveOptions.remindLead(c), i == 1); break;
                case HOME_ROW: LiveOptions.setHomeOnStart(c, i == 1); break;
                case MATCH_ROW: setMatchAlerts(c, i == 1); break;
                case MATCH_STYLE_ROW: LiveOptions.setMatchCard(c, i == 1); break;
                case GUIDE_SIZE_ROW: sizeGuide(c, i == 1); break;
                case GUIDE_HOURS_ROW: LiveOptions.setGuideHours(c, values[i]); break;
                case PAST_DAYS_ROW: LiveOptions.setPastDays(c, values[i]); break;
                case DAYS_AHEAD_ROW: LiveOptions.setDaysAhead(c, values[i]); break;
                case GUIDE_SHIFT_ROW: shiftGuide(c, values[i]); break;
                case REMIND_LEAD_ROW:
                    LiveOptions.setRemind(c, values[i], LiveOptions.remindAuto(c));
                    LiveSchedule.armAll(c);
                    break;
                case RECORD_EARLY_ROW:
                    LiveOptions.setRecordPadding(c, values[i], LiveOptions.recordLate(c));
                    LiveSchedule.armAll(c);     // the recordings set: their alarms that much earlier
                    break;
                case RECORD_LATE_ROW: LiveOptions.setRecordPadding(c, LiveOptions.recordEarly(c), values[i]); break;
                case HISTORY_ROW:
                    LiveOptions.setHistoryMax(c, values[i]);
                    Xtream.trimRecent(c);
                    view.historyChanged();
                    break;
                case OPACITY_ROW:
                    LiveOptions.setPanelOpacity(c, values[i]);
                    opacity = LiveOptions.panelOpacity(c);
                    break;
                default: {      // AGE_ROW: a lock needs the parents' PIN; set one first when there's none
                    final int age = values[i];
                    if (age > 0 && !LiveOptions.hasPin(c)) {
                        view.askNewPin(() -> {
                            LiveOptions.setAgeLock(context(), age);
                            announce();
                            view.invalidate();
                        });
                        return;
                    }
                    LiveOptions.setAgeLock(c, age);
                    break;
                }
            }
            announce();
            view.invalidate();
        });
        return true;
    }

    /** What each choice of a words setting does (null when the names say it all). */
    private static String[] details(int id) {
        switch (id) {
            case FORMAT_ROW: return new String[] {Ui.tr("O que o seu provedor prefere", "What your provider prefers"),
                    Ui.tr("Estável, funciona com quase todos", "Steady, works with most providers"),
                    Ui.tr("Ajusta-se à internet; alguns canais demoram mais", "Adapts to your internet; some channels start slower")};
            case BUFFER_ROW: return new String[] {Ui.tr("Começa mais rápido", "Starts fastest"),
                    Ui.tr("Mais estável numa internet lenta", "Steadier on slow internet"),
                    Ui.tr("O mais estável; alguns segundos a mais para começar", "Steadiest; a few seconds slower to start")};
            case CHANNEL_SORT_ROW: return new String[] {Ui.tr("Como o provedor lista", "As your provider lists them"),
                    "1, 2, 3…", Ui.tr("De A a Z", "A to Z")};
            case CATEGORY_SORT_ROW: return new String[] {Ui.tr("Como o provedor lista", "As your provider lists them"), Ui.tr("De A a Z", "A to Z")};
            case REMIND_AUTO_ROW: return new String[] {Ui.tr("Um aviso pergunta antes de mudar de canal", "A card asks before changing channel"),
                    Ui.tr("Muda de canal sozinho quando começa", "Changes channel by itself when it starts")};
            case MATCH_ROW: return new String[] {Ui.tr("Um lembrete quando o jogo passa ao vivo", "A reminder when the game is on live"),
                    Ui.tr("Também nas reprises e destaques", "For replays and highlights too")};
            case GUIDE_SIZE_ROW: return new String[] {Ui.tr("Linhas maiores, fáceis de ler de longe", "Bigger rows, easy to read from afar"),
                    Ui.tr("Linhas menores: mais canais na tela", "Smaller rows: more channels on screen")};
            case MATCH_STYLE_ROW: return new String[] {Ui.tr("Um painel com cada canal que mostra o jogo agora, para escolher",
                    "A panel with each channel showing the match now, to pick one"),
                    Ui.tr("Um aviso com o canal do jogo, como os lembretes", "A card with the match's channel, as reminders are")};
            case HOME_ROW: return new String[] {Ui.tr("Abre no que você assistia por último", "Opens on what you watched last"),
                    Ui.tr("Abre na página Início da TV ao vivo", "Opens on Live TV's Home page")};
            default: return null;
        }
    }

    /** A number setting's value `v` in words, as its row shows it. */
    private static String numberName(int id, int v) {
        switch (id) {
            case GUIDE_HOURS_ROW: return v + " h";
            case PAST_DAYS_ROW: return v == 0 ? Ui.tr("Nenhum", "None") : v + " " + (v == 1 ? Ui.tr("dia", "day") : Ui.tr("dias", "days"));
            case DAYS_AHEAD_ROW: return v + " " + (v == 1 ? Ui.tr("dia", "day") : Ui.tr("dias", "days"));
            case GUIDE_SHIFT_ROW: return LiveOptions.shiftName(v);
            case REMIND_LEAD_ROW: return v == 0 ? Ui.tr("Na hora", "As it starts") : v + " min";
            case RECORD_EARLY_ROW: return v == 0 ? Ui.tr("Na hora", "On time") : v + Ui.tr(" min antes", " min early");
            case RECORD_LATE_ROW: return v == 0 ? Ui.tr("Até o fim", "Until the end") : v + Ui.tr(" min depois", " min after");
            case OPACITY_ROW: return v >= 100 ? Ui.tr("Sólido", "Solid") : v + "%";
            case AGE_ROW: return v <= 0 ? Ui.tr("Desligado", "Off") : v + "+";
            default: return String.valueOf(v);
        }
    }

    /** A theme choice made: lettering, layout and colors again, and the row reads it out. */
    private void restyled(Context c) {
        LiveTheme.applyFonts(c);
        view.restyle();
        announce();
    }

    /**
     * Search: a few letters, then the settings whose name (or section's) has them; one goes straight there, several
     * are listed ("Section  ›  Setting") to pick from.
     */
    private void search() {
        final Context c = context();
        LiveNamePad.ask(c, Ui.tr("Buscar nos ajustes", "Search settings"), "", typed -> {
            String q = Xtream.fold(typed);
            if (q.isEmpty()) return;
            final List<Integer> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int s = 0; s < SECTION_ROWS.length; s++) {
                for (int id : rowsOf(s)) {
                    if (id == RESET_ROW || ids.contains(id)) continue;
                    String label = rowLabel(id);
                    if (!Xtream.fold(label).contains(q) && !Xtream.fold(sectionName(s)).contains(q)) continue;
                    ids.add(id);
                    names.add(sectionName(s) + "  ›  " + label);
                }
            }
            if (ids.isEmpty()) {
                view.flash(Ui.tr("Nenhum ajuste com esse nome", "No setting by that name"));
                return;
            }
            onSearch = false;
            if (ids.size() == 1) {
                showRow(ids.get(0));
                view.invalidate();
                return;
            }
            LiveSheet.menu(c).asList()
                    .setTitle(Ui.tr("Ajustes encontrados", "Settings found"))
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        showRow(ids.get(which));
                        view.invalidate();
                    })
                    .show();
        });
    }

    /** Reset this section: asked first, then its settings as they first were. */
    /**
     * A new guide offset: the channels' saved guides (moved by the old one) are dropped and fetched again, and the
     * guide redrawn. Reminders and recordings already set keep their times.
     */
    /** Compact or comfortable rows: the layout again, at once. */
    private void sizeGuide(Context c, boolean compact) {
        LiveOptions.setGuideCompact(c, compact);
        LiveView.compact = compact;
        view.restyle();
    }

    private void shiftGuide(Context c, int minutes) {
        LiveOptions.setGuideShift(c, minutes);
        Xtream.clearGuides(c);
        view.guideReplaced();
    }

    private void askReset(final Context c, final int s) {
        LiveSheet.menu(c)
                .setTitle(sectionName(s))
                .setMessage(Ui.tr("Os ajustes desta seção voltam a como eram. O PIN, as contas e as listas ficam.",
                        "This section's settings go back to how they first were. Your PIN, accounts and lists stay."))
                .setIcons("history", "close")
                .setItems(new String[] {Ui.tr("Voltar ao padrão", "Reset"), Ui.tr("Cancelar", "Cancel")}, (d, which) -> {
                    if (which != 0) return;
                    reset(c, s);
                    view.flash(Ui.tr("Seção de volta ao padrão", "Section reset"));
                    view.invalidate();
                })
                .show();
    }

    private void reset(Context c, int s) {
        switch (s) {
            case 0:     // Playback
                LiveOptions.forget(c, "format", "buffer", "frameRate", "passthrough", "audioLanguage", "tunneling", "merge");
                live.playbackChanged();
                live.relist();
                break;
            case 1:     // Channels and categories
                LiveOptions.forget(c, "channelSort", "categorySort");
                LiveOptions.setCountryTags(c, true);
                LiveOptions.setVoiceChannels(c, true);
                view.sortChanged();
                break;
            case 2:     // Guide
                LiveOptions.forget(c, "guideHours", "pastDays", "fullGuide", "daysAhead", "guideOnStart");
                if (LiveOptions.guideShiftMinutes(c) != 0) shiftGuide(c, 0);
                if (LiveOptions.guideCompact(c)) sizeGuide(c, false);
                view.guideReplaced();
                break;
            case 3:     // Theme and start-up
                LiveTheme.resetAll(c);
                LiveOptions.forget(c, "panelOpacity", "homeOnStart");
                LiveOptions.setStartOnBoot(c, false);
                LiveOptions.setStartOnWake(c, false);
                LiveOptions.setHomePreviews(c, true);
                opacity = LiveOptions.panelOpacity(c);
                LiveTheme.applyFonts(c);
                view.restyle();
                break;
            case REMINDERS_SECTION:
                LiveOptions.setRemind(c, 1, false);
                setMatchAlerts(c, false);
                LiveOptions.setMatchCard(c, false);
                LiveOptions.setRemindWatching(c, false);
                LiveOptions.setScores(c, true);
                LiveOptions.setGoalAlerts(c, true);
                LiveSchedule.armAll(c);
                break;
            case RECORDINGS_SECTION:     // the recordings' times (never the recordings)
                LiveOptions.setRecordPadding(c, 0, 2);
                LiveSchedule.armAll(c);
                break;
            case 7:     // Parental controls: the age lock (never the PIN)
                LiveOptions.forget(c, "ageLock");
                break;
            case 8:     // Profiles and account: only the options
                LiveOptions.forget(c, "askProfile", "historyMax");
                view.historyChanged();
                break;
            default:    // Captions
                LiveOptions.forget(c, "ccSize", "ccColor", "ccStyle");
                live.setSubtitlesOn(false);
                view.captionsChanged();
                break;
        }
    }

    /** Stream health: the channels with the most trouble this week (OK watches one), and Clear. */
    private void healthList(final Context c) {
        final List<LiveHealth.Entry> list = LiveHealth.week(c);
        if (list.isEmpty()) {
            view.flash(Ui.tr("Nenhum canal com problemas nos últimos 7 dias", "No channel had trouble in the last 7 days"));
            return;
        }
        int n = Math.min(10, list.size());
        String[] items = new String[n + 1], icons = new String[n + 1];
        for (int i = 0; i < n; i++) {
            LiveHealth.Entry e = list.get(i);
            items[i] = e.name + "  —  " + LiveHealth.describe(e);
            icons[i] = "play";
        }
        items[n] = Ui.tr("Limpar a lista", "Clear the list");
        icons[n] = "delete";
        LiveSheet.menu(c)
                .asList()
                .setTitle(Ui.tr("Saúde dos canais", "Stream health"))
                .setMessage(Ui.tr("Últimos 7 dias, os com mais problemas primeiro. OK assiste ao canal.",
                        "The last 7 days, the most trouble first. OK watches the channel."))
                .setIcons(icons)
                .setItems(items, (d, which) -> {
                    if (which == n) {
                        LiveHealth.clear(c);
                        view.flash(Ui.tr("Lista limpa", "List cleared"));
                        view.invalidate();
                        return;
                    }
                    Xtream.Lineup l = view.lineup();
                    Xtream.Channel ch = l == null ? null : l.find(list.get(which).id);
                    if (ch == null) {
                        view.flash(Ui.tr("Esse canal não está mais na lista", "That channel isn't in the list any more"));
                        return;
                    }
                    view.closeSettings();
                    live.play(ch);
                })
                .show();
    }

    private void cycle(int dir) {
        Context c = context();
        int focus = id();
        switch (focus) {
            case FRAME_RATE_ROW: LiveOptions.setFrameRate(c, !LiveOptions.frameRate(c)); live.playbackChanged(); announce(); return;
            case PASSTHROUGH_ROW: LiveOptions.setPassthrough(c, !LiveOptions.passthrough(c)); live.playbackChanged(); announce(); return;
            case AUDIO_LANG_ROW:
                if (!LiveOptions.audioLanguage(c).isEmpty()) {
                    live.clearAudioLanguage();
                    announce();
                }
                return;
            case TUNNELING_ROW: LiveOptions.setTunneling(c, !LiveOptions.tunneling(c)); live.playbackChanged(); announce(); return;
            case MERGE_ROW: LiveOptions.setMerge(c, !LiveOptions.merge(c)); live.relist(); announce(); return;
            case COUNTRY_ROW: LiveOptions.setCountryTags(c, !LiveOptions.countryTags(c)); view.invalidate(); announce(); return;
            case VOICE_ROW: LiveOptions.setVoiceChannels(c, !LiveOptions.voiceChannels(c)); announce(); return;
            case RECORD_EARLY_ROW:
                LiveOptions.setRecordPadding(c, LiveOptions.step(LiveOptions.RECORD_EARLY, LiveOptions.recordEarly(c), dir), LiveOptions.recordLate(c));
                LiveSchedule.armAll(c);
                announce();
                return;
            case RECORD_LATE_ROW:
                LiveOptions.setRecordPadding(c, LiveOptions.recordEarly(c), LiveOptions.step(LiveOptions.RECORD_LATE, LiveOptions.recordLate(c), dir));
                announce();
                return;
            case REMIND_LEAD_ROW:
                LiveOptions.setRemind(c, LiveOptions.step(LiveOptions.REMIND_LEAD, LiveOptions.remindLead(c), dir), LiveOptions.remindAuto(c));
                LiveSchedule.armAll(c);      // the alarms move with it
                announce();
                return;
            case REMIND_AUTO_ROW: LiveOptions.setRemind(c, LiveOptions.remindLead(c), !LiveOptions.remindAuto(c)); announce(); return;
            case HISTORY_ROW:
                LiveOptions.setHistoryMax(c, LiveOptions.step(LiveOptions.HISTORY_MAX, LiveOptions.historyMax(c), dir));
                Xtream.trimRecent(c);
                view.historyChanged();
                announce();
                return;
            case CC_ON_ROW:
                live.setSubtitlesOn(!LiveOptions.subtitles(c));
                announce();
                return;
            case CC_SIZE_ROW:
            case CC_STYLE_ROW:
            case CC_COLOR_ROW: {
                int size = LiveOptions.ccSize(c), color = LiveOptions.ccColor(c), style = LiveOptions.ccStyle(c);
                if (focus == CC_SIZE_ROW) size = (size + dir + LiveOptions.CC_SIZES.length) % LiveOptions.CC_SIZES.length;
                else if (focus == CC_COLOR_ROW) color = (color + dir + LiveOptions.CC_COLORS.length) % LiveOptions.CC_COLORS.length;
                else style = (style + dir + LiveOptions.CC_STYLES) % LiveOptions.CC_STYLES;
                LiveOptions.setCaptions(c, size, color, style);
                view.captionsChanged();
                announce();
                return;
            }
            case OPACITY_ROW:
                LiveOptions.setPanelOpacity(c, LiveOptions.step(LiveOptions.PANEL_OPACITY, LiveOptions.panelOpacity(c), dir));
                opacity = LiveOptions.panelOpacity(c);
                announce();
                return;
            case THEME_ROW:
                if (!ownColor()) {
                    view.flash(Ui.tr("Escolha o estilo Glassio ou Personalizado para escolher uma cor", "Pick the Glassio or Custom style to choose a color"));
                    return;
                }
                LiveTheme.set(c, (LiveTheme.current() + dir + LiveTheme.COUNT) % LiveTheme.COUNT);
                view.restyle();
                announce();
                return;
            case STYLE_ROW:
                LiveTheme.setStyle(c, LiveTheme.step(LiveTheme.style(), dir, false));
                LiveTheme.applyFonts(c);
                view.restyle();
                announce();
                return;
            case CUSTOM_BG_ROW:
                LiveTheme.setCustom(c, (LiveTheme.customBg() + dir + LiveTheme.BG_COUNT) % LiveTheme.BG_COUNT,
                        LiveTheme.customRound(), LiveTheme.customLayout());
                view.restyle();
                announce();
                return;
            case CUSTOM_ROUND_ROW:
                LiveTheme.setCustom(c, LiveTheme.customBg(), (LiveTheme.customRound() + dir + LiveTheme.ROUND_COUNT) % LiveTheme.ROUND_COUNT,
                        LiveTheme.customLayout());
                view.restyle();
                announce();
                return;
            case CUSTOM_LAYOUT_ROW:
                LiveTheme.setCustom(c, LiveTheme.customBg(), LiveTheme.customRound(),
                        (LiveTheme.customLayout() + dir + LiveTheme.CUSTOM) % LiveTheme.CUSTOM);
                LiveTheme.applyFonts(c);
                view.restyle();
                announce();
                return;
            case MORNING_ROW: {
                LiveTheme.setMorning(c, LiveTheme.step(LiveTheme.morningStyle(), dir, true));
                LiveTheme.applyFonts(c);
                view.restyle();
                announce();
                return;
            }
            case LATE_ROW: {
                LiveTheme.setLate(c, LiveTheme.step(LiveTheme.lateStyle(), dir, true));
                LiveTheme.applyFonts(c);
                view.restyle();
                announce();
                return;
            }
            case NIGHT_ROW: {       // the same, or one of the styles in the lists' order, round
                LiveTheme.setNight(c, LiveTheme.step(LiveTheme.nightStyle(), dir, true));
                LiveTheme.applyFonts(c);
                view.restyle();
                announce();
                return;
            }
            case GUIDE_HOURS_ROW: LiveOptions.setGuideHours(c, LiveOptions.step(LiveOptions.GUIDE_HOURS, LiveOptions.guideHours(c), dir)); announce(); return;
            case PAST_DAYS_ROW: LiveOptions.setPastDays(c, LiveOptions.step(LiveOptions.PAST_DAYS, LiveOptions.pastDays(c), dir)); announce(); return;
            case GUIDE_START_ROW: LiveOptions.setGuideOnStart(c, !LiveOptions.guideOnStart(c)); announce(); return;
            case FULL_GUIDE_ROW:
                LiveOptions.setFullGuide(c, !LiveOptions.fullGuide(c));
                if (LiveOptions.fullGuide(c)) live.updateGuide(false);
                view.guideReplaced();
                announce();
                return;
            case DAYS_AHEAD_ROW: LiveOptions.setDaysAhead(c, LiveOptions.step(LiveOptions.DAYS_AHEAD, LiveOptions.daysAhead(c), dir)); announce(); return;
            case GUIDE_SHIFT_ROW: shiftGuide(c, LiveOptions.step(LiveOptions.GUIDE_SHIFTS, LiveOptions.guideShiftMinutes(c), dir)); announce(); return;
            case BOOT_ROW: LiveOptions.setStartOnBoot(c, !LiveOptions.startOnBoot(c)); announce(); return;
            case WAKE_ROW: LiveOptions.setStartOnWake(c, !LiveOptions.startOnWake(c)); announce(); return;
            case HOME_ROW: LiveOptions.setHomeOnStart(c, !LiveOptions.homeOnStart(c)); announce(); return;
            case MATCH_ROW: setMatchAlerts(c, !LiveOptions.followAll(c)); announce(); return;
            case MATCH_STYLE_ROW: LiveOptions.setMatchCard(c, !LiveOptions.matchCard(c)); announce(); return;
            case GUIDE_SIZE_ROW: sizeGuide(c, !LiveOptions.guideCompact(c)); announce(); return;
            case REMIND_WATCHING_ROW: LiveOptions.setRemindWatching(c, !LiveOptions.remindWatching(c)); announce(); return;
            case SCORES_ROW: LiveOptions.setScores(c, !LiveOptions.scores(c)); announce(); return;
            case GOALS_ROW:
                if (LiveOptions.scores(c)) LiveOptions.setGoalAlerts(c, !LiveOptions.goalAlerts(c));
                announce();
                return;
            case AGE_ROW: {
                final int age = LiveOptions.step(LiveOptions.AGE_LOCKS, LiveOptions.ageLock(c), dir);
                if (age > 0 && !LiveOptions.hasPin(c)) {     // the lock needs a PIN: one now
                    view.askNewPin(() -> {
                        LiveOptions.setAgeLock(context(), age);
                        announce();
                        view.invalidate();
                    });
                    return;
                }
                LiveOptions.setAgeLock(c, age);
                if (age > 0 && !LiveOptions.fullGuide(c)) view.flash(Ui.tr("As classificações vêm do guia completo: ligue-o em Guia.",
                        "Ratings come from the full guide: turn it on in Guide."));
                announce();
                return;
            }
            case PREVIEW_ROW: LiveOptions.setHomePreviews(c, !LiveOptions.homePreviews(c)); announce(); return;
            default: break;
        }
        if (focus == CHANNEL_SORT_ROW) {
            LiveOptions.setChannelSort(c, (LiveOptions.channelSort(c) + dir + 3) % 3);
            view.sortChanged();
            announce();
            return;
        }
        if (focus == CATEGORY_SORT_ROW) {
            boolean byName = LiveOptions.categorySort(c) == LiveOptions.BY_NAME;
            LiveOptions.setCategorySort(c, byName ? LiveOptions.PROVIDER : LiveOptions.BY_NAME);
            view.sortChanged();
            announce();
            return;
        }
        if (focus == FORMAT_ROW) {
            int at = java.util.Arrays.asList(FORMATS).indexOf(LiveOptions.format(c));
            LiveOptions.setFormat(c, FORMATS[(Math.max(0, at) + dir + FORMATS.length) % FORMATS.length]);
        } else {
            LiveOptions.setBuffer(c, (LiveOptions.buffer(c) + dir + 3) % 3);
        }
        live.playbackChanged();
        announce();
    }

    private void activate() {
        Context c = context();
        if (screen == ROWS && choose(c, id())) return;
        if (screen == PLAYLISTS) {      // use it
            LivePlaylists.Playlist p = playlists.get(focus);
            if (p.id.equals(LivePlaylists.active(c))) {
                view.flash(Ui.tr("Esta já está em uso", "This one is already in use"));
                return;
            }
            LiveSheet.menu(c)
                    .setTitle(p.name())
                    .setIcons("play", "delete")
                    .setItems(new String[] {Ui.tr("Usar esta lista", "Use this playlist"), Ui.tr("Tirar esta lista", "Remove this playlist")}, (d, which) -> {
                        if (which == 0) {
                            view.closeSettings();
                            live.switchPlaylist(p.id);
                            return;
                        }
                        LivePlaylists.remove(c, p.id);
                        view.flash(Ui.tr("Lista tirada", "Playlist removed"));
                        playlists = LivePlaylists.all(c);
                        if (playlists.size() <= 1) showRow(PLAYLISTS_ROW);
                        else show(PLAYLISTS, Math.min(focus, playlists.size() - 1));
                    })
                    .show();
            return;
        }
        if (screen == FOLLOWING) {      // stop following it
            String term = following.get(focus);
            LiveFollow.remove(c, term);
            view.flash(Ui.tr("Deixou de seguir ", "Not following ") + term);
            following = LiveFollow.all(c);
            if (following.isEmpty()) showRow(FOLLOW_ROW);
            else show(FOLLOWING, Math.min(focus, following.size() - 1));
            return;
        }
        if (screen == SERIES) {     // stop it
            LiveSeries.remove(c, series.get(focus));
            view.flash(Ui.tr("Série parada", "Series stopped"));
            series = series(c, listRecordings);
            if (series.isEmpty()) showRow(listRecordings ? SERIES_RECORD_ROW : SERIES_ROW);
            else show(SERIES, Math.min(focus, series.size() - 1));
            return;
        }
        if (screen == SCHEDULE) {       // cancel it
            LiveSchedule.Item it = scheduled.get(focus);
            LiveSchedule.cancel(c, it.key());
            view.flash(LiveSchedule.reminder(it) ? Ui.tr("Lembrete cancelado", "Reminder cancelled")
                    : Ui.tr("Gravação cancelada", "Recording cancelled"));
            scheduled = scheduled(c, listRecordings);
            if (scheduled.isEmpty()) showRow(listRecordings ? RECORD_LIST_ROW : SCHEDULE_ROW);
            else show(SCHEDULE, Math.min(focus, scheduled.size() - 1));
            return;
        }
        if (screen == SEPARATE) {       // merged again
            String[] e = separate.get(focus);
            LiveOptions.setSeparate(c, e[0], e[1], false);
            view.flash(Ui.tr("Juntado de novo", "Merged again") + ": " + e[1]);
            live.relist();
            separate = LiveOptions.separate(c);
            if (separate.isEmpty()) showRow(SEPARATE_ROW);
            else show(SEPARATE, Math.min(focus, separate.size() - 1));
            return;
        }
        if (screen == HIDDEN) {
            Xtream.Category cat = view.providerCategories().get(focus);
            LiveOptions.setHidden(c, cat.id, !LiveOptions.hidden(c).contains(cat.id));
            view.hiddenChanged();
            announce();
            return;
        }
        if (screen == LOCKED) {
            Xtream.Category cat = view.providerCategories().get(focus);
            LiveOptions.setLocked(c, cat.id, !LiveOptions.locked(c).contains(cat.id));
            view.invalidate();
            announce();
            return;
        }
        if (screen == ORDER) {
            if (carrying) saveOrder();
            carrying = !carrying;
            announce();
            return;
        }
        if (screen == MAIN) {
            if (focus == KEYS_SECTION) {
                show(KEYS, 0);
            } else {
                // Back into the section just left: on the row it was left on.
                int at = focus == leftSection && leftRow < rowsOf(focus).length ? leftRow : 0;
                section = focus;
                show(ROWS, at);
            }
            return;
        }
        if (screen == KEYS) {
            cycleKey(1);
            return;
        }
        switch (id()) {
            case FORMAT_ROW:
            case BUFFER_ROW:
            case FRAME_RATE_ROW:
            case PASSTHROUGH_ROW:
            case AUDIO_LANG_ROW:
            case TUNNELING_ROW:
            case MERGE_ROW:
            case CHANNEL_SORT_ROW:
            case CATEGORY_SORT_ROW:
            case COUNTRY_ROW:
            case VOICE_ROW:
            case GUIDE_HOURS_ROW:
            case PAST_DAYS_ROW:
            case GUIDE_START_ROW:
            case FULL_GUIDE_ROW:
            case DAYS_AHEAD_ROW:
            case GUIDE_SHIFT_ROW:
            case BOOT_ROW:
            case WAKE_ROW:
            case HOME_ROW:
            case MATCH_ROW:
            case MATCH_STYLE_ROW:
            case REMIND_WATCHING_ROW:
            case SCORES_ROW:
            case GOALS_ROW:
            case GUIDE_SIZE_ROW:
            case AGE_ROW:
            case PREVIEW_ROW:
            case REMIND_LEAD_ROW:
            case RECORD_EARLY_ROW:
            case RECORD_LATE_ROW:
            case REMIND_AUTO_ROW:
            case THEME_ROW:
            case STYLE_ROW:
            case NIGHT_ROW:
            case CUSTOM_BG_ROW:
            case CUSTOM_ROUND_ROW:
            case CUSTOM_LAYOUT_ROW:
            case MORNING_ROW:
            case LATE_ROW:
            case OPACITY_ROW:
            case HISTORY_ROW:
            case CC_ON_ROW:
            case CC_SIZE_ROW:
            case CC_STYLE_ROW:
            case CC_COLOR_ROW:
                cycle(1);
                break;
            case GUIDE_SOURCE_ROW:
                askGuideUrl();
                break;
            case GUIDE_NOW_ROW:
                if (!LiveOptions.fullGuide(c)) view.flash(Ui.tr("Primeiro ligue o guia completo.", "Turn on the full guide first."));
                else if (!XmltvStore.running()) live.updateGuide(true);
                break;
            case FOLLOW_ROW:
                following = LiveFollow.all(c);
                if (following.isEmpty()) view.flash(Ui.tr("Nada seguido: busque um time ou programa e escolha Seguir.",
                        "Nothing followed yet: search for a team or a show and pick Follow."));
                else show(FOLLOWING, 0);
                break;
            case SERIES_ROW:
            case SERIES_RECORD_ROW:
                listRecordings = id() == SERIES_RECORD_ROW;
                series = series(c, listRecordings);
                if (series.isEmpty()) view.flash(Ui.tr("Nenhuma série: OK num programa futuro no guia → Todos os episódios…",
                        "No series yet: OK on a later show in the guide → Every episode…"));
                else show(SERIES, 0);
                break;
            case SCHEDULE_ROW:
            case RECORD_LIST_ROW:
                listRecordings = id() == RECORD_LIST_ROW;
                scheduled = scheduled(c, listRecordings);
                if (scheduled.isEmpty()) view.flash(Ui.tr("Nada marcado: segure OK num programa do guia.", "Nothing set: hold OK on a show in the guide."));
                else show(SCHEDULE, 0);
                break;
            case RECORDINGS_ROW:
                view.openRecordings();
                break;
            case SEPARATE_ROW:
                separate = LiveOptions.separate(c);
                if (!LiveOptions.merge(c)) view.flash(Ui.tr("Primeiro ligue Juntar canais parecidos.", "Turn on Merge similar channels first."));
                else if (separate.isEmpty()) view.flash(Ui.tr("Nenhum: segure OK num canal juntado no guia.", "None: hold OK on a merged channel in the guide."));
                else show(SEPARATE, 0);
                break;
            case HIDDEN_ROW:
                show(HIDDEN, 0);
                break;
            case CLEAR_HISTORY_ROW:
                if (Xtream.recent(c).isEmpty()) {
                    view.flash(Ui.tr("O Histórico já está vazio", "History is already empty"));
                    break;
                }
                LiveSheet.menu(c)
                        .setTitle(Ui.tr("Limpar o Histórico?", "Clear History?"))
                        .setIcons("delete", "close")
                        .setItems(new String[] {Ui.tr("Limpar", "Clear"), Ui.tr("Cancelar", "Cancel")}, (d, which) -> {
                            if (which != 0) return;
                            Xtream.clearRecent(c);
                            view.historyChanged();
                            view.flash(Ui.tr("Histórico limpo", "History cleared"));
                        })
                        .show();
                break;
            case EXPORT_ROW: {
                if (!LiveBackup.available()) break;
                final List<Xtream.Channel> favs = new ArrayList<>(favorites());
                new Thread(() -> {
                    String msg;
                    try {
                        LiveBackup.exportAll(c, favs);
                        msg = Ui.tr("Backup salvo em Downloads", "Backup saved to Downloads") + ": " + LiveBackup.FULL;
                    } catch (IOException | RuntimeException e) {
                        AppLog.w("live: backup: " + e.getMessage());
                        msg = Ui.tr("Não deu para fazer o backup", "Couldn't make the backup");
                    }
                    final String m = msg;
                    view.post(() -> view.flash(m));
                }, "backup").start();
                break;
            }
            case IMPORT_ROW: {
                Xtream.Lineup l = view.lineup();
                if (!LiveBackup.available() || l == null || l.categories.size() < 2) break;
                final List<Xtream.Channel> all = new ArrayList<>(l.categories.get(1).channels);
                LiveSheet.menu(c)
                        .setTitle(Ui.tr("Restaurar o backup?", "Restore the backup?"))
                        .setMessage(Ui.tr("Os ajustes, favoritos, perfis e lembretes de agora dão lugar aos do backup.",
                                "Your settings, favorites, profiles and reminders now are replaced by the backup's."))
                        .setIcons("history", "close")
                        .setItems(new String[] {Ui.tr("Restaurar", "Restore"), Ui.tr("Cancelar", "Cancel")}, (d, which) -> {
                            if (which != 0) return;
                            new Thread(() -> {
                                boolean ok;
                                try {
                                    ok = LiveBackup.restoreAll(c, all);
                                } catch (IOException | RuntimeException e) {
                                    AppLog.w("live: restore: " + e.getMessage());
                                    ok = false;
                                }
                                final boolean done = ok;
                                view.post(() -> {
                                    if (!done) {
                                        view.flash(Ui.tr("Nenhum backup em Downloads", "No backup in Downloads"));
                                        return;
                                    }
                                    view.flash(Ui.tr("Backup restaurado", "Backup restored"));
                                    live.restored();
                                });
                            }, "restore").start();
                        })
                        .show();
                break;
            }
            case ORDER_ROW:
                if (favorites().isEmpty()) view.flash(Ui.tr("Nenhum favorito ainda: segure OK num programa do guia.",
                        "No favorites yet: hold OK on a show in the guide."));
                else show(ORDER, 0);
                break;
            case PIN_ROW:
                if (!LiveOptions.hasPin(c)) {
                    view.askNewPin(() -> {
                        live.unlock();
                        view.flash(Ui.tr("PIN ligado", "PIN on"));
                    });
                } else {
                    view.askPin(this::pinChoices, null);
                }
                break;
            case LOCKED_ROW:
                if (!LiveOptions.hasPin(c)) view.flash(Ui.tr("Primeiro ligue o PIN dos pais.", "Turn on the parental PIN first."));
                else if (live.isUnlocked()) show(LOCKED, 0);
                else view.askPin(() -> { live.unlock(); show(LOCKED, 0); }, null);
                break;
            case HEALTH_ROW: {
                healthList(c);
                break;
            }
            case RESET_ROW:
                askReset(c, section);
                break;
            case CODE_ROW:      // another TV's style, by its code
                LivePinPad.askNumber(c, Ui.tr("Código do estilo", "Style code"), Ui.tr("Os 4 números de outra TV", "The 4 digits from another TV"), typed -> {
                    if (!LiveTheme.useCode(c, typed)) return false;
                    LiveTheme.applyFonts(c);
                    view.restyle();
                    showRow(CODE_ROW);      // Custom's rows came in above it: the focus stays on it
                    view.flash(Ui.tr("Estilo da outra TV aplicado", "The other TV's style is on"));
                    return true;
                });
                break;
            case SUBSCRIPTION_ROW: {
                view.flash(Ui.tr("Perguntando ao provedor…", "Asking your provider…"));
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    Xtream.Account a = Xtream.account(app);
                    view.post(() -> {
                        view.flash(a == null ? Ui.tr("O provedor não respondeu", "Your provider didn't answer") : Ui.tr("Assinatura atualizada", "Subscription checked"));
                        view.invalidate();
                    });
                }, "account").start();
                break;
            }
            case PROFILES_ROW:
                view.closeSettings();
                view.openProfiles();
                break;
            case ASK_PROFILE_ROW:
                LiveOptions.setAskProfile(c, !LiveOptions.askProfile(c));
                announce();
                break;
            case PLAYLISTS_ROW:
                playlists = LivePlaylists.all(c);
                if (playlists.size() <= 1) view.flash(Ui.tr("Só uma lista: Adicionar uma lista para mais", "Just one: Add a playlist for more"));
                else show(PLAYLISTS, 0);
                break;
            case ADD_PLAYLIST_ROW:
                view.closeSettings();
                live.addPlaylist();
                break;
            default:
                // With a PIN, signing out asks for it too (otherwise it'd be a way around the lock).
                if (LiveOptions.hasPin(c) && !live.isUnlocked()) view.askPin(() -> { live.unlock(); view.confirmSignOut(); }, null);
                else view.confirmSignOut();
                break;
        }
    }

    /** Match alerts set: the followed reminders made again for it (off the screen's thread). */
    private void setMatchAlerts(Context c, boolean all) {
        if (all == LiveOptions.followAll(c)) return;
        LiveOptions.setFollowAll(c, all);
        final Context app = c.getApplicationContext();
        new Thread(() -> LiveFollow.redo(app), "follow-redo").start();
    }

    private static String onOff(boolean on) {
        return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
    }

    /** The guide's link, typed with the TV's keyboard: empty for the provider's own. The full guide is fetched again. */
    private void askGuideUrl() {
        android.widget.EditText input = new android.widget.EditText(context());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…/guide.xml.gz");
        input.setText(LiveOptions.guideUrl(context()));
        LiveTextEntry.show(context(), Ui.tr("Fonte do guia", "Guide source"),
                Ui.tr("Um link XMLTV, ou vazio para o guia do provedor.", "An XMLTV link, or empty for your provider's guide."), input, typed -> {
                    String url = typed.trim();
                    if (!url.isEmpty() && !url.matches("(?i)^https?://.+")) {
                        view.flash(Ui.tr("O link precisa começar com http:// ou https://", "The link needs to start with http:// or https://"));
                        return;
                    }
                    LiveOptions.setGuideUrl(context(), url);
                    live.updateGuide(true);
                    view.invalidate();
                });
    }

    /** The PIN typed: change it, or turn the lock off. */
    private void pinChoices() {
        live.unlock();
        LiveSheet.menu(context())
                .setTitle(Ui.tr("PIN dos pais", "Parental PIN"))
                .setIcons("person", "close")
                .setItems(new String[] {Ui.tr("Trocar o PIN", "Change the PIN"), Ui.tr("Desligar o PIN", "Turn the PIN off")}, (d, which) -> {
                    if (which == 0) {
                        view.askNewPin(() -> view.flash(Ui.tr("PIN trocado", "PIN changed")));
                    } else {
                        LiveOptions.setPin(context(), null);
                        view.flash(Ui.tr("PIN desligado", "PIN off"));
                    }
                    view.invalidate();
                })
                .show();
    }

    /** The favorites as moved, in their file (the home screen reads it). */
    private void saveOrder() {
        final List<Xtream.Channel> list = new ArrayList<>(favorites());
        final Context app = context().getApplicationContext();
        new Thread(() -> Xtream.saveFavorites(app, list)).start();
    }

    /** TalkBack reads the focused row and its value. */
    private void announce() {
        if (count() == 0) return;
        view.announceForAccessibility(label(focus) + ", " + value(focus));
    }
}
