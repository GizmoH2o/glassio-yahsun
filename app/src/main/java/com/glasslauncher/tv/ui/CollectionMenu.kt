package com.glasslauncher.tv.ui

import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Collections
import com.glasslauncher.tv.data.HeroItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which of your collections [item] is in: OK puts it in or takes it out; a new one can be made from here. */
internal fun collectionMenu(app: GlassApp, ctx: android.content.Context, scope: CoroutineScope, item: HeroItem, onDialog: (DialogState) -> Unit): DialogState {
    val st = app.settings
    val list = Collections.parse(st.myCollections)
    val options = ArrayList<Pair<String, () -> Unit>>()
    for (c in list) {
        val inIt = Collections.holds(c, item)
        options += (if (inIt) "✓  " else "") + c.name to {
            st.myCollections = Collections.write(Collections.toggle(Collections.parse(st.myCollections), c.slot, item))
            toast(ctx, (if (inIt) "Removed from ${c.name}" else "Added to ${c.name}") + " · ${item.name}")
        }
    }
    if (list.size < Collections.MAX) options += "New collection" to {
        scope.launch {
            delay(80)
            onDialog(TextEntry("Collection name", "", "Friday films, for example") { typed ->
                val now = Collections.parse(st.myCollections)
                val made = Collections.create(now, typed)
                if (made == null) toast(ctx, "Give it a name")
                else {
                    val fresh = made.first { n -> now.none { it.slot == n.slot } }
                    st.myCollections = Collections.write(Collections.toggle(made, fresh.slot, item))
                    toast(ctx, "Added to ${fresh.name} · ${item.name}")
                }
            })
        }
        Unit
    }
    return MenuDialog("Add to a collection", options)
}
