package be.jeedomtv.view

import androidx.compose.ui.graphics.Color
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType

/*
 * Habillage des pages (onglets, tuiles, puces) : choix purement visuels, testables sans Compose.
 */

/** Accent chaud d'une tuile allumée ou active : ambre, à la place de l'ancien jaune plein. */
val ActiveAmber = Color(0xFFFFB74D)

/** Icône MDI d'une icône de tuile du contrat ; [on] : variante « allumée » quand elle existe. */
fun TileIcon.mdiName(on: Boolean = false): String = when (this) {
    TileIcon.Light -> if (on) "mdi:lightbulb-on" else "mdi:lightbulb"
    TileIcon.Plug -> "mdi:power-socket-eu"
    TileIcon.Shutter -> "mdi:window-shutter"
    TileIcon.Thermostat -> "mdi:thermostat"
    TileIcon.Temperature -> "mdi:thermometer"
    TileIcon.Scene -> "mdi:palette"
    TileIcon.Fan -> "mdi:fan"
    TileIcon.Lock -> "mdi:lock"
    TileIcon.Alarm -> "mdi:shield-home"
    TileIcon.Camera -> "mdi:cctv"
    TileIcon.Sun -> "mdi:white-balance-sunny"
    TileIcon.Rain -> "mdi:weather-rainy"
    TileIcon.Trash -> "mdi:trash-can-outline"
    TileIcon.Power -> "mdi:power"
    TileIcon.Generic -> "mdi:home-automation"
}

/** Icône d'une tuile : celle du contrat ; une liste ou un bouton sans icône prend celle de son type. */
fun tileMdiIcon(tile: Tile): String = when {
    tile.icon != TileIcon.Generic -> tile.icon.mdiName(on = tile.isActive)
    tile.type == TileType.Select -> "mdi:format-list-bulleted"
    tile.type == TileType.Button -> "mdi:gesture-tap-button"
    tile.type == TileType.Scene -> "mdi:palette"
    else -> TileIcon.Generic.mdiName()
}

/** Tuile « allumée » : un interrupteur à l'état allumé. Elle prend l'accent ambre. */
val Tile.isActive: Boolean
    get() = type == TileType.Switch && isOn

/**
 * Valeur ou état affiché en haut à droite de la tuile ; null s'il n'y en a pas (scène : l'icône
 * et le nom suffisent). Liste de choix : le choix courant suivi d'un chevron.
 */
fun tileCornerText(tile: Tile): String? = when (tile.type) {
    TileType.Scene -> null
    TileType.Select -> "${tile.choiceLabel ?: "—"} ›"
    else -> tileValueText(tile).takeIf { it.isNotBlank() }
}

/**
 * Position dans [min, max] (0 à 1) pour la petite jauge d'un volet ou la barre d'un curseur ;
 * null sans bornes ou sans valeur numérique.
 */
fun tileFraction(tile: Tile): Float? {
    if (tile.type != TileType.Shutter && tile.type != TileType.Slider) return null
    val min = tile.min ?: return null
    val max = tile.max ?: return null
    val value = tile.numericValue ?: return null
    if (max <= min) return null
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

/**
 * Icône MDI d'une page, devant son nom dans les onglets : d'après son nom (Lumières, Volets…),
 * sinon d'après ses tuiles si elles sont toutes du même genre ; null sinon (pas d'icône).
 */
fun pageMdiIcon(page: Page): String? {
    val name = page.name.lowercase()
    val byName = listOf(
        listOf("lumi", "éclair", "eclair", "lampe") to "mdi:lightbulb-group",
        listOf("volet", "store") to "mdi:window-shutter",
        listOf("chauff", "clim", "thermostat") to "mdi:thermostat",
        listOf("tempér", "temper") to "mdi:thermometer",
        listOf("caméra", "camera", "vidéo") to "mdi:cctv",
        listOf("scénario", "scenario", "scène", "scene", "ambiance") to "mdi:palette",
    ).firstOrNull { (words, _) -> words.any { it in name } }?.second
    if (byName != null) return byName
    val tiles = page.tiles
    if (tiles.isEmpty()) return null
    return when {
        tiles.all { it.type == TileType.Scene } -> "mdi:palette"
        tiles.all { it.icon == TileIcon.Camera } -> "mdi:cctv"
        tiles.all { it.icon == TileIcon.Light } -> "mdi:lightbulb-group"
        tiles.all { it.type == TileType.Shutter } -> "mdi:window-shutter"
        tiles.all { it.icon == TileIcon.Temperature } -> "mdi:thermometer"
        tiles.all { it.icon == TileIcon.Thermostat } -> "mdi:thermostat"
        else -> null
    }
}
