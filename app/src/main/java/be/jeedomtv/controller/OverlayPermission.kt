package be.jeedomtv.controller

/**
 * Permission « afficher par-dessus les autres applications », vue du contrôleur sans dépendance
 * Android : sur la TV, `Settings.canDrawOverlays` ; dans les tests, une valeur fixe.
 */
fun interface OverlayPermission {
    fun granted(): Boolean
}
