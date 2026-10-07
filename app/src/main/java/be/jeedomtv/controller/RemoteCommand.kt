package be.jeedomtv.controller

import be.jeedomtv.model.ColorKey

/** Commandes de la télécommande, indépendantes des KeyEvent Android. */
sealed interface RemoteCommand {
    data object Up : RemoteCommand
    data object Down : RemoteCommand
    data object Left : RemoteCommand
    data object Right : RemoteCommand
    data object Ok : RemoteCommand
    data object Back : RemoteCommand
    data object ChannelUp : RemoteCommand
    data object ChannelDown : RemoteCommand
    data object Menu : RemoteCommand
    data class Digit(val value: Int) : RemoteCommand

    /** Touche de couleur : raccourci vers une page, même quand une autre application est affichée. */
    data class Color(val key: ColorKey) : RemoteCommand
}
