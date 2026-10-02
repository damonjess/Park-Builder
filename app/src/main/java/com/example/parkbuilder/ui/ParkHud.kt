package com.example.parkbuilder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameSpeed
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.ToolCategory

/** Chunky wood-and-brass panel colours, in the spirit of the PS1 interface. */
object HudColors {
    val Panel = Color(0xFF2C4A6B)
    val PanelDark = Color(0xFF1B2F45)
    val PanelLight = Color(0xFF3D6088)
    val Brass = Color(0xFFD9A94C)
    val BrassDark = Color(0xFF9A7330)
    val Cream = Color(0xFFF6E3B0)
    val Ink = Color(0xFF101A24)
    val Good = Color(0xFF6BD46B)
    val Bad = Color(0xFFE85A4F)
}

/** A single emoji glyph per catalogue entry — cheap "pixel icon" stand-ins. */
fun BuildItem.glyph(): String = when (this) {
    BuildItem.PATH -> "🛣"
    BuildItem.WATER -> "💧"
    BuildItem.GRASS -> "🌿"
    BuildItem.BULLDOZE -> "🧨"
    BuildItem.CAROUSEL -> "🎠"
    BuildItem.FERRIS_WHEEL -> "🎡"
    BuildItem.DODGEMS -> "🚗"
    BuildItem.LOG_FLUME -> "🛶"
    BuildItem.ROLLER_COASTER -> "🎢"
    BuildItem.BURGER_BAR -> "🍔"
    BuildItem.SODA_STAND -> "🥤"
    BuildItem.ICE_CREAM -> "🍦"
    BuildItem.GIFT_SHOP -> "🎁"
    BuildItem.RESTROOM -> "🚻"
    BuildItem.FLOWERS -> "🌷"
    BuildItem.BENCH -> "🪑"
    BuildItem.TREE -> "🌳"
    BuildItem.LAMP -> "💡"
    BuildItem.FOUNTAIN -> "⛲"
}

@Composable
fun GameHudTop(
    state: GameState,
    onSpeed: (GameSpeed) -> Unit,
    onNewPark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HudColors.PanelDark)
            .border(2.dp, HudColors.Brass, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Row 1: Park Name (left) & Money / Today info (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.parkName,
                color = HudColors.Cream,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "today £${state.todayIncome} • ${state.stats.visitors} guests",
                    color = HudColors.Cream.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
                Text(
                    text = "£${state.money}",
                    color = if (state.money < 0) HudColors.Bad else HudColors.Good,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 2: Stats (left) & Speed controls + New park button (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Day ${state.day}  •  ${state.stats.rides} rides  •  rating ${state.stats.rating}/100",
                color = HudColors.Brass,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GameSpeed.entries.forEach { speed ->
                    val active = state.speed == speed
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (active) HudColors.Brass else HudColors.PanelLight)
                            .clickable { onSpeed(speed) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = speed.label,
                            color = if (active) HudColors.Ink else HudColors.Cream,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 26.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(HudColors.PanelLight)
                        .clickable(onClick = onNewPark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "NEW",
                        color = HudColors.Cream,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun GameHudBottom(
    state: GameState,
    category: ToolCategory,
    onCategory: (ToolCategory) -> Unit,
    onSelectItem: (BuildItem?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(HudColors.PanelDark)
            .border(2.dp, HudColors.Brass, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // ---- Ticker -------------------------------------------------------
        val ticker = when {
            state.messageTimer > 0f && state.message != null -> state.message
            state.selectedItem != null -> hintFor(state.selectedItem)
            else -> "Pick something to build, or tap a ride to inspect it"
        }
        Text(
            text = ticker,
            color = if (state.messageTimer > 0f) HudColors.Brass else HudColors.Cream,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // ---- Category tabs ------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolCategory.entries.forEach { entry ->
                val active = entry == category
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) HudColors.Brass else HudColors.Panel)
                        .clickable { onCategory(entry) }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = entry.displayName,
                        color = if (active) HudColors.Ink else HudColors.Cream,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ---- Items --------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BuildItem.entries.filter { it.category == category }.forEach { item ->
                ItemButton(
                    item = item,
                    selected = state.selectedItem == item,
                    affordable = state.money >= item.cost,
                    onClick = { onSelectItem(item) }
                )
            }
        }
    }
}

@Composable
private fun ItemButton(
    item: BuildItem,
    selected: Boolean,
    affordable: Boolean,
    onClick: () -> Unit
) {
    val background = when {
        selected -> HudColors.Brass
        item.category == ToolCategory.SCENERY -> Color(0xFF2F5E3C)
        item.category == ToolCategory.TERRAIN -> Color(0xFF4A4335)
        else -> HudColors.Panel
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) HudColors.Cream else HudColors.BrassDark,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp)
    ) {
        Text(text = item.glyph(), fontSize = 19.sp)
        Text(
            text = if (item.cost == 0) "free" else "£${item.cost}",
            color = if (affordable) HudColors.Cream else HudColors.Bad,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = item.displayName,
            color = HudColors.Cream.copy(alpha = 0.8f),
            fontSize = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private fun hintFor(item: BuildItem): String = when {
    item == BuildItem.BULLDOZE -> "Tap a building or path to demolish it"
    item.isTerrainBrush -> "Drag across the ground to lay ${item.displayName.lowercase()}"
    item.category == ToolCategory.SCENERY -> "Drag to plant ${item.displayName.lowercase()}s"
    else -> "Tap an empty spot to place ${item.displayName} (£${item.cost})"
}
