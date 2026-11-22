package com.fourseveneightnine.tv.client.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType

internal val CatalogRailGutter = 104.dp

internal class CatalogRailFocus(private val origin: MutableState<String?> = mutableStateOf(null)) {
    val entry = FocusRequester()
    val originKey: String? get() = origin.value
    private var restoreOrigin: (() -> Boolean)? = null
    var focusedAction: FocusRequester? = null

    fun enter(key: String, restore: () -> Boolean): Boolean {
        if (!runCatching { entry.requestFocus() }.getOrDefault(false)) return false
        origin.value = key
        restoreOrigin = restore
        return true
    }

    fun restoreTarget(): Boolean = runCatching { restoreOrigin?.invoke() == true }.getOrDefault(false)

    fun restoreAction(): Boolean = runCatching { (focusedAction ?: entry).requestFocus() }.getOrDefault(false)
}

internal data class CatalogAction(
    @DrawableRes val icon: Int,
    val label: String,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

@Composable
internal fun CatalogActionRail(
    rail: CatalogRailFocus,
    actions: List<CatalogAction>,
    onLeave: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (actions.isEmpty()) return
    val focus = remember(rail, actions.size) { List(actions.size) { if (it == 0) rail.entry else FocusRequester() } }
    var hasFocus by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    BackHandler(enabled = enabled && hasFocus) { onLeave(rail.originKey) }
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.tvGlassPanel(42.dp)
                .padding(7.dp)
                .onFocusChanged { hasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onLeave(rail.originKey); true }
                        Key.DirectionRight -> true
                        Key.DirectionUp, Key.DirectionDown -> {
                            val step = if (event.key == Key.DirectionUp) -1 else 1
                            focus[(selected + step).coerceIn(focus.indices)].requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            actions.forEachIndexed { index, action ->
                TvFocusable(
                    onClick = action.run,
                    enabled = enabled && action.enabled,
                    focusWhenDisabled = enabled,
                    accessibleLabel = action.label,
                    modifier = Modifier.size(66.dp)
                        .focusRequester(focus[index])
                        .onFocusChanged {
                            if (it.isFocused) {
                                selected = index
                                rail.focusedAction = focus[index]
                            }
                        },
                    focusScale = 1f,
                    cornerRadius = 33.dp,
                ) { focused ->
                    Box(Modifier.size(66.dp).background(if (focused) TvColor.TextPrimary.copy(alpha = 0.10f) else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Image(
                            painterResource(action.icon), contentDescription = null, modifier = Modifier.size(36.dp),
                            colorFilter = ColorFilter.tint(when {
                                !action.enabled -> TvColor.TextMuted
                                focused -> TvColor.Accent
                                else -> TvColor.TextPrimary
                            }),
                        )
                    }
                }
            }
        }
        if (enabled && hasFocus) {
            val index = selected.coerceIn(actions.indices)
            Text(
                actions[index].label,
                modifier = Modifier.padding(end = CatalogRailGutter)
                    .offset(y = ((index - actions.lastIndex / 2f) * 71).dp)
                    .widthIn(max = 340.dp)
                    .background(TvColor.Elevated, CircleShape).padding(horizontal = 16.dp, vertical = 10.dp)
                    .clearAndSetSemantics { },
                style = TvType.Meta,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
