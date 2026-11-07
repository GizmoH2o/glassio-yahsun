package com.glasslauncher.tv.live;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice beyond a channel's name (Live TV › Settings › Channels and categories › Voice commands): "Put the Arsenal game
 * on", "What's on tonight?", "Record this series", "Remind me about the Leeds game", in Live TV's languages. What was
 * said is read here (pure); LiveView finds the game in the guide and does it. Anything else stays a channel or a search.
 */
final class LiveVoiceCommand {
    private LiveVoiceCommand() {}

    static final int GAME = 1, REMIND_GAME = 2, RECORD_GAME = 3, TONIGHT = 4, TOMORROW = 5, NOW = 6, RECORD_THIS = 7,
            RECORD_SERIES = 8;

    static final class Command {
        final int kind;
        /** For a game: the team said (folded), and the other one when both were ("" for none). */
        final String team, other;

        Command(int kind, String team, String other) {
            this.kind = kind;
            this.team = team;
            this.other = other;
        }
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    // MARK: - What's on

    private static final String ASK = "^(what s|whats|what is|is there anything|anything|o que|que|was|qu est ce|cosa|что)\\b.*";
    private static final Pattern TONIGHT_P = p(ASK + "\\b(tonight|hoje a noite|hoje de noite|esta noche|heute abend|ce soir|stasera|сегодня вечером)\\b.*");
    private static final Pattern TOMORROW_P = p(ASK + "\\b(tomorrow night|tomorrow evening|amanha a noite|manana por la noche|morgen abend|demain soir|domani sera|завтра вечером)\\b.*");
    private static final Pattern NOW_P = p(ASK + "\\b(on now|on right now|right now|passando agora|agora|ahora|gerade|jetzt|maintenant|ora|adesso|сейчас)\\b.*");

    // MARK: - Recording

    private static final Pattern[] SERIES = {
            p("^(please )?record (this |the )?(whole )?(series|show s series)$"),
            p("^(please )?record (every|all|all the) episodes?$"),
            p("^(gravar|grava|grave) (esta|essa|a) serie$"),
            p("^(grabar|graba|graba me) (esta|la) serie$"),
            p("^(nimm )?(die|diese) serie auf(nehmen)?$"), p("^serie aufnehmen$"),
            p("^(enregistrer|enregistre) (cette|la) serie$"),
            p("^(registra|registrare) (questa|la) serie$"),
            p("^(запиши|записать|записывай) (этот |весь )?сериал$")};
    private static final Pattern[] THIS = {
            p("^(please )?(record|start recording)( this| it| this show| this programme| this program| now)?$"),
            p("^(gravar|grava|grave) (isso|isto|este programa|agora)$"),
            p("^(grabar|graba) (esto|este programa)$"),
            p("^nimm (das|es|diese sendung) auf$"),
            p("^(enregistrer|enregistre) (ca|cela|ce programme)$"),
            p("^(registra|registrare) (questo|questo programma)$"),
            p("^(запиши|записать) (это|эту передачу)$")};

    // MARK: - Games

    private static final Pattern REMIND = p("^(remind me (about|of|when)|lembre me (do|da|dos|das|de)|lembrar (do|da|de)|recuerdame (el|del|de)?|erinnere mich an|rappelle moi|ricordami) (.+)$");
    private static final Pattern RECORD = p("^(record|gravar|grava|grabar|graba|nimm|enregistre|enregistrer|registra|registrare|запиши|записать) (.+)$");
    /** Words before a game that mean "put it on", longest first. */
    private static final String[] LEAD = {"can you put on", "can you put", "i want to watch", "lets watch", "let s watch", "show me",
            "put on", "turn on", "switch to", "change to", "go to", "tune to", "put", "watch", "play", "coloca", "colocar",
            "bota", "assistir", "passa", "pon", "poner", "mira", "ver", "zeig mir", "zeige", "zeig", "schalte auf", "mets",
            "regarder", "montre", "metti", "guarda", "включи", "покажи"};
    private static final Pattern GAME_WORD = p("^(.+) (game|match|fight|race|fixture|matchup)$");
    private static final Pattern[] GAME_FIRST = {
            p("^(o )?jogo (do|da|dos|das|de) (.+)$"), p("^(el )?partido (del|de la|de) (.+)$"),
            p("^(das )?spiel (von|vom|der|des) (.+)$"), p("^(le )?match (de la|du|des|de) (.+)$"),
            p("^(la )?partita (del|della|dell|dello|di) (.+)$"), p("^матч (.+)$")};
    private static final Pattern VERSUS = p("^(.+?) (v|vs|versus|against|x|contra|gegen|contre|contro|против) (.+)$");
    private static final String[] TRAIL = {" on", " now", " today", " tonight", " live", " please", " for me", " again"};

    /** What was said, as a command; null when it's none (a channel's name, a search). Pure. */
    static Command parse(String said) {
        String f = Xtream.fold(said);
        if (f.isEmpty()) return null;
        if (TOMORROW_P.matcher(f).matches()) return new Command(TOMORROW, "", "");
        if (TONIGHT_P.matcher(f).matches()) return new Command(TONIGHT, "", "");
        if (NOW_P.matcher(f).matches()) return new Command(NOW, "", "");
        for (Pattern s : SERIES) if (s.matcher(f).matches()) return new Command(RECORD_SERIES, "", "");
        for (Pattern s : THIS) if (s.matcher(f).matches()) return new Command(RECORD_THIS, "", "");
        Matcher m;
        if ((m = REMIND.matcher(f)).matches()) return game(REMIND_GAME, m.group(m.groupCount()), true);
        if ((m = RECORD.matcher(f)).matches()) return game(RECORD_GAME, m.group(2), true);
        for (String lead : LEAD) {
            if (f.startsWith(lead + " ")) return game(GAME, f.substring(lead.length() + 1), true);
        }
        return game(GAME, f, false);
    }

    /** `rest` as a game of `kind`: "the Arsenal game", "Leeds v Spurs" (said after a word like "watch": `led`). */
    private static Command game(int kind, String rest, boolean led) {
        String r = tidy(rest);
        Matcher m;
        boolean named = false;
        String teams = null;
        if ((m = GAME_WORD.matcher(r)).matches()) {
            teams = tidy(m.group(1));
            named = true;
        } else {
            for (Pattern g : GAME_FIRST) {
                if ((m = g.matcher(r)).matches()) {
                    teams = tidy(m.group(m.groupCount()));
                    named = true;
                    break;
                }
            }
        }
        if (teams == null) teams = r;
        String team = teams, other = "";
        if ((m = VERSUS.matcher(teams)).matches()) {
            team = tidy(m.group(1));
            other = tidy(m.group(3));
        } else if (!named) {
            return null;        // neither "game" nor "v": a channel or a show
        }
        if (!named && !led) return null;
        if (team.isEmpty() || STOP.contains(team)) return null;
        return new Command(kind, team, other);
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList("the", "this", "that", "a",
            "it", "o", "el", "das", "le", "la"));

    private static String tidy(String s) {
        String t = s.trim();
        boolean again = true;
        while (again) {
            again = false;
            for (String tr : TRAIL) {
                if (t.endsWith(tr) && t.length() > tr.length()) {
                    t = t.substring(0, t.length() - tr.length()).trim();
                    again = true;
                }
            }
            for (String lead : new String[] {"on ", "the ", "o ", "a ", "el ", "la ", "le ", "das "}) {
                if (t.startsWith(lead) && t.length() > lead.length()) {
                    t = t.substring(lead.length()).trim();
                    again = true;
                }
            }
        }
        return t;
    }

    // MARK: - When

    /** The times {from, to} a "what's on" asks about: tonight 7 to 11 pm (what's left of it once begun), now. Pure. */
    static long[] window(int kind, long now, TimeZone tz) {
        if (kind == NOW) return new long[] {now, now + 1};
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (kind == TONIGHT && hour < 5) return new long[] {now, now + 3 * 3600_000L};     // after midnight: still tonight
        if (kind == TOMORROW) c.add(Calendar.DAY_OF_MONTH, 1);
        long from = at(c, 19), to = at(c, 23);
        if (kind == TONIGHT && now >= to) {
            Calendar n = Calendar.getInstance(tz);
            n.setTimeInMillis(now);
            n.add(Calendar.DAY_OF_MONTH, 1);
            return new long[] {now, at(n, 2)};
        }
        return new long[] {kind == TONIGHT ? Math.max(now, from) : from, to};
    }

    private static long at(Calendar day, int hour) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // MARK: - Which airing

    /** An airing of the game asked for: its title, times, and how good its channel is (favorite, country…). */
    static final class Candidate {
        final String title;
        final long start, end;
        final int score;

        Candidate(String title, long start, long end, int score) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.score = score;
        }
    }

    private static final Pattern MATCHY = p(".*\\b(v|vs|x|at|versus)\\b.*|.*@.*");

    /** Whether a title reads as a match ("Arsenal v Chelsea") rather than a show about a team. */
    static boolean matchy(String title) {
        return MATCHY.matcher(Xtream.fold(title).isEmpty() ? "" : " " + Xtream.fold(title) + " ").matches() || title.contains("@");
    }

    /**
     * The airing to use (its index; -1 for none not over): one on now first, a match before a show about the team, then
     * the better channel; none on, the soonest. Pure.
     */
    static int pick(List<Candidate> list, long now) {
        int best = -1;
        for (int i = 0; i < list.size(); i++) {
            Candidate c = list.get(i);
            if (c.end <= now) continue;
            if (best < 0 || better(c, list.get(best), now)) best = i;
        }
        return best;
    }

    /** Longer than this, an airing is a block of several matches (or a whole day of a sport), not the game. */
    static final long BLOCK_MS = 4 * 3600_000L;

    /** A block: hours long, or several matches in one title ("Leeds v Spurs / Arsenal v Hull"). */
    static boolean block(Candidate c) {
        return c.end - c.start > BLOCK_MS || c.title.contains(" / ") || c.title.contains(" | ");
    }

    private static final Pattern NOTICE = p("^(next game|next match|upcoming|coming up|no event|no events|off air|channel off air|event starts|starts at)\\b.*");

    /** A notice in a game's place ("Next Game: Hull City @ Arsenal on 2026-11-07", "NO EVENT"), not a broadcast. */
    static boolean notice(Candidate c) {
        return NOTICE.matcher(Xtream.fold(c.title)).matches();
    }

    private static boolean better(Candidate a, Candidate b, long now) {
        boolean aNotice = notice(a), bNotice = notice(b);
        if (aNotice != bNotice) return !aNotice;
        boolean aBlock = block(a), bBlock = block(b);
        if (aBlock != bBlock) return !aBlock;       // the team's own airing, even later, before a block
        boolean aOn = a.start <= now, bOn = b.start <= now;
        if (aOn != bOn) return aOn;
        boolean am = matchy(a.title), bm = matchy(b.title);
        if (!aOn && a.start != b.start) return a.start < b.start;
        if (am != bm) return am;
        if (a.score != b.score) return a.score > b.score;
        return a.start < b.start;
    }
}
