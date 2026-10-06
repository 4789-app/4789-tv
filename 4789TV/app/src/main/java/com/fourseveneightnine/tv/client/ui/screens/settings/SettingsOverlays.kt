@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The TV keyboard used modally, spec §15.3: the wide key says "Done" rather than "Clear".
 *
 * Typing a manifest URL on a remote is unpleasant however it is drawn, so the field shows the
 * whole string and the panel stays until Done or BACK.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModalKeyboard(
    header: String,
    hint: String,
    initialText: String = "https://",
    space: String = ".",
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var upperCase by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.72f))
            // The overlay is modal, so focus may not leave it. Measured on the box: LEFT from key
            // column 1 walked out to the settings list behind the scrim, and OK there threw the
            // half-typed address away.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .semantics {
                paneTitle = header
                isTraversalGroup = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(40.dp),
        ) {
            Text(header, style = TvType.PanelHeader, color = TvColor.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(TvSpace.S))
            Text(hint, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 2)
            Spacer(Modifier.height(TvSpace.M))
            Row(
                modifier = Modifier
                    .width(680.dp)
                    .heightIn(min = 64.dp)
                    .clip(TvShape.Control)
                    .background(TvColor.Elevated2)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), TvShape.Control)
                    .semantics {
                        contentDescription = "Entered text"
                        stateDescription = text.ifEmpty { "Empty" }
                    }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    style = TvType.data(24),
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(TvSpace.M))
            TvKeyboard(
                modal = true,
                keyHeight = 60.dp,
                gap = 8.dp,
                firstKeyModifier = Modifier.focusRequester(firstKey),
                onKey = { stroke ->
                    when (stroke) {
                        is KeyStroke.Character -> text += if (upperCase) stroke.value else stroke.value.lowercase()
                        KeyStroke.Space -> text += space
                        KeyStroke.Backspace -> text = text.dropLast(1)
                        KeyStroke.Commit -> onDone(text)
                    }
                },
            )
            Spacer(Modifier.height(TvSpace.S))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                URL_KEYS.forEach { character ->
                    UrlKey(character) { text += character }
                }
                UrlKey(if (upperCase) "ABC" else "abc") { upperCase = !upperCase }
            }
            Spacer(Modifier.height(TvSpace.M))
            Row {
                TvButton("Cancel", onCancel, kind = ButtonKind.Ghost, height = TvGeom.ButtonHeightDense)
            }
        }
    }
}

@Composable
private fun UrlKey(label: String, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        accessibleLabel = if (label == "abc" || label == "ABC") "Change letter case, $label" else "Type $label",
        cornerRadius = 8.dp,
        focusScale = 1f,
    ) { focused ->
        Box(
            Modifier
                .width(60.dp)
                .height(52.dp)
                .clip(TvShape.Chip)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TvType.Meta, color = TvColor.TextPrimary, maxLines = 1)
        }
    }
}

private val URL_KEYS = listOf(".", "/", "-", "_", ":", "?", "=", "&", "%")

/** Spec §14.10: the borrowed files and their licences, read from the app's own assets. */
@Composable
internal fun LicencesPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val body = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text by produceState(initialValue = "Reading licences…", context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() }
            }.getOrElse { "The licence file is missing from this build." }
        }
    }
    SidePanel(header = "Licences", onClose = onClose, width = TvGeom.SidePanelWideWidth) {
        // A scroll container only ever sees a D-pad key when something inside it holds the ring.
        // One focusable on the column itself is what makes UP and DOWN scroll the text.
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(body)
                .tvFocusRing(focused, 10.dp)
                .semantics {
                    contentDescription = "Licence text. Use up and down to scroll."
                    liveRegion = LiveRegionMode.Polite
                }
                .focusable(interactionSource = interaction)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text, style = TvType.Meta, color = TvColor.TextSecondary)
            Spacer(Modifier.height(TvSpace.M))
        }
    }
    LaunchedEffect(Unit) { runCatching { body.requestFocus() } }
}

internal const val LICENCES_ASSET = "licences.txt"
