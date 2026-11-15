package ca.emarshall.finance.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.emarshall.finance.ui.theme.Fin

/** A top-level tab: large title that scrolls away with the content, grouped background. */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize().background(Fin.palette.background),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                trailing()
            }
        }
        content()
    }
}

/** A pushed screen: compact bar with a back chevron, centred title, then scrolling content. */
@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Fin.palette.background)) {
        NavBar(title, onBack, trailing)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
fun NavBar(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(Fin.palette.background).statusBarsPadding().height(52.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Back", tint = Fin.palette.accent, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            title, Modifier.align(Alignment.Center).padding(horizontal = 72.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** White rounded card, no border: the basic surface. */
@Composable
fun FinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Fin.palette.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Gradient hero card with white text, for the one number that matters on a screen. */
@Composable
fun HeroCard(brush: Brush, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(brush)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Small grey caption above a card, with an optional action on the right ("See all"). */
@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 0.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) {
            Text(action, Modifier.clickable(onClick = onAction).padding(4.dp), color = Fin.palette.accent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** iOS-style inset grouped list: rows share one rounded container with inset separators. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fin.palette.card), content = content)
}

@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = Color.Unspecified,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    showChevron: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) IconTile(icon, if (iconColor == Color.Unspecified) Fin.palette.accent else iconColor)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyLarge, color = if (valueColor == Color.Unspecified) Fin.palette.secondaryLabel else valueColor, maxLines = 1)
            }
            trailing?.invoke()
            if (showChevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Fin.palette.tertiaryLabel)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = if (icon != null) 58.dp else 16.dp), color = Fin.palette.separator, thickness = 0.5.dp)
    }
}

@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Pill-shaped segmented control. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fin.palette.cardSecondary).padding(2.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Fin.palette.card else Color.Transparent)
                    .clickable { onSelect(o) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelMedium, color = if (on) Fin.palette.label else Fin.palette.secondaryLabel, maxLines = 1)
            }
        }
    }
}

/** A tappable rounded pill, e.g. "2 things need attention ›". */
@Composable
fun Pill(text: String, icon: ImageVector?, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/** Legend dot + label for charts. */
@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel)
    }
}

/** Grey footnote under a group or card, aligned with its content. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel)
}

enum class NoticeTone { INFO, WARNING, ERROR, SUCCESS }

/** A tinted strip for messages, warnings and errors, instead of bare coloured text. */
@Composable
fun Notice(text: String, tone: NoticeTone = NoticeTone.INFO, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val p = Fin.palette
    val (color, icon) = when (tone) {
        NoticeTone.INFO -> p.accent to Icons.Outlined.Info
        NoticeTone.WARNING -> p.warning to Icons.Outlined.WarningAmber
        NoticeTone.ERROR -> p.negative to Icons.Outlined.ErrorOutline
        NoticeTone.SUCCESS -> p.positive to Icons.Outlined.CheckCircle
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.label)
        if (action != null && onAction != null) {
            Text(action, Modifier.clickable(onClick = onAction).padding(4.dp), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The one main action on a screen: full width, tall, rounded. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A secondary action: tinted, same shape as [PrimaryButton]. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    val color = if (destructive) Fin.palette.negative else Fin.palette.accent
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.12f), contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Borderless text field on a grey fill, for forms inside cards. */
@Composable
fun FinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val p = Fin.palette
    TextField(
        value, onValueChange, modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, singleLine = singleLine,
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = p.cardSecondary, unfocusedContainerColor = p.cardSecondary, disabledContainerColor = p.cardSecondary,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.secondaryLabel,
        ),
    )
}

/** Centred icon and message for a screen or section with nothing in it yet. */
@Composable
fun EmptyMessage(text: String, icon: ImageVector = Icons.Outlined.Inbox, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Fin.palette.accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Fin.palette.accent, modifier = Modifier.size(28.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Fin.palette.secondaryLabel, textAlign = TextAlign.Center)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A row with a switch on the right, for settings. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null, iconColor: Color = Color.Unspecified, divider: Boolean = true) {
    GroupRow(title, subtitle = subtitle, icon = icon, iconColor = iconColor, divider = divider, onClick = { onChange(!checked) }) {
        Switch(checked, onChange)
    }
}

/** Label on the left, value on the right, inside a [Group]. */
@Composable
fun ValueRow(title: String, value: String, valueColor: Color = Color.Unspecified, divider: Boolean = true) =
    GroupRow(title, value = value, valueColor = valueColor, divider = divider)

/** A [Group] row that shows the current choice on the right and opens a menu of options. */
@Composable
fun <T> MenuRow(
    title: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = Color.Unspecified,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title, subtitle = subtitle, icon = icon, iconColor = iconColor, value = display(selected), divider = divider,
            onClick = if (enabled) ({ open = true }) else null,
            trailing = { Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = Fin.palette.tertiaryLabel, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}
