package be.jeedomtv.controller

/**
 * Allumer et éteindre l'écran, vu du contrôleur sans dépendance Android : sur la TV, une activité
 * qui allume l'écran et l'administrateur de l'appareil ; dans les tests, un faux.
 */
interface ScreenPower {
    /** Allume l'écran ; false si c'est impossible (permission manquante). */
    fun wake(): Boolean

    /** Met l'écran en veille ; false si c'est impossible. */
    fun sleep(): Boolean

    /** Sans moyen d'agir sur l'écran (tests, ou avant le branchement). */
    object None : ScreenPower {
        override fun wake() = false
        override fun sleep() = false
    }
}
