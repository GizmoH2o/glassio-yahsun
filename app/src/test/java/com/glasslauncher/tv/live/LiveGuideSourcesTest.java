package com.glasslauncher.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LiveGuideSourcesTest {
    private static List<LiveGuideSources.Link> three() {
        return new ArrayList<>(Arrays.asList(new LiveGuideSources.Link("https://a.com/a.xml", true),
                new LiveGuideSources.Link("https://b.com/b.xml", false), new LiveGuideSources.Link("https://c.com/c.xml", true)));
    }

    private static String urls(List<LiveGuideSources.Link> links) {
        StringBuilder b = new StringBuilder();
        for (LiveGuideSources.Link l : links) b.append(l.url.charAt(8));
        return b.toString();
    }

    @Test
    public void siteIsTheHostOnly() {
        assertEquals("epg.example.com", LiveGuideSources.site("https://epg.example.com/guide.xml.gz"));
        assertEquals("epg.example.com", LiveGuideSources.site("http://user:secret@epg.example.com:8080/xmltv.php?password=x"));
        assertEquals("host.tv", LiveGuideSources.site("HTTPS://host.tv"));
    }

    @Test
    public void validNeedsHttp() {
        assertTrue(LiveGuideSources.valid("https://a.com/g.xml"));
        assertTrue(LiveGuideSources.valid("  HTTP://a.com/g.xml.gz "));
        assertFalse(LiveGuideSources.valid("ftp://a.com/g.xml"));
        assertFalse(LiveGuideSources.valid("https://"));
        assertFalse(LiveGuideSources.valid(null));
    }

    @Test
    public void readWriteRoundTrip() {
        List<LiveGuideSources.Link> back = LiveGuideSources.read(LiveGuideSources.write(three()));
        assertEquals(3, back.size());
        assertEquals("abc", urls(back));
        assertTrue(back.get(0).on);
        assertFalse(back.get(1).on);
    }

    @Test
    public void readSkipsWhatIsNotALink() {
        assertEquals(0, LiveGuideSources.read("not json").size());
        List<LiveGuideSources.Link> l = LiveGuideSources.read("[{\"u\":\"nope\"},{\"u\":\"https://x.com/g\"},5]");
        assertEquals(1, l.size());
        assertTrue(l.get(0).on);      // on unless said otherwise
    }

    @Test
    public void movedStaysWithinTheList() {
        assertEquals("bac", urls(LiveGuideSources.moved(three(), 1, -1)));
        assertEquals("acb", urls(LiveGuideSources.moved(three(), 1, 1)));
        assertEquals("abc", urls(LiveGuideSources.moved(three(), 0, -1)));
        assertEquals("abc", urls(LiveGuideSources.moved(three(), 2, 1)));
    }
}
