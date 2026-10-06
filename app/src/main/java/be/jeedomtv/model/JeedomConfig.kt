package be.jeedomtv.model

/** Paramètres de connexion au plugin Jeedom, saisis sur l'écran de configuration. */
data class JeedomConfig(
    /** Adresse IP ou nom d'hôte de Jeedom, éventuellement suivi de `:port`. */
    val host: String,
    /** Clé de la TV, affichée sur la page de l'équipement `jeetvbe`. */
    val key: String,
) {
    companion object {
        const val DEFAULT_HOST = "192.168.1.10"
    }
}
