package be.jeedomtv.view

/**
 * Champ « Clé de la TV » : une clé déjà enregistrée s'affiche masquée (`••••••••5381`) tant
 * qu'on n'y touche pas, et elle est alors conservée telle quelle à la validation. Au premier
 * caractère tapé ou effacé, le champ repart vide et en clair (le caractère tapé est gardé).
 */
data class KeyField(
    /** Clé enregistrée (vide au premier lancement). */
    val saved: String,
    /** Saisie en cours ; null tant que le champ n'a pas été modifié. */
    val draft: String? = null,
) {
    val masked: Boolean
        get() = draft == null && saved.isNotEmpty()

    /** Texte affiché dans le champ. */
    val shown: String
        get() = if (masked) mask(saved) else draft.orEmpty()

    /** Clé à enregistrer. */
    val value: String
        get() = draft ?: saved

    /** Nouveau texte du champ. */
    fun edit(text: String): KeyField {
        if (!masked) return copy(draft = text)
        val mask = shown
        // Caractères tapés avant ou après le masque (selon le curseur) : on les garde ;
        // un effacement, qui entame le masque, repart de zéro.
        return copy(draft = if (mask in text) text.replaceFirst(mask, "") else "")
    }

    companion object {
        /** `••••••••` puis les 4 derniers caractères ; tout masqué pour une clé de 4 caractères ou moins. */
        fun mask(key: String): String =
            if (key.length <= 4) "•".repeat(key.length) else "••••••••" + key.takeLast(4)
    }
}
