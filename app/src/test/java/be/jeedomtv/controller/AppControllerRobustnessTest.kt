package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileType
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cas limites de la revue de code : réponses d'`exec` tardives, ordres livrés pendant un layout
 * impossible à recharger, boucle annulée, changement de TV, layout qui déplace les tuiles.
 */
class AppControllerRobustnessTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        visible: Boolean = true,
        permission: Boolean = false,
        pageIndex: Int = 0,
    ): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = visible, pageIndex = pageIndex)),
            FakeSettings(stored = config),
            factory,
            backgroundScope,
            OverlayPermission { permission },
        )
        c.start()
        runCurrent()
        return c
    }

    private fun AppController.tile(id: String) = state.value.findTile(id)!!

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    /** Les réponses d'`exec` attendent que le test les libère, dans l'ordre des appels. */
    private fun FakeDriverFactory.pendingExecs(): MutableList<CompletableDeferred<String?>> {
        val pending = mutableListOf<CompletableDeferred<String?>>()
        onExec = { CompletableDeferred<String?>().also { pending += it }.await() }
        return pending
    }

    // --- Réponses d'exec tardives --------------------------------------------------------------

    @Test
    fun `reponse d'exec tardive - n'ecrase pas la valeur plus recente livree par changes`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Ok) // t1 allumé → optimiste « 0 »
        runCurrent()
        assertEquals("0", c.tile("t1").value)

        // Quelqu'un rallume aussitôt la lampe : changes a raison.
        factory.pushChanges(changes("1", "9f2c1a", "t1" to "1"))
        runCurrent()
        pending[0].complete("0") // Valeur lue juste après l'ordre, déjà périmée.
        runCurrent()
        assertEquals("1", c.tile("t1").value)
    }

    @Test
    fun `deux bascules rapides - la reponse de la premiere n'ecrase pas la seconde`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Ok, Ok) // « 1 » → « 0 » → « 1 »
        runCurrent()
        assertEquals("1", c.tile("t1").value)

        pending[0].complete("0")
        runCurrent()
        assertEquals("la réponse du premier appui est périmée", "1", c.tile("t1").value)
        pending[1].complete("1")
        runCurrent()
        assertEquals("1", c.tile("t1").value)
    }

    @Test
    fun `erreur d'exec apres confirmation par changes - pas de retour a l'ancienne valeur`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Ok) // optimiste « 0 »
        runCurrent()
        factory.pushChanges(changes("1", "9f2c1a", "t1" to "0")) // Jeedom confirme : éteinte.
        runCurrent()
        pending[0].completeExceptionally(JeedomException("Jeedom ne répond pas (192.168.1.10)"))
        runCurrent()
        assertEquals("la lampe est bien éteinte", "0", c.tile("t1").value)
        assertEquals("Jeedom ne répond pas (192.168.1.10)", c.state.value.notice)
    }

    @Test
    fun `erreur d'exec sans nouvelle valeur - retour a l'ancienne valeur`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Ok)
        runCurrent()
        pending[0].completeExceptionally(JeedomException("Commande refusée"))
        runCurrent()
        assertEquals("1", c.tile("t1").value)
    }

    @Test
    fun `consigne - reponse tardive ignoree apres une valeur de changes`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Right, Right, Right, Ok, Up, Ok) // t4 : 20.5 → 21, envoyé
        runCurrent()
        factory.pushChanges(changes("1", "9f2c1a", "t4" to "21"))
        runCurrent()
        pending[0].complete("20.5") // Lue avant que le thermostat ne prenne la consigne.
        runCurrent()
        assertEquals("21", c.tile("t4").value)
    }

    @Test
    fun `reponse d'exec ignoree apres un nouveau layout`() = runTest {
        val factory = FakeDriverFactory()
        val pending = factory.pendingExecs()
        val c = started(factory)
        c.press(Ok)
        runCurrent()
        factory.onLayout = { contractLayout(revision = "r2") }
        factory.pushChanges(changes("1", "r2"))
        runCurrent()
        assertEquals("valeur du layout frais", "1", c.tile("t1").value)
        pending[0].complete("0")
        runCurrent()
        assertEquals("1", c.tile("t1").value)
    }

    @Test
    fun `exec - tuile inconnue (404), le layout est recharge`() = runTest {
        val factory = FakeDriverFactory()
        factory.onExec = { throw JeedomException("Tuile inconnue", httpCode = 404) }
        val c = started(factory)
        val before = factory.layoutCount
        c.press(Ok)
        runCurrent()
        assertEquals(before + 1, factory.layoutCount)
        assertEquals("Tuile inconnue", c.state.value.notice)
    }

    // --- Boucle des changements ----------------------------------------------------------------

    @Test
    fun `layout impossible a recharger - les ordres de la meme reponse ne sont pas perdus`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.onLayout = { throw JeedomException("Jeedom injoignable") }
        val ask = TvCommand.Ask(7, "jeton", "", "Ouvrir le portail ?", listOf("Ignorer", "Ouvrir"), 30)
        factory.pushChanges(commands("1", ask, revision = "r2"))
        runCurrent()
        assertNotNull("la question est posée malgré le layout", c.state.value.question)
        assertTrue("la boucle repart : hors ligne", c.state.value.offline)
    }

    @Test
    fun `boucle annulee pendant l'attente - pas de faux hors ligne`() = runTest {
        val factory = FakeDriverFactory()
        // Pilote réel : un appel annulé peut se terminer par une erreur réseau plutôt qu'une annulation.
        val real = factory.create(config)
        val wrapped = object : be.jeedomtv.model.driver.JeedomDriver by real {
            override suspend fun changes(since: String?) = try {
                awaitCancellation()
            } catch (e: CancellationException) {
                throw JeedomException("Jeedom injoignable (192.168.1.10)")
            }
        }
        val c2 = AppController(
            AppModel(AppState(uiVisible = true)),
            FakeSettings(stored = config),
            { wrapped },
            backgroundScope,
        )
        c2.start()
        runCurrent()
        c2.onScreenChanged(true) // Réveil : la boucle est relancée.
        runCurrent()
        assertFalse(c2.state.value.offline)
    }

    @Test
    fun `pause croissante entre les essais, 30 s au plus`() {
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L), (0..5).map { retryDelayMs(it) })
    }

    // --- Dédoublonnage des ordres --------------------------------------------------------------

    @Test
    fun `autre TV (nouvelle cle) - ses ordres ne sont pas pris pour des doublons`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.pushChanges(commands("1", TvCommand.Notify(5, "", "premier")))
        runCurrent()

        c.submitSetup(JeedomConfig(host = "192.168.1.10", key = "autre-cle"))
        runCurrent()
        c.onUiVisibilityChanged(true)
        factory.pushChanges(commands("2", TvCommand.Notify(5, "", "autre TV")))
        runCurrent()
        assertEquals("autre TV", c.state.value.banner?.message)
    }

    @Test
    fun `meme TV - un id deja traite reste ignore apres une reconnexion`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.pushChanges(commands("1", TvCommand.Notify(5, "", "premier")))
        runCurrent()
        c.submitSetup(config)
        runCurrent()
        factory.pushChanges(commands("2", TvCommand.Notify(5, "", "doublon")))
        runCurrent()
        assertEquals("premier", c.state.value.banner?.message)
    }

    // --- Nouveau layout ------------------------------------------------------------------------

    private fun tile(id: String) = Tile(id, TileType.Info, id.uppercase())

    @Test
    fun `nouveau layout - la selection suit la tuile deplacee`() {
        val state = AppState(
            pages = listOf(Page("p1", "Salon", listOf(tile("a"), tile("b"), tile("c")))),
            focusedIndex = 2,
        )
        val next = state.withLayout(Layout("r2", listOf(Page("p1", "Salon", listOf(tile("c"), tile("a"), tile("b"))))))
        assertEquals(0, next.focusedIndex)
    }

    @Test
    fun `nouveau layout - tuile selectionnee retiree, la selection reste dans les bornes`() {
        val state = AppState(
            pages = listOf(Page("p1", "Salon", listOf(tile("a"), tile("b"), tile("c")))),
            focusedIndex = 2,
        )
        val next = state.withLayout(Layout("r2", listOf(Page("p1", "Salon", listOf(tile("a"), tile("b"))))))
        assertEquals(1, next.focusedIndex)
    }

    @Test
    fun `nouveau layout - page affichee retiree, premiere tuile de la page voisine`() {
        val state = AppState(
            pages = listOf(
                Page("p1", "Salon", listOf(tile("a"))),
                Page("p2", "Cuisine", listOf(tile("b"), tile("c"), tile("d"))),
            ),
            pageIndex = 1,
            focusedIndex = 2,
        )
        val next = state.withLayout(
            Layout("r2", listOf(Page("p1", "Salon", listOf(tile("a"))), Page("p3", "Garage", listOf(tile("e"), tile("f"), tile("g")))))
        )
        assertEquals(1, next.pageIndex)
        assertEquals(0, next.focusedIndex)
    }

    @Test
    fun `panneau ferme apres un layout qui deplace les pages - la page d'avant est retrouvee par son id`() = runTest {
        val factory = FakeDriverFactory()
        // L'application était restée sur « Cuisine » (p2).
        val c = started(factory, visible = false, permission = true, pageIndex = 1)
        assertEquals("p2", c.state.value.currentPage?.id)

        factory.pushChanges(commands("3", TvCommand.Show(3, "p3")))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        // Jeedom retire la page « Salon » : « Cuisine » passe en tête.
        val layout = contractLayout(revision = "r2")
        factory.onLayout = { layout.copy(pages = layout.pages.drop(1)) }
        factory.pushChanges(changes("4", "r2"))
        runCurrent()
        c.press(Back)
        assertEquals(Overlay.None, c.state.value.overlay)
        assertEquals("p2", c.state.value.currentPage?.id)
    }

    @Test
    fun `Jeedom injoignable en arriere-plan - pas de rafale d'essais`() = runTest {
        val factory = FakeDriverFactory()
        started(factory, visible = false)
        repeat(4) { factory.failChanges(JeedomException("Jeedom injoignable")) }
        runCurrent()
        advanceTimeBy(3_100)
        assertEquals("un essai après 3 s", 2, factory.changesCalls.size)
        advanceTimeBy(5_000)
        assertEquals("le suivant après 6 s", 2, factory.changesCalls.size)
        advanceTimeBy(1_000)
        assertEquals(3, factory.changesCalls.size)
    }
}
