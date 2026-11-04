package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A team's other names in the guide: "Manchester United" is "Man Utd" on some channels. The names here, and those added
 * by hand on the team's page (Also called). A known team's names are whole phrases, so following Manchester United
 * doesn't catch "Manchester City v West Ham United"; anything else followed keeps matching by its words.
 */
final class LiveNames {
    private LiveNames() {}

    /**
     * One team per line, folded, its main name first. "name~a,b": a name other teams or sports share, so following
     * those words alone stays as typed (Spurs may be San Antonio), and a title with phrase a or b isn't this team.
     */
    private static final String[] TEAMS = {
            // England
            "manchester united|manchester utd|man united|man utd|man u|mufc",
            "manchester city|man city|mcfc",
            "tottenham hotspur|tottenham|thfc|spurs~san antonio,sa spurs",
            "newcastle united|newcastle utd|newcastle~newcastle jets,newcastle knights,newcastle falcons,newcastle thunder",
            "west ham united|west ham utd|west ham",
            "wolverhampton wanderers|wolverhampton|wolves~warrington,minnesota",
            "leeds united|leeds utd|leeds~leeds rhinos",
            "brighton and hove albion|brighton hove albion|brighton and hove|brighton",
            "nottingham forest|nottm forest|nott m forest|notts forest",
            "sheffield united|sheffield utd|sheff utd|sheff united",
            "sheffield wednesday|sheffield wed|sheff wed|sheff wednesday",
            "west bromwich albion|west bromwich|west brom|wba",
            "leicester city|leicester~leicester tigers,leicester riders",
            "bournemouth|afc bournemouth",
            "crystal palace|c palace",
            "aston villa|a villa",
            "ipswich town|ipswich",
            "norwich city|norwich",
            "stoke city|stoke",
            "swansea city|swansea",
            "queens park rangers|qpr",
            // Scotland
            "glasgow rangers|rangers~new york,ny rangers,texas,queens park,qpr,tranmere",
            "glasgow celtic|celtic",
            // Spain
            "barcelona|fc barcelona|barca",
            "real madrid|r madrid",
            "atletico madrid|atletico de madrid|atl madrid|atleti"
                    + "|atletico~atletico mineiro,atletico nacional,atletico paranaense,atletico san luis,atletico tucuman,atletico goianiense",
            "athletic bilbao|athletic club|bilbao",
            "real sociedad|sociedad",
            "real betis|betis",
            // Germany
            "bayern munich|bayern munchen|bayern muenchen|fc bayern|bayern",
            "borussia dortmund|dortmund|bvb",
            "borussia monchengladbach|borussia moenchengladbach|monchengladbach|moenchengladbach|gladbach|b monchengladbach",
            "bayer leverkusen|bayer 04 leverkusen|leverkusen",
            "rb leipzig|leipzig",
            "eintracht frankfurt|e frankfurt|frankfurt",
            // France
            "paris saint germain|paris st germain|paris sg|psg",
            "olympique marseille|marseille",
            "olympique lyonnais|olympique lyon|lyon",
            "as monaco|monaco~grand prix,formula,f1,gp,e prix",
            // Italy
            "inter milan|internazionale|fc internazionale|inter~inter miami,inter turku,inter baku",
            "ac milan|milan~inter milan",
            "juventus|juve",
            "as roma|roma",
            "ss lazio|lazio",
            "ssc napoli|napoli",
            "acf fiorentina|fiorentina",
            // Portugal, the Netherlands
            "fc porto|porto~",
            "sl benfica|benfica",
            "sporting cp|sporting lisbon|sporting clube de portugal"
                    + "|sporting~sporting kansas city,sporting kc,sporting gijon,sporting cristal,sporting charleroi",
            "afc ajax|ajax",
            "psv eindhoven|psv",
            // MLS
            "inter miami|inter miami cf",
            "la galaxy|los angeles galaxy",
            "lafc|los angeles fc",
            "new york city fc|nycfc",
    };

    // Left out of a name when looking it up: "FC Barcelona" and "Manchester United FC" are known teams.
    private static final Set<String> CLUB_WORDS = new HashSet<>(Arrays.asList("fc", "afc", "cf", "sc", "the"));

    private static final Map<String, String> MAIN = new HashMap<>();        // any name → the team's main name
    private static final Map<String, List<String>> NAMES = new HashMap<>(); // main name → all its names
    private static final Map<String, String[]> NOT = new HashMap<>();       // a shared name → the titles it isn't in
    private static final List<String> REPEATED = new ArrayList<>();

    static {
        for (String line : TEAMS) {
            String[] parts = line.split("\\|");
            String main = parts[0];
            List<String> names = new ArrayList<>();
            for (String part : parts) {
                int tilde = part.indexOf('~');
                String name = tilde < 0 ? part : part.substring(0, tilde);
                if (tilde >= 0) NOT.put(name, part.substring(tilde + 1).split(","));
                if (MAIN.put(name, main) != null) REPEATED.add(name);
                names.add(name);
            }
            NAMES.put(main, Collections.unmodifiableList(names));
        }
    }

    /** Names in the table twice (a test keeps this empty). */
    static List<String> repeated() {
        return REPEATED;
    }

    // MARK: - One followed name's names

    /** What's looked for when `term` is followed. */
    static final class Names {
        private final String term;
        private final boolean known;
        private final List<String> phrases = new ArrayList<>();

        private Names(String term, Collection<String> mine) {
            this.term = term;
            String main = known(term);
            known = main != null;
            if (known) {
                phrases.addAll(NAMES.get(main));
                if (!phrases.contains(term)) phrases.add(term);
            }
            for (String name : mine) if (!phrases.contains(name) && !name.equals(term)) phrases.add(name);
        }

        /** Whether a title (folded) is about it. */
        boolean in(String title) {
            if (!known && LiveFollow.matches(term, title)) return true;
            String t = " " + title + " ";
            for (String p : phrases) if (t.contains(" " + p + " ") && !blocked(p, t)) return true;
            return false;
        }

        /** Whether a team on a scoreboard is this one: `name`, one of its names, of the team called `full` (both folded). */
        boolean isTeam(String name, String full) {
            String n = " " + name + " ";
            if (!known) {
                String t = " " + term + " ";
                if (name.equals(term) || t.contains(n) || n.contains(t)) return true;
            }
            String f = " " + full + " ";
            for (String p : phrases) if (n.contains(" " + p + " ") && !blocked(p, f)) return true;
            return false;
        }

        /** What to ask the guide: each name as one phrase; a term of no known team by its words. */
        List<String[]> search() {
            List<String[]> out = new ArrayList<>();
            if (!known && !term.isEmpty()) out.add(term.split(" "));
            // A name with another inside it ("man utd" has "man u") would find nothing more.
            for (String p : phrases) {
                boolean covered = false;
                for (String q : phrases) covered |= !q.equals(p) && p.contains(q);
                if (!covered) out.add(new String[] {p});
            }
            return out;
        }

        private static boolean blocked(String phrase, String paddedTitle) {
            String[] not = NOT.get(phrase);
            if (not == null) return false;
            for (String b : not) if (!b.isEmpty() && paddedTitle.contains(" " + b + " ")) return true;
            return false;
        }
    }

    static Names of(String term, Collection<String> mine) {
        return new Names(term, mine);
    }

    /** `term` (folded) with the names added to it by hand. */
    static Names of(Context c, String term) {
        refresh(c);
        Names n = CACHE.get(term);
        if (n == null) {
            List<String> mine = byHand.get(term);
            n = new Names(term, mine == null ? Collections.<String>emptyList() : mine);
            CACHE.put(term, n);
        }
        return n;
    }

    /** The guide's airings of `names` between the times. Background thread. */
    static List<XmltvStore.Found> find(Context c, Names names, long from, long to, int limit) {
        List<XmltvStore.Found> out = new ArrayList<>();
        for (XmltvStore.Found f : XmltvStore.findAny(c, names.search(), from, to, limit)) {
            if (names.in(Xtream.fold(f.title))) out.add(f);
        }
        return out;
    }

    // MARK: - One team, whatever it's called

    /** The team a followed name is, by its main name, else null. A name other teams share is none. */
    private static String known(String term) {
        String main = NOT.containsKey(term) ? null : MAIN.get(term);
        if (main != null) return main;
        String bare = withoutClubWords(term);
        return NOT.containsKey(bare) ? null : MAIN.get(bare);
    }

    private static String withoutClubWords(String term) {
        StringBuilder b = new StringBuilder();
        for (String w : term.split(" ")) {
            if (w.isEmpty() || CLUB_WORDS.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    /** One key per team: following "Man Utd" and "Manchester United" is following one team. */
    static String key(String term) {
        String folded = Xtream.fold(term), main = known(folded);
        return main != null ? main : folded;
    }

    /** One side of a match (folded, without club words) by its team's main name: both listings of a match are one. */
    static String side(String name) {
        String main = MAIN.get(name);
        if (main != null) return main;
        main = sides.get(name);
        return main != null ? main : name;
    }

    // MARK: - Names added by hand

    /** A typed list ("Man U, MUFC") as names: folded, at least 2 letters, each once. */
    static List<String> parse(String typed) {
        List<String> out = new ArrayList<>();
        if (typed == null) return out;
        for (String part : typed.split(",")) {
            String name = Xtream.fold(part);
            if (name.length() >= 2 && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private static final long FRESH = 3000;     // the file is written from another process too
    private static final Map<String, Names> CACHE = new ConcurrentHashMap<>();
    private static volatile Map<String, List<String>> byHand = Collections.emptyMap();
    private static volatile Map<String, String> sides = Collections.emptyMap();
    private static Map<String, String> lastRead;
    private static long readAt;

    private static synchronized void refresh(Context c) {
        long now = System.currentTimeMillis();
        if (now - readAt < FRESH && lastRead != null) return;
        readAt = now;
        Map<String, String> read = LiveFollow.alsoAll(c);
        if (read.equals(lastRead)) return;
        lastRead = read;
        Map<String, List<String>> names = new HashMap<>();
        Map<String, String> s = new HashMap<>();
        for (Map.Entry<String, String> e : read.entrySet()) {
            List<String> list = parse(e.getValue());
            names.put(e.getKey(), list);
            for (String name : list) s.put(name, key(e.getKey()));
        }
        byHand = names;
        sides = s;
        CACHE.clear();
    }

    /** The names just changed here: read again. */
    static synchronized void changed() {
        readAt = 0;
    }
}
