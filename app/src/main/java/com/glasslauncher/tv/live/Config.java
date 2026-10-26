package com.glasslauncher.tv.live;

/** Live TV's fixed values (Zero's came from config.properties; Glassio has none of its own for these). */
final class Config {
    private Config() {}

    /** Which country's age ratings TMDB is asked for first (then the US's and the UK's). */
    static final String TMDB_REGION = "US";
}
