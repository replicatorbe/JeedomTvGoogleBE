#!/usr/bin/env bash
#
# Accorde puis vérifie, par adb, toutes les permissions de Jeedom TV (contrat docs/api.md,
# « Présence humaine et allumage », section « Installation »). Sans root.
#
# Idempotent : à relancer après chaque `adb install -r`, qui peut réinitialiser des autorisations
# (le service d'accessibilité en particulier). Rien n'est affiché sur l'écran de la TV.
#
#   tools/setup-tv.sh <IP_TV>:5555
#   ADB=/chemin/vers/adb tools/setup-tv.sh <IP_TV>:5555
#
# Code de sortie : 0 si tout est accordé, 1 si une permission manque encore (détail affiché).

set -euo pipefail

SERIAL="${1:-}"
if [[ -z "$SERIAL" ]]; then
    echo "usage : $0 <ip:port>" >&2
    exit 2
fi
ADB="${ADB:-adb}"
PKG="be.jeedomtv"
A11Y="$PKG/$PKG.ColorKeyService"
LISTENER="$PKG/$PKG.presence.MediaListenerService"
ADMIN="$PKG/.presence.AdminReceiver"

"$ADB" connect "$SERIAL" >/dev/null 2>&1 || true

# Commande sur la TV ; la sortie est nettoyée des retours chariot d'adb.
tv() {
    "$ADB" -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'
}

# Sorties lues en entier avant comparaison (pas de grep -q dans un tube : pipefail et SIGPIPE).
# dumpsys écrit le composant sous forme courte (be.jeedomtv/.presence.AdminReceiver) ou longue.
admin_on() {
    local dump
    dump="$(tv dumpsys device_policy)"
    [[ "$dump" == *"$ADMIN"* || "$dump" == *"$PKG/$PKG.presence.AdminReceiver"* ]]
}

if [[ "$(tv pm path "$PKG")" != package:* ]]; then
    echo "Jeedom TV ($PKG) n'est pas installée sur $SERIAL." >&2
    exit 2
fi
SDK="$(tv getprop ro.build.version.sdk)"
echo "TV $SERIAL — Android SDK $SDK"

# --- Octroi --------------------------------------------------------------------------------------

# Accessibilité : ajoutée à la liste existante (les autres services restent activés).
current="$(tv settings get secure enabled_accessibility_services)"
if [[ "$current" == "null" || -z "$current" ]]; then
    wanted="$A11Y"
elif [[ ":$current:" == *":$A11Y:"* ]]; then
    wanted="$current"
else
    wanted="$current:$A11Y"
fi
if [[ "$wanted" != "$current" ]]; then
    tv settings put secure enabled_accessibility_services "'$wanted'" >/dev/null
fi
tv settings put secure accessibility_enabled 1 >/dev/null

tv appops set "$PKG" GET_USAGE_STATS allow >/dev/null
tv cmd notification allow_listener "$LISTENER" >/dev/null
tv appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null
# TURN_SCREEN_ON n'existe qu'à partir d'Android 14 (SDK 34).
if (( SDK >= 34 )); then
    tv appops set "$PKG" TURN_SCREEN_ON allow >/dev/null
fi
# Administrateur de l'appareil : une seconde activation échoue (« already active ») ; on vérifie d'abord.
if ! admin_on; then
    admin_out="$(tv dpm set-active-admin "$ADMIN" || true)"
    [[ -n "$admin_out" ]] && echo "dpm : $admin_out"
fi
tv dumpsys deviceidle whitelist "+$PKG" >/dev/null
# Fonctionnement en arrière-plan (README, « Indispensables ») ; APP_AUTO_START est propre à TCL.
tv appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null || true
tv appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null || true
tv appops set "$PKG" APP_AUTO_START allow >/dev/null 2>&1 || true

# --- Vérification --------------------------------------------------------------------------------

missing=0
check() { # libellé, commande de vérification (code 0 = accordée)
    local label="$1"
    shift
    if "$@"; then
        printf '  OK        %s\n' "$label"
    else
        printf '  MANQUANTE %s\n' "$label"
        missing=1
    fi
}
appop_allowed() { [[ "$(tv appops get "$PKG" "$1")" == *"$1: allow"* ]]; }
accessibility_on() { [[ ":$(tv settings get secure enabled_accessibility_services):" == *":$A11Y:"* ]]; }
listener_on() { [[ ":$(tv settings get secure enabled_notification_listeners):" == *":$LISTENER:"* ]]; }

battery_exempt() { [[ "$(tv dumpsys deviceidle whitelist)" == *",$PKG,"* ]]; }

# Les réglages (écouteur de notifications) sont écrits de façon asynchrone.
sleep 2
echo "Vérification :"
check "Accessibilité ($A11Y)" accessibility_on
check "Statistiques d'utilisation (GET_USAGE_STATS)" appop_allowed GET_USAGE_STATS
check "Écouteur de notifications ($LISTENER)" listener_on
check "Afficher par-dessus (SYSTEM_ALERT_WINDOW)" appop_allowed SYSTEM_ALERT_WINDOW
if (( SDK >= 34 )); then
    check "Allumer l'écran (TURN_SCREEN_ON)" appop_allowed TURN_SCREEN_ON
else
    printf '  –         %s\n' "Allumer l'écran (TURN_SCREEN_ON) : sans objet avant Android 14"
fi
check "Administrateur de l'appareil ($ADMIN)" admin_on
check "Exemption d'économie d'énergie (deviceidle)" battery_exempt

if (( missing )); then
    echo "Des permissions manquent (voir ci-dessus). L'écran de configuration de la TV (touche Menu) en donne aussi l'état."
    exit 1
fi
echo "Tout est accordé. L'application relit les permissions toutes les 5 s (écran allumé)."
