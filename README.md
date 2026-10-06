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
- Pilotage par Jeedom, même pendant un film (service au premier plan, démarré avec la TV) :
  - **Afficher une page** : pendant un film, en [superposition](#superposition-par-dessus-la-vidéo), sans interrompre la vidéo ; sinon dans l'application, avec retour automatique après une durée. Une touche de la télécommande annule le retour ;
  - **Message** : bandeau d'environ 8 s, dans l'application ou par-dessus la vidéo ;
  - **Quitter** : la superposition se ferme, ou l'application passe en arrière-plan.
- Jeedom connaît l'état de la TV : application visible, écran allumé, page affichée.

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
├── view/OverlayWindowManager   Fenêtres de superposition (bandeau, panneau) hors de toute activité
├── JeedomTvService / BootReceiver   Service au premier plan (boucle des changements permanente), démarré avec la TV
├── model/        État de l'application (AppModel, AppState), configuration, pages et tuiles
│   └── driver/   Interface JeedomDriver + implémentation HTTP (OkHttp, kotlinx.serialization)
├── controller/   AppController (écrans, sélection, réglage, confirmation, changements en direct)
│                 ordres de Jeedom, état signalé, et RemoteKeyMapper (touches → commandes)
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
./gradlew :app:assembleRelease        # APK : app/build/outputs/apk/release/app-release.apk
```

Le build **release** est réduit et optimisé par R8 : il démarre beaucoup plus vite que le build debug sur la TV. Il est signé avec la clé de debug Android : il s'installe par adb sans keystore, et par-dessus un build debug du même poste (la configuration est conservée).

Installation sur la TV :

1. Activer les options développeur sur la TV, puis le débogage réseau.
2. Installer et lancer l'app :

```bash
adb connect <IP_TV>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

Ensuite, appliquer les réglages de la section [Optimiser la TV](#optimiser-la-tv-adb-sans-root) : au minimum les deux premiers, sans lesquels le pilotage en arrière-plan ne fonctionne pas.

Au premier lancement, l'écran de configuration demande :

- l'adresse de Jeedom (par exemple `192.168.1.10` ; un port est accepté : `jeedom.local:8080`) ;
- la clé de la TV, affichée sur la page de l'équipement Jeedom TV correspondant dans le plugin.

La configuration n'est enregistrée qu'après une connexion réussie.

En build debug uniquement, la configuration peut aussi être passée par adb, ce qui évite la saisie au clavier de la TV :

```bash
adb shell am start -n be.jeedomtv/.view.MainActivity \
  --es jeedom_host <IP_JEEDOM> --es jeedom_key '<CLE_DE_LA_TV>'
```

## Optimiser la TV (adb, sans root)

Réglages utiles sur une TCL Google TV (Android 11). Ils sont réversibles et survivent à un redémarrage. À lancer depuis une machine du réseau, après `adb connect <IP_TV>:5555`.

### Indispensables au pilotage en arrière-plan

```bash
# Superposition par-dessus la vidéo, et ouverture de l'écran depuis l'arrière-plan
adb shell appops set be.jeedomtv SYSTEM_ALERT_WINDOW allow

# TCL : autoriser le démarrage automatique (refusé par défaut, il bloque le service au démarrage)
adb shell appops set be.jeedomtv APP_AUTO_START allow
```

### Recommandés

```bash
# Exempter l'application de l'économiseur d'énergie et des restrictions d'arrière-plan
adb shell dumpsys deviceidle whitelist +be.jeedomtv
adb shell appops set be.jeedomtv RUN_IN_BACKGROUND allow
adb shell appops set be.jeedomtv RUN_ANY_IN_BACKGROUND allow
```

Vérification :

```bash
adb shell appops get be.jeedomtv
adb shell dumpsys deviceidle whitelist | grep jeedomtv
```

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

- **TCL a son propre gestionnaire de mémoire** (`com.tcl.guard`). Il ne peut pas être désactivé sans root, mais il épargne les applications qui ont un service au premier plan, comme celle-ci.
- **Veille et réseau** : au rallumage de l'écran et au retour du réseau, l'application relance aussitôt l'attente des changements, avec des pages rechargées, et signale son état à Jeedom.
- **Ordres périmés** : un ordre non livré au bout de 60 s est abandonné par le plugin. Une TV éteinte n'affiche donc pas une page périmée à son réveil.
- **Après une mise à jour par adb**, l'application redémarre seule (`MY_PACKAGE_REPLACED`) et reprend les ordres de Jeedom, mais le gestionnaire de démarrage de TCL (`TclAppBoot`) lui refuse le service au premier plan (`block startForeground … default_borbid`), malgré `APP_AUTO_START`. Sans service au premier plan, `com.tcl.guard` peut l'arrêter plus tard. Ouvrir l'application une fois après chaque mise à jour, ou par adb : `adb shell am start -n be.jeedomtv/.view.MainActivity`.
- **Une clé = un seul appareil.** Les ordres de Jeedom sont livrés une seule fois, au premier appareil qui les demande. Deux appareils configurés avec la même clé (une TV et un émulateur de test, par exemple) se volent les ordres et brouillent l'état « Visible ». Chaque appareil a donc son propre équipement dans Jeedom.
- **Juste après la touche Accueil**, Android bloque environ 5 s l'ouverture d'une application depuis l'arrière-plan. Un ordre « Afficher » reçu à ce moment s'affiche avec quelques secondes de retard : l'application redemande le premier plan au bout de 6 s.

## Piloter la TV depuis Jeedom

Le plugin crée sur l'équipement de la TV les commandes décrites dans [docs/api.md](docs/api.md#commandes-jeedom--tv) :

| Commande | Usage |
|---|---|
| `Afficher <nom de page>` | Affiche la page, avec la durée par défaut de l'équipement (30 s) |
| `Afficher page` | Titre = page (id ou nom), message = durée en s (`0` = sans retour) |
| `Message` | Bandeau sur la TV (titre facultatif, message) |
| `Quitter` | Retour au programme TV |
| `Visible`, `Écran allumé`, `Page affichée`, `En ligne` | État de la TV, utilisable dans les conditions |

Exemple de scénario « Sonnette » (déclencheur : la commande info du bouton de sonnette) :

```
SI #[TV salon][TV salon][Écran allumé]# == 1
ALORS
  #[TV salon][TV salon][Afficher page]#    titre : Volets    message : 20
  #[TV salon][TV salon][Message]#          titre : Sonnette  message : Quelqu'un sonne à la porte
```

Pendant un film, le panneau de la page « Volets » s'affiche par-dessus la vidéo, avec le message ; il se ferme seul 20 s plus tard si personne ne touche la télécommande. Si l'application était déjà affichée, elle passe sur la page « Volets » puis revient 20 s plus tard à ce qui était affiché avant. La condition évite de réveiller une TV en veille. Les noms entre crochets (objet, équipement) dépendent de votre installation.

## Superposition par-dessus la vidéo

Sur Google TV, ouvrir une application par-dessus une autre fait passer la vidéo en arrière-plan. YouTube, par exemple, l'arrête alors : au retour, il affiche le choix du profil et la vidéo est à recommencer. Quand l'application est cachée, les ordres de Jeedom s'affichent donc dans des fenêtres de superposition : l'application vidéo reste au premier plan (« resumed ») et continue sa lecture.

| Ordre | Application affichée | Application cachée (film, IPTV…) |
|---|---|---|
| Message | Bandeau dans l'application | Bandeau en haut de l'écran, ~8 s. Il ne prend pas le focus : la télécommande continue de piloter la vidéo. |
| Afficher page | Page dans l'application | Panneau semi-transparent sur la moitié basse de l'écran : la page en grille compacte. |
| Quitter | Retour à l'application d'avant | Fermeture de la superposition |

Touches du panneau : les mêmes que sur l'écran des pages (flèches, OK, 1 à 9, CH+ / CH-, mode réglage, confirmation), plus :

| Touche | Action |
|---|---|
| Retour | Fermer le panneau (ou annuler le réglage / la confirmation en cours) |
| Menu | Ouvrir l'application complète sur la même page |

Le panneau se ferme seul après la durée de l'ordre. Une touche de la télécommande annule cette fermeture ; il se ferme alors après une minute sans touche, comme un panneau sans durée. Pour Jeedom, le panneau compte comme un affichage : `Visible` vaut 1 et `Page affichée` donne sa page.

**Permission requise** : « afficher par-dessus les autres applications », accordée par adb (voir [Indispensables au pilotage en arrière-plan](#indispensables-au-pilotage-en-arrière-plan)). Sans elle, l'ordre « Afficher » ouvre l'application comme avant, et le bandeau n'est pas affiché quand l'application est cachée.

Limites :

- Le panneau prend le focus de la télécommande : tant qu'il est affiché, les touches ne vont plus à la vidéo. La plupart des lecteurs continuent leur lecture, mais une application qui se met en pause à la perte du focus le ferait.
- L'écran d'accueil de Google TV compte aussi comme une « application cachée » : le panneau s'y affiche par-dessus.
- Le panneau ne réagit qu'à la télécommande (pas au toucher ni à la souris).
