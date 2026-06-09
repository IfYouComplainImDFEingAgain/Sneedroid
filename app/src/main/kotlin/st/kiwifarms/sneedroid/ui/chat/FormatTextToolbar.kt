package st.kiwifarms.sneedroid.ui.chat

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * A [TextToolbar] that augments the system long-press selection menu (Copy / Cut / Paste /
 * Select all) with BBCode formatting actions. The format items only appear when there is a real
 * selection — Android offers Copy exactly then, so we gate on it. [onFormat] receives the open and
 * close tags to wrap the current selection with.
 *
 * Provided via `LocalTextToolbar` around the composer's text field; everything else (positioning,
 * copy/paste behaviour) mirrors Compose's default `AndroidTextToolbar`.
 */
class FormatTextToolbar(
    private val view: View,
    private val onFormat: (before: String, after: String) -> Unit,
) : TextToolbar {
    private var actionMode: ActionMode? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private var rect: Rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(Callback(), ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private inner class Callback : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            onCopy?.let { menu.add(0, COPY, 0, android.R.string.copy) }
            onPaste?.let { menu.add(0, PASTE, 1, android.R.string.paste) }
            onCut?.let { menu.add(0, CUT, 2, android.R.string.cut) }
            onSelectAll?.let { menu.add(0, SELECT_ALL, 3, android.R.string.selectAll) }
            // Formatting needs a selection to wrap; Copy is offered exactly when one exists.
            if (onCopy != null) {
                menu.add(0, BOLD, 4, "Bold")
                menu.add(0, ITALIC, 5, "Italic")
                menu.add(0, UNDERLINE, 6, "Underline")
                menu.add(0, STRIKE, 7, "Strike")
            }
            return true
        }

        // Rebuild on invalidate() so the item set tracks the latest callbacks/selection.
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            onCreateActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                COPY -> onCopy?.invoke()
                PASTE -> onPaste?.invoke()
                CUT -> onCut?.invoke()
                SELECT_ALL -> { onSelectAll?.invoke(); return true } // keep open to allow a follow-up format
                BOLD -> onFormat("[b]", "[/b]")
                ITALIC -> onFormat("[i]", "[/i]")
                UNDERLINE -> onFormat("[u]", "[/u]")
                STRIKE -> onFormat("[s]", "[/s]")
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private companion object {
        const val COPY = 0
        const val PASTE = 1
        const val CUT = 2
        const val SELECT_ALL = 3
        const val BOLD = 4
        const val ITALIC = 5
        const val UNDERLINE = 6
        const val STRIKE = 7
    }
}
