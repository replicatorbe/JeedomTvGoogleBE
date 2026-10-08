package be.jeedomtv.view

import be.jeedomtv.model.Board
import be.jeedomtv.model.BoardSection
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus
import org.junit.Assert.assertEquals
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
}
