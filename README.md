# Jeedom TV

Application Android TV (Google TV) pour afficher et piloter à la télécommande des tuiles domotiques servies par le plugin Jeedom `jeetvbe`, sur le réseau local.

Le contrat entre l'application et le plugin est décrit dans [docs/api.md](docs/api.md). Il fait foi pour les deux côtés.

## Fonctionnalités

- Pages de tuiles définies dans Jeedom, une rangée d'onglets en haut, une grille de 4 colonnes en dessous.
- Types de tuiles :
  - **interrupteur** : allumer / éteindre, fond ambré quand il est allumé ;
  - **volet** : réglage de la position, ou monter / descendre / stop pour un volet sans retour de position ;
  - **curseur** : réglage d'une consigne entre un minimum et un maximum ;
  - **info** : affichage d'une valeur avec son unité ;
  - **scénario** : lancement, avec un bref retour visuel.
- Valeurs en direct : l'application attend les changements de Jeedom (attente longue) tant qu'elle est affichée.
  - La grille se recharge seule quand les pages changent dans Jeedom.
  - Un indicateur « hors ligne » discret s'affiche quand Jeedom ne répond plus. L'application réessaie toutes les 3 s.
- Confirmation avant tout ordre sur une tuile marquée « confirmer » dans Jeedom.
- La TV ne connaît aucun id de commande Jeedom : une clé volée ne pilote que les tuiles de cette TV.

| Touche | Grille | Mode réglage (curseur, volet avec position) | Volet sans position |
|---|---|---|---|
| Flèches | Déplacer la sélection | ▲ ▼ : ± un pas · ◀ ▶ : min / max | ▲ monter · ▼ descendre |
| OK | Agir sur la tuile (voir ci-dessous) | Envoyer la valeur et sortir | Stop |
| 1 à 9 | Agir sur la tuile N de la page | – | – |
| CH+ / CH- | Page suivante / précédente (en boucle) | Volet : monter / descendre | Monter / descendre |
| Menu | Configuration | Configuration | Configuration |
| Retour | Quitter | Annuler | Sortir |

OK selon la tuile :

| Tuile | Effet |
|---|---|
| Interrupteur | Bascule. L'affichage change tout de suite, puis Jeedom le corrige si besoin. En cas d'erreur, l'ancienne valeur revient et un message s'affiche 4 s. |
| Scénario | Lance le scénario. |
| Curseur, volet | Entre en mode réglage. La valeur en attente part de la valeur actuelle. |
| Info | Rien. |

Boîte de confirmation : OK confirme, Retour annule.

Un bandeau en bas de l'écran rappelle les touches du contexte.

## Architecture (MVC)

```
app/src/main/java/be/jeedomtv/
├── JeedomTvApp   Racine de composition : Modèle et Contrôleur vivent aussi longtemps que le processus
├── model/        État de l'application (AppModel, AppState), configuration, pages et tuiles
│   └── driver/   Interface JeedomDriver + implémentation HTTP (OkHttp, kotlinx.serialization)
├── controller/   AppController (écrans, sélection, réglage, confirmation, changements en direct)
│                 et RemoteKeyMapper (touches → commandes)
└── view/         Écrans Compose for TV : configuration, chargement, pages, tuile, confirmation
```

- Le **contrôleur** reçoit les commandes de la télécommande et met à jour le **modèle**. Il ne touche jamais à Android.
- La **vue** observe le modèle et se redessine. La sélection (page, tuile) est portée par l'état, pas par le focus Compose.
- Jeedom est accessible via l'interface `JeedomDriver`. Les tests du contrôleur utilisent un pilote factice.

Stack : Kotlin, Jetpack Compose for TV, OkHttp, kotlinx.serialization, DataStore.

## Compiler et installer

Prérequis : Android SDK (API 35) et JDK 17 ou plus.

```bash
./gradlew :app:testDebugUnitTest      # tests unitaires
./gradlew :app:assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
```

Installation sur la TV :

1. Activer les options développeur sur la TV, puis le débogage réseau.
2. Installer et lancer l'app :

```bash
adb connect <IP_TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Au premier lancement, l'écran de configuration demande :

- l'adresse de Jeedom (par défaut `192.168.1.10`, un port est accepté : `jeedom.local:8080`) ;
- la clé de la TV, affichée sur la page de l'équipement Jeedom TV correspondant dans le plugin.

La configuration n'est enregistrée qu'après une connexion réussie.

En build debug, la configuration peut aussi être passée par adb, ce qui évite la saisie au clavier de la TV :

```bash
adb shell am start -n be.jeedomtv/.view.MainActivity \
  --es jeedom_host 192.168.1.10 --es jeedom_key '<CLE_DE_LA_TV>'
```

## Optimiser la TV (adb, sans root)

Réglages utiles sur une TCL Google TV (Android 11). Ils sont réversibles et survivent à un redémarrage. À lancer depuis une machine du réseau, après `adb connect <IP_TV>:5555`.

L'application n'a pas de service au premier plan : elle n'interroge Jeedom que lorsqu'elle est affichée. Les réglages d'arrière-plan de CameraOnTv ne sont donc pas nécessaires ici.

### Libérer de la mémoire (optionnel)

```bash
# Économiseur d'écran / mode ambiant Google TV (~200 Mo)
adb shell settings put secure screensaver_enabled 0          # réactiver : 1

# Applications TCL inutilisées (désactivées, pas désinstallées)
adb shell pm disable-user --user 0 com.tcl.usercenter        # compte TCL
adb shell pm disable-user --user 0 com.tcl.miracast          # Miracast (Chromecast reste disponible)
adb shell pm disable-user --user 0 com.tcl.esticker
adb shell pm disable-user --user 0 tv.wuaki.apptv            # Rakuten TV
# réactiver : adb shell pm enable <paquet>
```

### Interface plus réactive (optionnel)

```bash
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
# valeur d'origine : 1
```

### Bon à savoir

- **TCL a son propre gestionnaire de mémoire** (`com.tcl.guard`). Il peut tuer l'application quand elle est en arrière-plan. Ce n'est pas gênant : au retour, elle se reconnecte et recharge les pages.
