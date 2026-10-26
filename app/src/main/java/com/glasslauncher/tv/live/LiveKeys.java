package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What the remote's buttons do while watching (Live TV's settings, Remote buttons, TiviMate's way): each button and
 * its long press is a slot, each slot one action. The defaults are TiviMate's, with ← for the channel list.
 */
final class LiveKeys {
    private LiveKeys() {}

    static final int NONE = 0, CONTROLS = 1, INFO = 2, CHANNEL_LIST = 3, GROUPS = 4, GUIDE = 5, LAST = 6, NEXT_INFO = 7,
            PREV_INFO = 8, CH_UP = 9, CH_DOWN = 10, MENU = 11, HISTORY = 12, SEARCH = 13, RECORD = 14, START_OVER = 15,
            FAVORITE = 16, SETTINGS = 17, REWIND = 18, EXIT = 19, MULTIVIEW = 20, SLEEP = 21;

    /** The order ← → step through them in the settings. */
    static final int[] ACTIONS = {CONTROLS, INFO, CHANNEL_LIST, GROUPS, GUIDE, LAST, NEXT_INFO, PREV_INFO, CH_UP, CH_DOWN,
            MENU, HISTORY, SEARCH, RECORD, START_OVER, FAVORITE, SETTINGS, REWIND, MULTIVIEW, SLEEP, EXIT, NONE};

    static final String[] SLOTS = {"ok", "long_ok", "left", "long_left", "right", "long_right", "up", "long_up", "down",
            "long_down", "menu", "long_menu", "red", "green", "yellow", "blue"};

    private static int byDefault(String slot) {
        switch (slot) {
            case "ok": return CONTROLS;
            case "long_ok": return MENU;
            case "left":
            case "long_left": return CHANNEL_LIST;
            case "right":
            case "long_right": return LAST;
            case "up": return NEXT_INFO;
            case "long_up": return HISTORY;
            case "down": return PREV_INFO;
            case "long_down": return SEARCH;
            case "menu": return MENU;
            case "long_menu": return GUIDE;
            case "red": return GUIDE;
            case "green": return GROUPS;
            case "yellow": return CONTROLS;
            case "blue": return SETTINGS;
            default: return NONE;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("live-keys", Context.MODE_PRIVATE);
    }

    static int action(Context c, String slot) {
        return sp(c).getInt(slot, byDefault(slot));
    }

    static void set(Context c, String slot, int action) {
        sp(c).edit().putInt(slot, action).apply();
    }

    /** The slot of key `code` (held: its long press), or null for a key that isn't mapped. */
    static String slotOf(int code, boolean held) {
        String s;
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER: s = "ok"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: s = "left"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: s = "right"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: s = "up"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: s = "down"; break;
            case android.view.KeyEvent.KEYCODE_MENU: s = "menu"; break;
            case android.view.KeyEvent.KEYCODE_PROG_RED: return held ? null : "red";
            case android.view.KeyEvent.KEYCODE_PROG_GREEN: return held ? null : "green";
            case android.view.KeyEvent.KEYCODE_PROG_YELLOW: return held ? null : "yellow";
            case android.view.KeyEvent.KEYCODE_PROG_BLUE: return held ? null : "blue";
            default: return null;
        }
        return held ? "long_" + s : s;
    }

    static String slotName(String slot) {
        boolean held = slot.startsWith("long_");
        String key;
        switch (held ? slot.substring(5) : slot) {
            case "ok": key = "OK"; break;
            case "left": key = "◀"; break;
            case "right": key = "▶"; break;
            case "up": key = "▲"; break;
            case "down": key = "▼"; break;
            case "menu": key = Ui.tr("Menu", "Menu"); break;
            case "red": key = Ui.tr("Vermelho", "Red"); break;
            case "green": key = Ui.tr("Verde", "Green"); break;
            case "yellow": key = Ui.tr("Amarelo", "Yellow"); break;
            default: key = Ui.tr("Azul", "Blue"); break;
        }
        return held ? key + "  " + Ui.tr("(segurar)", "(hold)") : key;
    }

    static String actionName(int a) {
        switch (a) {
            case CONTROLS: return Ui.tr("Info e botões", "Info and buttons");
            case INFO: return Ui.tr("Info do programa", "Show info");
            case CHANNEL_LIST: return Ui.tr("Lista de canais", "Channels list");
            case GROUPS: return Ui.tr("Categorias", "Categories");
            case GUIDE: return Ui.tr("Guia de TV", "TV guide");
            case LAST: return Ui.tr("Último canal", "Last channel");
            case NEXT_INFO: return Ui.tr("Info do próximo canal", "Next channel's info");
            case PREV_INFO: return Ui.tr("Info do canal anterior", "Previous channel's info");
            case CH_UP: return Ui.tr("Próximo canal", "Next channel");
            case CH_DOWN: return Ui.tr("Canal anterior", "Previous channel");
            case MENU: return Ui.tr("Menu", "Menu");
            case HISTORY: return Ui.tr("Histórico", "History");
            case SEARCH: return Ui.tr("Buscar", "Search");
            case RECORD: return Ui.tr("Gravar", "Record");
            case START_OVER: return Ui.tr("Começar do início", "Start over");
            case FAVORITE: return Ui.tr("Favorito", "Favorite");
            case SETTINGS: return Ui.tr("Ajustes", "Settings");
            case REWIND: return Ui.tr("Voltar 10 s", "Back 10 s");
            case MULTIVIEW: return Ui.tr("Multivisão", "Multiview");
            case SLEEP: return Ui.tr("Timer para dormir", "Sleep timer");
            case EXIT: return Ui.tr("Sair da TV ao vivo", "Exit Live TV");
            default: return Ui.tr("Nada", "Nothing");
        }
    }
}
