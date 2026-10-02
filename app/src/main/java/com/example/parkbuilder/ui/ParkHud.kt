package com.example.parkbuilder.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parkbuilder.game.GameEngine
import com.example.parkbuilder.game.fairEntranceFee
import com.example.parkbuilder.game.gatePriceRatio
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameSpeed
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Structure
import com.example.parkbuilder.game.model.ToolCategory

/** Chunky wood-and-brass panel colours, in the spirit of the PS1 interface. */
object HudColors {
    val Panel = Color(0xFF285494)
    val PanelDark = Color(0xFF1B3A68)
    val PanelLight = Color(0xFF3B6CB0)
    val Brass = Color(0xFFE2B048)
    val BrassDark = Color(0xFFA67C28)
    val Cream = Color(0xFFFFF0C4)
    val Ink = Color(0xFF0D1C2E)
    val Good = Color(0xFF52D652)
    val Bad = Color(0xFFE84C3D)
}

/**
 * One slim status strip. It used to be two full rows, which in landscape left barely a
 * third of the screen for the park itself.
 */
@Composable
fun GameHudTop(
    state: GameState,
    onSpeed: (GameSpeed) -> Unit,
    onNewPark: () -> Unit,
    onGate: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only the bottom corners are rounded: the strip is flush with the top of the screen.
    val stripShape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(stripShape)
            .background(HudColors.PanelDark)
            .border(2.dp, HudColors.Brass, stripShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.parkName,
            color = HudColors.Cream,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Day ${state.day}  •  ${state.stats.rides} rides  •  rating ${state.stats.rating}",
            color = HudColors.Brass,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "£${state.todayIncome} • ${state.stats.visitors}g",
            color = HudColors.Cream.copy(alpha = 0.75f),
            fontSize = 10.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(HudColors.PanelLight)
                .clickable(onClick = onGate)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "🎟 £${state.entranceFee}",
                color = HudColors.Cream,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "£${state.money}",
            color = if (state.money < 0) HudColors.Bad else HudColors.Good,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            GameSpeed.entries.forEach { speed ->
                val active = state.speed == speed
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) HudColors.Brass else HudColors.PanelLight)
                        .clickable { onSpeed(speed) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speed.label,
                        color = if (active) HudColors.Ink else HudColors.Cream,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(HudColors.PanelLight)
                    .clickable(onClick = onNewPark),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NEW",
                    color = HudColors.Cream,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
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
    onTicketPrice: (String, Int) -> Unit,
    gateOpen: Boolean,
    onEntranceFee: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Tapping a ride, shop or toilet with no build tool active selects it for inspection.
    val inspected = state.structures.firstOrNull { it.isSelected && it.item.isAttraction }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(HudColors.PanelDark)
            .border(2.dp, HudColors.Brass, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Row 1: portrait + ticker + category tabs share a line. They used to occupy
        // three separate rows, which is what pushed the panel two thirds up the screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ManagerPortrait(state)
            Spacer(modifier = Modifier.width(8.dp))

            val ticker = when {
                state.messageTimer > 0f && state.message != null -> state.message
                state.selectedItem != null -> hintFor(state.selectedItem)
                else -> "Pick something to build, or tap a ride to inspect it"
            }
            Text(
                text = ticker,
                color = if (state.messageTimer > 0f) HudColors.Brass else HudColors.Cream,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                ToolCategory.entries.forEach { entry ->
                    val active = entry == category
                    val iconLabel = when (entry) {
                        ToolCategory.RIDE -> "🎠 Rides"
                        ToolCategory.SHOP -> "🏬 Shops"
                        ToolCategory.FACILITY -> "🚻 Facilities"
                        ToolCategory.SCENERY -> "🌳 Scenery"
                        ToolCategory.TERRAIN -> "🧭 Land"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) HudColors.Brass else HudColors.Panel)
                            .border(1.dp, if (active) HudColors.Cream else HudColors.Brass, RoundedCornerShape(6.dp))
                            .clickable { onCategory(entry) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = iconLabel,
                            color = if (active) HudColors.Ink else HudColors.Cream,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        if (inspected != null && state.selectedItem == null) {
            TicketPriceRow(inspected, onTicketPrice)
            Spacer(modifier = Modifier.height(3.dp))
        } else if (gateOpen && state.selectedItem == null) {
            EntranceFeeRow(state, onEntranceFee)
            Spacer(modifier = Modifier.height(3.dp))
        }

        // Row 2: items
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
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

/**
 * The manager's portrait in a brass frame, mirroring the little character the original
 * keeps tucked into the corner of the interface. Inline — the day counter already lives
 * in the top strip, and a stacked label here cost a whole row of park height.
 */
@Composable
private fun ManagerPortrait(state: GameState) {
    val portrait = remember { TextureAtlas.portrait() }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(HudColors.PanelLight)
            .border(2.dp, HudColors.Brass, RoundedCornerShape(6.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = portrait,
            contentDescription = "Park manager",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
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
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val icon = remember(item, density) { ParkIcons.icon(item, density, measurer) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) HudColors.Cream else HudColors.BrassDark,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Image(
            bitmap = icon,
            contentDescription = item.displayName,
            modifier = Modifier.size(22.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = if (item.cost == 0) "free" else "£${item.cost}",
            color = if (affordable) HudColors.Cream else HudColors.Bad,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = item.displayName,
            color = HudColors.Cream.copy(alpha = 0.8f),
            fontSize = 7.sp,
            lineHeight = 8.sp,
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

/** Compact inspector: name, visits so far, and − / + controls for the ticket price. */
@Composable
private fun TicketPriceRow(structure: Structure, onTicketPrice: (String, Int) -> Unit) {
    val item = structure.item
    PriceRow(
        label = "${item.displayName} · ${structure.lifetimeVisitors} visits · fair £${item.price}",
        price = structure.ticketPrice,
        ratio = structure.priceRatio,
        canLower = structure.ticketPrice > 0,
        canRaise = structure.ticketPrice < item.maxTicketPrice,
        onLower = { onTicketPrice(structure.id, structure.ticketPrice - 1) },
        onRaise = { onTicketPrice(structure.id, structure.ticketPrice + 1) }
    )
}

/** Gate controls. A better-rated park can charge more before guests balk. */
@Composable
private fun EntranceFeeRow(state: GameState, onEntranceFee: (Int) -> Unit) {
    val rating = state.stats.rating
    PriceRow(
        label = "Entrance · ${state.todayVisitors} guests today · fair £${fairEntranceFee(rating)}",
        price = state.entranceFee,
        ratio = gatePriceRatio(state.entranceFee, rating),
        canLower = state.entranceFee > 0,
        canRaise = state.entranceFee < GameEngine.MAX_ENTRANCE_FEE,
        onLower = { onEntranceFee(state.entranceFee - 1) },
        onRaise = { onEntranceFee(state.entranceFee + 1) }
    )
}

@Composable
private fun PriceRow(
    label: String,
    price: Int,
    ratio: Float,
    canLower: Boolean,
    canRaise: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit
) {
    val verdict = when {
        ratio > 2f -> "Rip-off!" to HudColors.Bad
        ratio > 1.5f -> "Pricey" to HudColors.Bad
        ratio < 0.75f -> "Bargain" to HudColors.Good
        else -> "Fair" to HudColors.Cream
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(HudColors.Panel)
            .border(1.dp, HudColors.BrassDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = HudColors.Cream,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = verdict.first,
            color = verdict.second,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        PriceStepButton("−", canLower, onLower)
        Text(
            text = "£$price",
            color = HudColors.Brass,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp)
        )
        PriceStepButton("+", canRaise, onRaise)
    }
}

@Composable
private fun PriceStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) HudColors.Brass else HudColors.PanelDark)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (enabled) HudColors.Ink else HudColors.Cream.copy(alpha = 0.4f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
