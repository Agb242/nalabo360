# Installer Nalabo360 sur iPhone — gratuitement

Ce guide ne nécessite **aucun compte Apple Developer payant (99 $/an)**. Le
projet compile une **.ipa non signée** sur GitHub Actions ; vous la signez
vous-même avec votre identifiant Apple personnel (gratuit) via **Sideloadly**
ou **AltStore**.

## Les limites du compte gratuit (à connaître avant de commencer)

| Limite | Détail |
|---|---|
| **7 jours** | La signature expire au bout de 7 jours : l'application refuse de s'ouvrir ensuite. Il faut re-signer (AltStore le fait presque tout seul, Sideloadly demande de recommencer l'installation). |
| **3 applications** maximum | Un certificat gratuit ne peut signer que 3 applications sideloadées à la fois par appareil. |
| **10 App IDs / 7 jours** | Chaque application sideloadée consomme un identifiant d'app ; la limite se réinitialise au fil des jours. |

Pour un usage « je teste mon application », c'est largement suffisant.

---

## Étape 0 — Récupérer la .ipa non signée

1. Ouvrez le dépôt GitHub → onglet **Actions**.
2. Cliquez sur le workflow **iOS** (le dernier run vert).
3. En bas de la page du run, section **Artifacts**, téléchargez
   `nalabo360-unsigned-ipa`.
4. Dézippez le fichier : vous obtenez **`Nalabo360-unsigned.ipa`**.

> L'équivalent Android existe : le workflow **Android** publie
> `composeApp-debug.apk`, installable directement (`adb install` ou copie
> sur le téléphone).

---

## Option A — Sideloadly (le plus simple, Windows ou macOS)

### Prérequis (Windows uniquement)
- [Sideloadly](https://sideloadly.io)
- Les pilotes USB d'Apple : installez
  [iTunes](https://www.apple.com/itunes/) (version Microsoft Store conseillée)
  et [iCloud](https://www.apple.com/icloud/setup/pc) si ce n'est pas déjà fait,
  puis redémarrez.

### Installation
1. Branchez l'iPhone en USB. Sur le téléphone, tapez **Faire confiance**.
2. Lancez Sideloadly.
3. Glissez-déposez `Nalabo360-unsigned.ipa` dans la zone *IPA*.
4. Renseignez votre **identifiant Apple** (champ *Apple account*). Un compte
   avec double authentification fonctionne ; Sideloadly vous demandera le code.
5. Cliquez **Start** et patientez.
6. Sur l'iPhone : **Réglages → Général → VPN et gestion des appareils** →
   touchez votre profil Apple ID → **Faire confiance**.
7. Lancez **Nalabo360** depuis l'écran d'accueil.

Renouvelez tous les 7 jours : refaites simplement l'installation (les photos
sauvegardées dans votre bibliothèque ne sont pas affectées).

---

## Option B — AltStore (re-signature automatique)

Plus long à mettre en place, mais AltServer **rafraîchit la signature tout
seul** tant que le PC et l'iPhone sont sur le même réseau Wi-Fi.

1. Installez [AltServer](https://altstore.io) sur le PC (Windows : pensez au
   plugin iTunes/iCloud proposé à l'installation ; macOS : il vit dans la
   barre de menu).
2. iPhone branché en USB (faites confiance) → cliquez sur l'icône AltServer →
   **Install AltStore → [votre iPhone]**, puis entrez votre identifiant Apple.
3. AltStore apparaît sur l'iPhone (**Réglages → Général → VPN et gestion des
   appareils** → Faire confiance).
4. Dans AltStore sur l'iPhone, onglet **My Apps**, bouton **+** en haut à
   gauche → choisissez `Nalabo360-unsigned.ipa`.
5. À chaque expiration (avant 7 jours), ouvrez l'iPhone et le PC sur le même
   Wi-Fi, lancez brièvement AltStore : il renouvelle la signature tout seul.

---

## Dépannage

| Symptôme | Solution |
|---|---|
| *Application enterprise / développeur non approuvé* | Réglages → Général → VPN et gestion des appareils → faire confiance au profil. |
| *Maximum de 3 applications atteint* | Supprimez une application sideloadée expirée dont vous ne servez plus, puis réessayez. |
| *Impossible d'installer (provisioning)* | Le certificat a peut-être atteint sa limite de 10 App IDs / 7 jours — attendez un jour ou supprimez des apps sideloadées. |
| L'app s'ouvre puis se referme aussitôt | La signature de 7 jours a expiré : re-sideloadez (option A) ou rafraîchissez (option B). |
| Sideloadly ne voit pas l'iPhone (Windows) | Réinstallez iTunes + iCloud (pilotes USB Apple), changez de câble/port USB, débranchez-rebranchez. |
| Code 2FA refusé | Utilisez un [mot de passe d'app](https://appleid.apple.com/account/manage) généré pour votre identifiant Apple dans Sideloadly. |

## Ce que contient l'.ipa

Assemblée par [`iosApp/build-ipa.sh`](../iosApp/build-ipa.sh) sans projet Xcode :

```
Payload/
└── Nalabo360.app
    ├── Info.plist              (identifiants, autorisations caméra/photos)
    ├── Nalabo360              (binaire Swift minimal : UIWindow + Compose UI)
    └── Frameworks/
        └── Nalabo360Kit.framework   (tout l'app partagée Kotlin)
```

L'ad-hoc signature posée par le script sert uniquement à rendre le bundle
valide ; **Sideloadly/AltStore remplacent toutes les signatures** par celle de
votre certificat personnel au moment de l'installation.
