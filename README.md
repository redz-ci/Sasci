# SAS CI – Suivi des livraisons et des incidents

Application Android pour les chauffeurs de SAS CI, reliée à un fichier **Google Sheets**. Les photos sont rangées dans **Google Drive**.

| Composant | Contenu |
|---|---|
| `app/` | Application Android (Kotlin) à installer sur les téléphones des chauffeurs |
| `apps-script/` | Serveur Google Apps Script : Google Sheets, photos Drive, alertes, rapport quotidien |
| `.github/workflows/` | Compilation automatique de l'APK, sans rien installer sur un ordinateur |

---

## 1. Ce que fait l'application

**Volet livraisons**
1. **Départ du parc** : le chauffeur choisit sa tournée et confirme son départ. L'application enregistre la date, l'heure et la position GPS.
2. **Arrivée chez le client** : le chauffeur choisit le BL, prend une photo **obligatoire** du camion sur le site, puis confirme. L'application enregistre l'heure et la position GPS, et calcule la distance avec le GPS du client quand il est connu.
3. **Fin du déchargement** : le chauffeur prend une photo **obligatoire** de la marchandise livrée. L'application enregistre l'heure et le GPS et calcule la durée du déchargement.
4. **Confirmation** : le chauffeur prend une photo **obligatoire** du BL signé et choisit le statut (*complète*, *partielle* ou *refusée*). Pour une livraison partielle ou refusée, il doit indiquer les quantités et le motif. Le BL passe alors au statut **« Livré – à valider »**.

**Volet incidents**, accessible à tout moment, même hors livraison :
- deux catégories, *Qualité de service* et *Sécurité opérationnelle*, chacune avec les types prévus dans le compte rendu ;
- trois niveaux d'alerte : mineur, important ou grave ;
- chaque déclaration contient une description, des photos, la date et l'heure, la position GPS, le véhicule et, si besoin, le BL concerné ;
- les incidents **importants** et **graves** déclenchent un e-mail immédiat à la logistique. Après un incident grave, l'application propose au chauffeur d'appeler la logistique.

**Fonctionnement hors réseau** : chaque action est d'abord enregistrée sur le téléphone, puis envoyée automatiquement dès que le réseau revient. L'heure enregistrée est celle de l'action, pas celle de l'envoi. L'écran d'accueil affiche le nombre d'envois en attente.

**Côté logistique (Google Sheets)** :
- onglets *Suivi des tournées*, *Suivi des BL*, *Incidents et litiges* et *Tableau de bord* ;
- anomalies détectées automatiquement : GPS absent, arrivée hors zone client, déchargement trop long, livraison partielle ou refusée ;
- validation et clôture : la logistique change la colonne *Statut* (`Validé`, `Clôturé`) et remplit *Commentaire logistique* ;
- **rapport quotidien** envoyé chaque soir par e-mail : livraisons réalisées, livraisons non terminées, anomalies, incidents et dossiers restant à traiter.

---

## 2. Installer le serveur Google Sheets (une seule fois, environ 15 minutes)

1. Avec le compte Google de la logistique, créez un **nouveau Google Sheets**, par exemple « SAS CI – Suivi livraisons ».
2. Ouvrez le menu **Extensions > Apps Script**.
3. Remplacez le contenu de `Code.gs` par celui du fichier [`apps-script/Code.gs`](apps-script/Code.gs), puis enregistrez.
4. Dans la liste des fonctions, choisissez **`installer`** et cliquez sur **Exécuter**. Acceptez les autorisations demandées (Sheets, Drive, envoi d'e-mails).
   Les onglets, le tableau de bord, le dossier Drive des photos et l'envoi du rapport du soir sont alors créés.
5. Cliquez sur **Déployer > Nouveau déploiement**, type **Application Web** :
   - *Exécuter en tant que* : **Moi** ;
   - *Qui a accès* : **Tout le monde**.
   Copiez l'URL obtenue, qui se termine par `/exec`.
6. Revenez dans le Google Sheets et rechargez la page. Un menu **SAS CI** apparaît.
   - **SAS CI > Paramètres de connexion pour l'application** affiche l'URL et la **clé API**.
   - L'onglet **Paramètres** permet de régler les e-mails de la logistique, le téléphone, l'heure du rapport et les seuils d'anomalie.
7. Dans l'onglet **Chauffeurs**, ajoutez une ligne par chauffeur : Nom, Code PIN (facultatif), Camion attitré, Téléphone, Actif = `Oui`.

> Si vous modifiez le script plus tard : **Déployer > Gérer les déploiements > Modifier (crayon) > Version : Nouvelle version**. L'URL ne change pas.

### Préparer les tournées chaque jour (sans double saisie)

1. Copiez les lignes du tableau Excel des tournées dans l'onglet **Import Excel**, en respectant l'ordre des colonnes :
   `Date | N° Tournée (optionnel) | Chauffeur | Camion | Ordre | N° BL | Client | Adresse | GPS client (optionnel) | Marchandise | Quantité`
2. Lancez **SAS CI > Importer les tournées**.
   Les tournées et les BL sont créés et apparaissent sur le téléphone des chauffeurs dès qu'ils touchent « Actualiser ».
   - Si le N° de tournée est vide, il est créé automatiquement à partir de la date et du camion.
   - Le **GPS client** au format `5.3364,-4.0267` permet de vérifier que le camion est bien chez le client.

Pour un premier essai, **SAS CI > Créer des données de test** crée le chauffeur « Chauffeur Test » (PIN `1234`) et une tournée de trois BL.

---

## 3. Obtenir l'APK (application Android)

### Option A – Compilation automatique par GitHub (recommandée, rien à installer)

À chaque envoi de code sur GitHub, l'APK est compilé automatiquement.

1. *(Recommandé)* Pour que l'URL et la clé soient déjà intégrées à l'application, le chauffeur n'ayant alors rien à saisir, ouvrez sur GitHub **Settings > Secrets and variables > Actions** :
   - `SASCI_SCRIPT_URL` : l'URL qui se termine par **`/exec`** (jamais `/dev`) ;
   - `SASCI_API_KEY` : la clé API.
   Vous pouvez les créer dans l'onglet *Variables* ou dans l'onglet *Secrets* : les deux fonctionnent.
   L'adresse et la clé sont lues **au moment de la compilation**. Après les avoir modifiées, relancez la compilation et réinstallez l'APK.
2. Ouvrez l'onglet **Actions > Construire l'APK Android > Run workflow**, ou envoyez simplement un commit.
3. Quand la compilation est terminée (environ 5 minutes) :
   - sur la page de l'exécution, téléchargez l'artefact **SASCI-Livraisons-apk**, un fichier zip qui contient l'APK ;
   - pour la branche principale, l'APK est aussi publié dans **Releases > derniere-version**.

### Option B – Compiler sur un ordinateur

- **Avec Android Studio** : *File > Open*, choisissez ce dossier, puis *Build > Build App Bundle(s) / APK(s) > Build APK(s)*.
- **En ligne de commande**, avec le JDK 17 et le SDK Android installés :
  ```bash
  ./gradlew assembleRelease -PSASCI_SCRIPT_URL="https://script.google.com/macros/s/XXXX/exec" -PSASCI_API_KEY="xxxx"
  # APK : app/build/outputs/apk/release/app-release.apk
  ```

### Signature (important pour les mises à jour)

Android n'installe une mise à jour que si elle est signée avec **la même clé** que la version déjà installée. Sans clé configurée, chaque compilation GitHub utilise une clé de test différente, et il faut alors désinstaller l'ancienne version avant d'installer la nouvelle. Pour l'éviter, créez une clé **une seule fois** :

```bash
keytool -genkeypair -keystore sasci.jks -alias sasci -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 sasci.jks > sasci.jks.b64
```

Puis, dans les **Secrets** GitHub, ajoutez :
- `SASCI_KEYSTORE_BASE64` : le contenu de `sasci.jks.b64` ;
- `SASCI_KEYSTORE_PASSWORD` : le mot de passe choisi ;
- `SASCI_KEY_ALIAS` : `sasci`.

Conservez `sasci.jks` et son mot de passe en lieu sûr. Ne les mettez jamais dans le dépôt.

---

## 4. Installer l'application sur le téléphone d'un chauffeur

Android 7 ou plus récent est nécessaire.

1. Envoyez le fichier **SASCI-Livraisons.apk** au téléphone, par WhatsApp, Google Drive, e-mail ou câble USB.
2. Ouvrez le fichier. Si Android affiche *« Installation bloquée / sources inconnues »*, touchez **Paramètres**, activez **Autoriser cette source**, puis revenez en arrière.
3. Touchez **Installer**. Si Google Play Protect affiche un avertissement, choisissez **Installer quand même** : l'application est interne et n'est pas publiée sur le Play Store.
4. Ouvrez **SAS CI Livraisons**, autorisez la **localisation**, choisissez votre nom, saisissez votre code PIN, puis touchez **Se connecter**.
   Si l'URL n'a pas été intégrée à l'APK, saisissez l'URL et la clé dans *Paramètres du serveur*.

**Conseils pour les chauffeurs**
- Gardez la localisation (GPS) activée pendant la tournée.
- En zone sans réseau, continuez normalement : les envois partiront automatiquement plus tard. Ouvrez l'application quand le réseau revient pour qu'elle termine les envois.
- Touchez une photo pour la supprimer et la reprendre.

---

## 5. Organisation du Google Sheets

| Onglet | Rôle |
|---|---|
| Suivi des tournées | Une ligne par tournée : départ, retour, nombre de BL et de BL livrés, statut |
| Suivi des BL | Une ligne par BL : heures, GPS, photos, durée, résultat, anomalies, validation |
| Incidents et litiges | Une ligne par incident : statut à suivre (`Ouvert` → `En cours` → `Clôturé`) |
| Tableau de bord | Indicateurs : livraisons, durées moyennes par client, historique par chauffeur et camion, incidents |
| Import Excel | Zone où coller les tableaux de tournées |
| Chauffeurs | Liste des chauffeurs autorisés, avec leur PIN et leur camion |
| Paramètres | Clé API, e-mails, téléphone, heure du rapport, seuils d'anomalie |
| Journal | Trace technique des envois, qui sert à éviter les doublons |

**Statuts d'un BL** : `À livrer` → `Arrivé` → `Déchargé` → `Livré – à valider` → `Validé` → `Clôturé`.
Les quatre premiers sont mis à jour par l'application, les deux derniers par la logistique.

---

## 6. Dépannage : « l'application ne se connecte pas »

Dans l'application, ouvrez **⚙ Paramètres du serveur** puis touchez **Enregistrer et tester la connexion**. Le test vérifie chaque point dans l'ordre et indique celui qui bloque :

| Message | Cause | Solution |
|---|---|---|
| *L'adresse se termine par /dev* | L'adresse copiée est l'adresse de **test** | Apps Script > **Déployer > Gérer les déploiements** et copier l'URL **/exec** |
| *Google demande une connexion* | Le déploiement n'est pas public | Gérer les déploiements > Modifier (crayon) > *Exécuter en tant que : **Moi*** et *Qui a accès : **Tout le monde***, puis Déployer |
| *fonction doGet introuvable* | Le déploiement date d'avant le collage du code | Enregistrer Code.gs, puis Gérer les déploiements > Modifier > Version : **Nouvelle version** > Déployer |
| *Clé API invalide* | La clé saisie ou intégrée à l'APK ne correspond pas | Recopier la valeur de la ligne « Clé API » de l'onglet **Paramètres** |
| *Aucun chauffeur actif* | Onglet Chauffeurs vide, ou Actif = Non | Ajouter les chauffeurs, avec Actif = `Oui` |
| *Onglet manquant* | La fonction `installer` n'a pas été lancée | Menu **SAS CI > Installer / réparer** |

**Test depuis un navigateur.** Ouvrez l'URL `/exec` dans une fenêtre de **navigation privée**. Vous devez voir :
`{"ok":true,"service":"SAS CI – Suivi des livraisons"}`. Si Google affiche une page de connexion, le déploiement n'est pas en accès « Tout le monde ».
Ajoutez ensuite `?action=ping&key=VOTRE_CLE` à la fin de l'URL : la réponse doit être `{"ok":true}`.

**Après une mise à jour de `Code.gs`**, il faut toujours créer une **nouvelle version** du déploiement : Gérer les déploiements > Modifier > Nouvelle version. L'URL reste la même.

**Comptes Google Workspace (entreprise).** Si l'option « Tout le monde » n'apparaît pas, l'administrateur du domaine a bloqué le partage externe des scripts. Dans ce cas, utilisez un compte Gmail pour héberger le Google Sheets, ou demandez à l'administrateur d'autoriser cette option.

---

## 7. Limites connues de cette première version

- Les envois en attente partent quand l'application est ouverte, puis toutes les minutes tant qu'elle reste ouverte. Ils ne partent pas quand l'application est fermée.
- Le Web App Google est accessible avec la clé API et le PIN du chauffeur. Gardez la clé interne et donnez un PIN à chaque chauffeur.
- Quotas Google (compte gratuit) : environ 100 e-mails par jour et 90 minutes de script par jour, ce qui suffit largement pour quelques dizaines de livraisons par jour.
