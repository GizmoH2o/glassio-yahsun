p = 'app/src/main/java/com/glasslauncher/tv/ui/SettingsScreen.kt'
s = open(p).read()


def rep(o, n):
    global s
    assert s.count(o) == 1, o[:90]
    s = s.replace(o, n)


# Adjust rows: OK selects the value, then Left/Right change it; OK or Back ends. Without OK, Left/Right just move focus.
rep("""        is Adjust -> Frame(s, onKey = { k -> when (k) { Key.DirectionLeft -> { row.step(-1); true }; Key.DirectionRight -> { row.step(1); true }; else -> false } }, onClick = { row.step(1) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            Text("‹  ${row.value()}  ›", color = Color.White.copy(alpha = if (f) 1f else 0.75f), fontSize = (13 * s).sp)
        }""", """        is Adjust -> {
            var editing by remember { mutableStateOf(false) }
            Frame(s, editing = editing, onKey = { ev ->
                when {
                    !editing -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) row.step(if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) editing = false; true }
                    // Up/Down while editing: stay on the row
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) editing = false }, onClick = { editing = !editing }) { f ->
                Label(row.title, if (editing) "◀ ▶ to change · OK when done" else row.desc, s, Modifier.weight(1f))
                if (editing) Text("‹  ${row.value()}  ›", color = Color(0xFFB9ABFF), fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(row.value(), color = Color.White.copy(alpha = if (f) 1f else 0.75f), fontSize = (13 * s).sp)
            }
        }""")
# App rows (Dock & icons): OK picks the app up, Left/Right move it, OK or Back puts it down; Menu / hold OK = options.
rep("""            Frame(s, onKey = { k -> when (k) { Key.DirectionLeft -> { app.apps.move(e.pkg, -1); true }; Key.DirectionRight -> { app.apps.move(e.pkg, 1); true }; else -> false } }, onClick = { onApp(e) }) { f ->""",
    """            var moving by remember { mutableStateOf(false) }
            Frame(s, editing = moving, onKey = { ev ->
                when {
                    ev.key == Key.Menu -> { if (ev.type == KeyEventType.KeyDown) onApp(e); true }
                    !moving -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) app.apps.move(e.pkg, if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) moving = false; true }
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) moving = false }, onClick = { moving = !moving }, onLong = { onApp(e) }) { f ->""")
rep("""                Text("  ◀ ▶", color = Color.White.copy(alpha = if (f) 0.9f else 0.35f), fontSize = (12 * s).sp)""",
    """                Text(if (moving) "  ◀ ▶ · OK" else "", color = Color(0xFFB9ABFF), fontSize = (12 * s).sp)""")
rep("""                add(Note("Here: ◀ ▶ reorders an app, OK opens its options."))""",
    """                add(Note("Here: OK picks an app up, ◀ ▶ moves it, OK puts it down. Hold OK (or Menu) for its options."))""")
# Toggle/Act rows keep their callbacks; Frame now handles keys itself
rep("""        is Toggle -> Frame(s, onKey = null, onClick = { row.set(!row.get()) }) { f ->""",
    """        is Toggle -> Frame(s, onClick = { row.set(!row.get()) }) { f ->""")
rep("""        is Act -> Frame(s, onKey = null, onClick = row.onClick) { f ->""",
    """        is Act -> Frame(s, onClick = row.onClick) { f ->""")
rep("""@Composable
private fun Frame(s: Float, onKey: ((Key) -> Boolean)?, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onKeyEvent { ev -> if (onKey != null && ev.type == KeyEventType.KeyDown) onKey(ev.key) else false }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape((14 * s).dp))
            .background(if (focused) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}""", """@Composable
private fun Frame(
    s: Float,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    editing: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((14 * s).dp))
            .background(when {
                editing -> Color(0xFF7B61FF).copy(alpha = 0.34f)
                focused -> Color.White.copy(alpha = 0.26f)
                else -> Color.White.copy(alpha = 0.06f)
            })
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}""")
open(p, 'w').write(s)

# Settings button: lighter focus animation (read the colour in the draw phase, quicker spring, faster fade-in)
h = 'app/src/main/java/com/glasslauncher/tv/ui/HomeScreen.kt'
t = open(h).read()
for o, n in [
    ("""    val scale by animateFloatAsState(if (focused) 1.18f else 1f, spring(0.6f, 450f), label = "gear")
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "gearLit")""",
     """    val scale = animateFloatAsState(if (focused) 1.16f else 1f, spring(0.8f, 900f), label = "gear")
    val lit = animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "gearLit")"""),
    ("""            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }""",
     """            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }"""),
    ("""        SlidersGlyph(Modifier.fillMaxSize(), Color.White, lit)""", """        SlidersGlyph(Modifier.fillMaxSize(), Color.White) { lit.value }"""),
    ("""private fun SlidersGlyph(modifier: Modifier, outline: Color, lit: Float = 0f) {
    androidx.compose.foundation.Canvas(modifier) {""",
     """private fun SlidersGlyph(modifier: Modifier, outline: Color, litOf: () -> Float = { 0f }) {
    // The highlight is read while drawing: animating it redraws the glyph, it never recomposes.
    androidx.compose.foundation.Canvas(modifier) {
        val lit = litOf()"""),
    ("""    val a by animateFloatAsState(if (visible) 1f else 0f, tween(350), label = "topAlpha")""",
     """    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 140 else 350), label = "topAlpha")"""),
]:
    assert t.count(o) == 1, o[:80]
    t = t.replace(o, n)
open(h, 'w').write(t)
print('ok')
