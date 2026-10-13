package be.jeedomtv.view

import be.jeedomtv.model.Board
import be.jeedomtv.model.BoardSection
import be.jeedomtv.model.BoardWeather
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus
import be.jeedomtv.model.WeatherHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Test

/** Textes du tableau des trains : état, heure réelle, voie, correspondances, heure de lecture. */
class BoardTextTest {

    @Test
    fun `etat - a l'heure, retard avec l'heure reelle, supprime`() {
        val late = Train("07:09", "07:13", 4, status = TrainStatus.Delayed)
        assertEquals("+4 min", trainStatusText(late))
        assertEquals("(07:13)", trainRealText(late))
        val onTime = Train("07:53", status = TrainStatus.OnTime)
        assertEquals("à l'heure", trainStatusText(onTime))
        assertNull(trainRealText(onTime))
        val canceled = Train("07:38", "07:45", 7, status = TrainStatus.Canceled)
        assertEquals("Supprimé", trainStatusText(canceled))
        assertNull("pas d'heure réelle pour un train supprimé", trainRealText(canceled))
    }

    @Test
    fun `voie et correspondances`() {
        assertEquals("voie 3", platformText(Train("07:21", platform = "3")))
        assertNull(platformText(Train("07:21")))
        assertEquals("1 corresp.", transfersText(Train("07:21", transfers = 1)))
        assertNull(transfersText(Train("07:21")))
    }

    @Test
    fun `verifie a - une heure, un intervalle, ou rien`() {
        fun board(vararg updated: String) = Board(updated.mapIndexed { i, u -> BoardSection("b$i", "T", updated = u) })
        assertEquals("vérifié à 07:12", boardUpdatedText(board("07:12", "07:12")))
        assertEquals("vérifié entre 07:11 et 07:12", boardUpdatedText(board("07:12", "", "07:11")))
        assertEquals("", boardUpdatedText(board("")))
        assertEquals("", boardUpdatedText(Board()))
    }

    @Test
    fun `mise en page - grandes lignes avec peu de trains, moins de trains plutot que des lettres trop petites`() {
        fun section(trains: Int, notes: Int = 0) =
            BoardSection("b", "T", notes = List(notes) { "n" }, trains = List(trains) { Train("07:0$it") })
        // 960 × 540 dp : une TV 1080p de densité 2 comme une TV 720p de densité 213.
        val height = 540.dp - 44.dp
        val few = boardFit(Board(listOf(section(4, 1), section(2))), height)
        assertTrue(few.row > 44.dp)
        assertEquals(4, few.trainsPerSection)
        val usual = boardFit(Board(listOf(section(6, 2), section(6, 2))), height)
        assertTrue(usual.row >= 26.dp)
        assertEquals(5, usual.trainsPerSection)
        val max = boardFit(Board(List(3) { section(6, 2) }), height)
        assertTrue(max.row >= 26.dp)
        assertTrue(max.trainsPerSection in 1..3)
        assertEquals(1, boardFit(Board(), height).trainsPerSection)
        // Bandeau d'un `notify` par-dessus la télé : sa place est retirée aux lignes.
        val banner = boardFit(Board(listOf(section(6, 2), section(6, 2))), height, reserved = 42.dp)
        assertTrue(banner.row < usual.row || banner.trainsPerSection < usual.trainsPerSection)
    }

    @Test
    fun `trains montres - le prochain et les suivants d'abord, un seul prochain`() {
        val trains = List(6) { Train("07:0$it", next = it == 3) }
        assertEquals(listOf("07:03", "07:04"), boardTrains(trains, 2).map { it.time })
        assertEquals("place en plus : ceux d'avant", listOf("07:02", "07:03", "07:04", "07:05"), boardTrains(trains, 4).map { it.time })
        assertEquals(trains, boardTrains(trains, 6))
        val noNext = List(4) { Train("07:1$it") }
        assertEquals(listOf("07:10", "07:11"), boardTrains(noNext, 2).map { it.time })
        val last = List(4) { Train("07:2$it", next = it == 3) }
        assertEquals(listOf("07:22", "07:23"), boardTrains(last, 2).map { it.time })
        val twice = List(3) { Train("07:3$it", next = it > 0) }
        assertEquals(listOf(false, true, false), boardTrains(twice, 3).map { it.next })
        assertEquals(listOf("07:31"), boardTrains(twice, 1).map { it.time })
        assertEquals(emptyList<Train>(), boardTrains(emptyList(), 2))
    }

    private val contractWeather = BoardWeather(
        city = "Soignies", temp = 14, icon = "mdi:weather-cloudy", condition = "Couvert", tmin = 7, tmax = 21,
        rain = "Risque de précipitations dans 2h40", warning = "Vigilance jaune — Brouillard", warningLevel = 1,
        hours = listOf(WeatherHour("09", "mdi:weather-cloudy", 15, 0), WeatherHour("15", "mdi:weather-rainy", 20, 90)),
    )

    @Test
    fun `meteo - temperatures, heures, risque de pluie`() {
        assertEquals("14°", weatherTempText(14))
        assertEquals("-3°", weatherTempText(-3))
        assertEquals("", weatherTempText(null))
        assertEquals("↓7° ↑21°", weatherRangeText(7, 21))
        assertEquals("↑21°", weatherRangeText(null, 21))
        assertEquals("↓7°", weatherRangeText(7, null))
        assertEquals("", weatherRangeText(null, null))
        assertEquals("09h", weatherHourText("09"))
        assertEquals("09h", weatherHourText("9"))
        assertEquals("15h", weatherHourText(" 15 "))
        assertEquals("ce soir", weatherHourText("ce soir"))
        assertEquals("30 %", weatherRainText(30))
        assertNull(weatherRainText(0))
        assertNull(weatherRainText(null))
        assertFalse(weatherRainAlert(49))
        assertTrue(weatherRainAlert(50))
        assertFalse(weatherRainAlert(null))
    }

    @Test
    fun `meteo - alertes de la ligne 1 et couleur de la vigilance`() {
        assertEquals(
            listOf("Risque de précipitations dans 2h40" to BoardColors.Rain, "Vigilance jaune — Brouillard" to BoardColors.Yellow),
            weatherAlerts(contractWeather),
        )
        assertEquals(BoardColors.Delayed, weatherWarningColor(2))
        assertEquals(BoardColors.Canceled, weatherWarningColor(3))
        assertEquals(BoardColors.Slight, weatherWarningColor(0))
        assertEquals(listOf("Météo non relue" to BoardColors.Slight), weatherAlerts(BoardWeather(note = "Météo non relue")))
    }

    @Test
    fun `meteo - lignes de la bande`() {
        assertEquals(0, weatherBandLines(null))
        assertEquals("météo vide : comme sans météo", 0, weatherBandLines(BoardWeather()))
        assertEquals("note seule : une ligne", 1, weatherBandLines(BoardWeather(note = "IRM absent")))
        assertEquals(1, weatherBandLines(contractWeather.copy(hours = emptyList())))
        assertEquals(2, weatherBandLines(contractWeather))
        assertEquals(0.dp, weatherBandHeight(BoardWeather()))
        assertTrue(weatherBandHeight(contractWeather) > weatherBandHeight(BoardWeather(note = "n")))
    }

    @Test
    fun `mise en page - la bande meteo prend la place de trains, TV 1080p densite 2`() {
        fun section(trains: Int, notes: Int = 0) =
            BoardSection("b", "T", notes = List(notes) { "n" }, trains = List(trains) { Train("07:0$it") })
        val height = 540.dp - 44.dp
        val board = Board(listOf(section(6), section(6)))
        val without = boardFit(board, height)
        assertEquals(6, without.trainsPerSection)
        val with = boardFit(board, height, reserved = weatherBandHeight(contractWeather))
        assertEquals("2 sections de 6 trains et la bande : 5 trains par section", 5, with.trainsPerSection)
        assertTrue(with.row >= 26.dp)
        val noteOnly = boardFit(board, height, reserved = weatherBandHeight(BoardWeather(note = "n")))
        assertEquals("note seule : une ligne, les 6 trains tiennent encore", 6, noteOnly.trainsPerSection)
        val notes = boardFit(Board(listOf(section(6, 2), section(6, 2))), height, reserved = weatherBandHeight(contractWeather))
        assertEquals(4, notes.trainsPerSection)
        // Par-dessus la télé, avec le bandeau d'un `notify` en plus.
        val overlay = boardFit(board, height, reserved = weatherBandHeight(contractWeather) + 42.dp)
        assertTrue(overlay.row >= 26.dp)
        assertTrue(overlay.trainsPerSection in 1..5)
    }
}
