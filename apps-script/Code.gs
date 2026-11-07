/**
 * SAS CI – Suivi des livraisons et des incidents
 * Serveur Google Apps Script lié au fichier Google Sheets.
 *
 * Installation : voir apps-script/INSTALLATION.md
 *  1. Coller ce fichier dans Extensions > Apps Script du Google Sheets.
 *  2. Exécuter la fonction "installer" (menu SAS CI > Installer / réparer).
 *  3. Déployer > Nouveau déploiement > Application Web (Exécuter en tant que : moi,
 *     Accès : Tout le monde). Copier l'URL /exec dans l'application.
 */

// ---------------------------------------------------------------------------
// Structure du classeur
// ---------------------------------------------------------------------------

const ONGLET = {
  TOURNEES: 'Suivi des tournées',
  BL: 'Suivi des BL',
  INCIDENTS: 'Incidents et litiges',
  DASH: 'Tableau de bord',
  IMPORT: 'Import Excel',
  CHAUFFEURS: 'Chauffeurs',
  PARAMS: 'Paramètres',
  JOURNAL: 'Journal',
};

const COLONNES = {
  TOURNEES: ['ID Tournée', 'Date', 'Chauffeur', 'Camion', 'Statut', 'Nb BL', 'Nb livrés',
    'Départ (heure)', 'Départ GPS', 'Retour parc (heure)', 'Retour GPS', 'Observations'],
  BL: ['N° BL', 'ID Tournée', 'Date', 'Chauffeur', 'Camion', 'Ordre', 'Client', 'Adresse',
    'GPS client', 'Marchandise', 'Quantité prévue', 'Statut',
    'Arrivée (heure)', 'Arrivée GPS', 'Distance client (m)', 'Photo camion',
    'Fin déchargement (heure)', 'Fin déchargement GPS', 'Durée déchargement (min)', 'Photo marchandise',
    'Photo BL signé', 'Résultat livraison', 'Quantité concernée', 'Motif', 'Confirmation (heure)',
    'Anomalies', 'Commentaire logistique', 'Validé par', 'Date validation'],
  INCIDENTS: ['ID Incident', 'Date / heure', 'Chauffeur', 'Véhicule', 'Catégorie', 'Type', 'Niveau',
    'N° BL', 'ID Tournée', 'Description', 'GPS', 'Photos', 'Statut', 'Traitement / actions', 'Date clôture'],
  IMPORT: ['Date', 'N° Tournée (optionnel)', 'Chauffeur', 'Camion', 'Ordre', 'N° BL', 'Client', 'Adresse',
    'GPS client (optionnel : lat,lng)', 'Marchandise', 'Quantité'],
  CHAUFFEURS: ['Nom', 'Code PIN', 'Camion attitré', 'Téléphone', 'Actif'],
  PARAMS: ['Paramètre', 'Valeur', 'Explication'],
  JOURNAL: ['Event ID', 'Reçu le', 'Action', 'Chauffeur', 'Référence'],
};

const STATUTS_BL = ['À livrer', 'Arrivé', 'Déchargé', 'Livré – à valider', 'Validé', 'Clôturé'];
const STATUTS_TOURNEE = ['Planifiée', 'En cours', 'Terminée', 'Clôturée'];
const STATUTS_INCIDENT = ['Ouvert', 'En cours', 'Clôturé'];
const NIVEAUX = ['Mineur', 'Important', 'Grave'];
const RESULTATS = ['Complète', 'Partielle', 'Refusée'];

const P = {
  CLE: 'Clé API',
  EMAILS: 'Emails logistique',
  TEL: 'Téléphone logistique',
  HEURE: 'Heure du rapport quotidien',
  DIST: 'Distance max client (m)',
  DUREE: 'Durée max déchargement (min)',
  DOSSIER: 'Dossier photos (ID Drive)',
};

// ---------------------------------------------------------------------------
// Menu
// ---------------------------------------------------------------------------

function onOpen() {
  SpreadsheetApp.getUi().createMenu('SAS CI')
    .addItem('Importer les tournées (onglet « Import Excel »)', 'importerTournees')
    .addItem('Envoyer le rapport du jour maintenant', 'rapportQuotidien')
    .addItem('Mettre à jour les compteurs des tournées', 'recalculerTournees')
    .addSeparator()
    .addItem('Paramètres de connexion pour l\'application', 'afficherConnexion')
    .addItem('Créer des données de test', 'creerDonneesTest')
    .addItem('Installer / réparer', 'installer')
    .addToUi();
}

// ---------------------------------------------------------------------------
// Installation
// ---------------------------------------------------------------------------

function installer() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  PropertiesService.getScriptProperties().setProperty('SS_ID', ss.getId());

  const ordre = ['TOURNEES', 'BL', 'INCIDENTS', 'DASH', 'IMPORT', 'CHAUFFEURS', 'PARAMS', 'JOURNAL'];
  ordre.forEach(function (k, i) {
    let sh = ss.getSheetByName(ONGLET[k]);
    if (!sh) sh = ss.insertSheet(ONGLET[k], Math.min(i, ss.getSheets().length));
    if (COLONNES[k]) preparerEntetes_(sh, COLONNES[k]);
  });

  // Supprime la feuille vide par défaut si elle existe
  ['Feuille 1', 'Feuille1', 'Sheet1'].forEach(function (n) {
    const sh = ss.getSheetByName(n);
    if (sh && sh.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(sh);
  });

  // Paramètres par défaut
  const params = ss.getSheetByName(ONGLET.PARAMS);
  const defauts = [
    [P.CLE, 'sasci-' + Utilities.getUuid().replace(/-/g, '').slice(0, 12), 'Clé à saisir dans l\'application (ou à intégrer à l\'APK).'],
    [P.EMAILS, Session.getEffectiveUser().getEmail(), 'Destinataires des alertes et du rapport quotidien (séparés par des virgules).'],
    [P.TEL, '', 'Numéro proposé au chauffeur pour appeler la logistique après un incident grave.'],
    [P.HEURE, 19, 'Heure d\'envoi du rapport quotidien (0 à 23). Relancer « Installer / réparer » après modification.'],
    [P.DIST, 500, 'Au-delà de cette distance entre le camion et le GPS client, une anomalie est signalée.'],
    [P.DUREE, 90, 'Au-delà de cette durée de déchargement, une anomalie est signalée.'],
    [P.DOSSIER, '', 'Dossier Google Drive des photos (créé automatiquement).'],
  ];
  const existants = params.getRange(1, 1, Math.max(params.getLastRow(), 1), 1).getValues().map(function (r) { return r[0]; });
  defauts.forEach(function (d) {
    if (existants.indexOf(d[0]) < 0) params.appendRow(d);
  });
  params.setColumnWidth(1, 230); params.setColumnWidth(2, 320); params.setColumnWidth(3, 520);

  // Formats et listes déroulantes
  const ch = ss.getSheetByName(ONGLET.CHAUFFEURS);
  ch.getRange('B:B').setNumberFormat('@');
  validation_(ch, 'Actif', ['Oui', 'Non']);

  const bl = ss.getSheetByName(ONGLET.BL);
  validation_(bl, 'Statut', STATUTS_BL);
  validation_(bl, 'Résultat livraison', RESULTATS);
  formatDate_(bl, 'Date', 'dd/MM/yyyy');
  ['Arrivée (heure)', 'Fin déchargement (heure)', 'Confirmation (heure)', 'Date validation']
    .forEach(function (c) { formatDate_(bl, c, 'dd/MM/yyyy HH:mm'); });

  const t = ss.getSheetByName(ONGLET.TOURNEES);
  validation_(t, 'Statut', STATUTS_TOURNEE);
  formatDate_(t, 'Date', 'dd/MM/yyyy');
  formatDate_(t, 'Départ (heure)', 'dd/MM/yyyy HH:mm');
  formatDate_(t, 'Retour parc (heure)', 'dd/MM/yyyy HH:mm');

  const inc = ss.getSheetByName(ONGLET.INCIDENTS);
  validation_(inc, 'Statut', STATUTS_INCIDENT);
  validation_(inc, 'Niveau', NIVEAUX);
  formatDate_(inc, 'Date / heure', 'dd/MM/yyyy HH:mm');
  formatDate_(inc, 'Date clôture', 'dd/MM/yyyy');

  const imp = ss.getSheetByName(ONGLET.IMPORT);
  imp.getRange('F:F').setNumberFormat('@');

  couleursConditionnelles_(bl, inc);
  construireTableauDeBord_();

  // Déclencheur du rapport quotidien
  ScriptApp.getProjectTriggers().forEach(function (tr) {
    if (tr.getHandlerFunction() === 'rapportQuotidien') ScriptApp.deleteTrigger(tr);
  });
  const heure = Math.min(23, Math.max(0, parseInt(param_(P.HEURE), 10) || 19));
  ScriptApp.newTrigger('rapportQuotidien').timeBased().everyDays(1).atHour(heure).create();

  dossierPhotos_();

  try {
    SpreadsheetApp.getUi().alert('Installation terminée',
      'Étapes suivantes :\n\n' +
      '1. Onglet « Chauffeurs » : ajoutez les chauffeurs (Nom, PIN, camion, Actif = Oui).\n' +
      '2. Déployer > Nouveau déploiement > Application Web\n' +
      '   – Exécuter en tant que : Moi\n   – Qui a accès : Tout le monde\n' +
      '3. Menu SAS CI > « Paramètres de connexion pour l\'application » pour obtenir l\'URL et la clé.\n' +
      '4. Collez les tournées Excel dans l\'onglet « Import Excel » puis menu SAS CI > Importer.',
      SpreadsheetApp.getUi().ButtonSet.OK);
  } catch (e) { /* exécution hors interface */ }
}

function preparerEntetes_(sh, entetes) {
  const derniere = Math.max(sh.getLastColumn(), 1);
  const actuels = sh.getRange(1, 1, 1, derniere).getValues()[0].map(String);
  if (sh.getLastRow() === 0 || actuels.join('') === '') {
    sh.getRange(1, 1, 1, entetes.length).setValues([entetes]);
  } else {
    // Ajoute les colonnes manquantes à la fin (réparation sans perte de données)
    entetes.forEach(function (h) {
      if (actuels.indexOf(h) < 0) {
        sh.getRange(1, sh.getLastColumn() + 1).setValue(h);
      }
    });
  }
  const n = sh.getLastColumn();
  sh.getRange(1, 1, 1, n).setFontWeight('bold').setBackground('#0D47A1').setFontColor('#FFFFFF').setWrap(true);
  sh.setFrozenRows(1);
}

function validation_(sh, colonne, valeurs) {
  const c = indexColonne_(sh, colonne);
  if (c < 0) return;
  const rule = SpreadsheetApp.newDataValidation().requireValueInList(valeurs, true).setAllowInvalid(true).build();
  sh.getRange(2, c + 1, sh.getMaxRows() - 1, 1).setDataValidation(rule);
}

function formatDate_(sh, colonne, format) {
  const c = indexColonne_(sh, colonne);
  if (c >= 0) sh.getRange(2, c + 1, sh.getMaxRows() - 1, 1).setNumberFormat(format);
}

function couleursConditionnelles_(bl, inc) {
  const regles = [];
  const cs = indexColonne_(bl, 'Statut');
  if (cs >= 0) {
    const r = bl.getRange(2, cs + 1, bl.getMaxRows() - 1, 1);
    const couleurs = { 'Arrivé': '#BBDEFB', 'Déchargé': '#FFE0B2', 'Livré – à valider': '#FFF59D', 'Validé': '#C8E6C9', 'Clôturé': '#A5D6A7' };
    Object.keys(couleurs).forEach(function (k) {
      regles.push(SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo(k).setBackground(couleurs[k]).setRanges([r]).build());
    });
  }
  const ca = indexColonne_(bl, 'Anomalies');
  if (ca >= 0) {
    const r = bl.getRange(2, ca + 1, bl.getMaxRows() - 1, 1);
    regles.push(SpreadsheetApp.newConditionalFormatRule().whenCellNotEmpty().setBackground('#FFCDD2').setRanges([r]).build());
  }
  bl.setConditionalFormatRules(regles);

  const regInc = [];
  const cn = indexColonne_(inc, 'Niveau');
  if (cn >= 0) {
    const r = inc.getRange(2, cn + 1, inc.getMaxRows() - 1, 1);
    regInc.push(SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo('Grave').setBackground('#EF9A9A').setRanges([r]).build());
    regInc.push(SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo('Important').setBackground('#FFCC80').setRanges([r]).build());
  }
  inc.setConditionalFormatRules(regInc);
}

// ---------------------------------------------------------------------------
// Tableau de bord (formules recalculées automatiquement par Google Sheets)
// ---------------------------------------------------------------------------

function construireTableauDeBord_() {
  const ss = classeur_();
  const sh = ss.getSheetByName(ONGLET.DASH);
  sh.clear();
  const bl = ss.getSheetByName(ONGLET.BL);
  const tr = ss.getSheetByName(ONGLET.TOURNEES);
  const inc = ss.getSheetByName(ONGLET.INCIDENTS);

  function col(sheet, nom) {
    const c = indexColonne_(sheet, nom);
    const l = lettre_(c + 1);
    return "'" + sheet.getName() + "'!" + l + '2:' + l;
  }
  const stBL = col(bl, 'Statut'), dateBL = col(bl, 'Date'), res = col(bl, 'Résultat livraison');
  const duree = col(bl, 'Durée déchargement (min)'), anom = col(bl, 'Anomalies'), numBL = col(bl, 'N° BL');
  const idT = col(tr, 'ID Tournée'), dateT = col(tr, 'Date');
  const idI = col(inc, 'ID Incident'), stI = col(inc, 'Statut'), nivI = col(inc, 'Niveau'), dateI = col(inc, 'Date / heure');
  const livre = '(COUNTIF(' + stBL + ',"Livré – à valider")+COUNTIF(' + stBL + ',"Validé")+COUNTIF(' + stBL + ',"Clôturé"))';

  const lignes = [
    ['TABLEAU DE BORD – SAS CI', '', ''],
    ['Indicateur', 'Total', 'Aujourd\'hui'],
    ['Tournées', '=COUNTIF(' + idT + ',"<>")', '=COUNTIF(' + dateT + ',TODAY())'],
    ['BL affectés', '=COUNTIF(' + numBL + ',"<>")', '=COUNTIF(' + dateBL + ',TODAY())'],
    ['Livraisons effectuées', '=' + livre,
      '=COUNTIFS(' + dateBL + ',TODAY(),' + stBL + ',"Livré – à valider")+COUNTIFS(' + dateBL + ',TODAY(),' + stBL + ',"Validé")+COUNTIFS(' + dateBL + ',TODAY(),' + stBL + ',"Clôturé")'],
    ['Livraisons complètes', '=COUNTIF(' + res + ',"Complète")', '=COUNTIFS(' + dateBL + ',TODAY(),' + res + ',"Complète")'],
    ['Livraisons partielles', '=COUNTIF(' + res + ',"Partielle")', '=COUNTIFS(' + dateBL + ',TODAY(),' + res + ',"Partielle")'],
    ['Livraisons refusées', '=COUNTIF(' + res + ',"Refusée")', '=COUNTIFS(' + dateBL + ',TODAY(),' + res + ',"Refusée")'],
    ['Livraisons non terminées', '=COUNTIF(' + numBL + ',"<>")-' + livre, ''],
    ['BL en attente de validation', '=COUNTIF(' + stBL + ',"Livré – à valider")', ''],
    ['Durée moyenne de déchargement (min)', '=IFERROR(ROUND(AVERAGE(' + duree + '),0),"")', '=IFERROR(ROUND(AVERAGEIFS(' + duree + ',' + dateBL + ',TODAY()),0),"")'],
    ['Anomalies nécessitant une intervention', '=COUNTIFS(' + anom + ',"<>",' + stBL + ',"<>Clôturé")', '=COUNTIFS(' + anom + ',"<>",' + dateBL + ',TODAY())'],
    ['Incidents ouverts', '=COUNTIFS(' + idI + ',"<>",' + stI + ',"<>Clôturé")', ''],
    ['dont graves', '=COUNTIFS(' + idI + ',"<>",' + stI + ',"<>Clôturé",' + nivI + ',"Grave")', ''],
    ['Incidents clôturés', '=COUNTIF(' + stI + ',"Clôturé")', ''],
    ['Incidents déclarés aujourd\'hui', '', '=COUNTIFS(' + dateI + ',">="&TODAY(),' + dateI + ',"<"&TODAY()+1)'],
  ];
  sh.getRange(1, 1, lignes.length, 3).setValues(lignes);
  sh.getRange('A1').setFontSize(16).setFontWeight('bold').setFontColor('#0D47A1');
  sh.getRange('A2:C2').setFontWeight('bold').setBackground('#0D47A1').setFontColor('#FFFFFF');
  sh.getRange(3, 2, lignes.length - 2, 2).setHorizontalAlignment('center').setFontWeight('bold');
  sh.setColumnWidth(1, 320); sh.setColumnWidth(2, 110); sh.setColumnWidth(3, 110);

  // Tableaux détaillés (QUERY)
  const plage = "'" + bl.getName() + "'!A2:" + lettre_(bl.getLastColumn());
  const L = function (n) { return 'Col' + (indexColonne_(bl, n) + 1); };
  sh.getRange('E1').setValue('Durée moyenne de déchargement par client').setFontWeight('bold').setFontColor('#0D47A1');
  sh.getRange('E2').setFormula('=IFERROR(QUERY({' + plage + '},"select ' + L('Client') + ', avg(' + L('Durée déchargement (min)') + '), count(' + L('Durée déchargement (min)') + ') where ' + L('Durée déchargement (min)') + ' is not null group by ' + L('Client') + ' order by avg(' + L('Durée déchargement (min)') + ') desc label ' + L('Client') + ' \'Client\', avg(' + L('Durée déchargement (min)') + ') \'Durée moy. (min)\', count(' + L('Durée déchargement (min)') + ') \'Livraisons\'",0),"Pas encore de données")');

  sh.getRange('I1').setValue('Historique par chauffeur et camion').setFontWeight('bold').setFontColor('#0D47A1');
  sh.getRange('I2').setFormula('=IFERROR(QUERY({' + plage + '},"select ' + L('Chauffeur') + ', ' + L('Camion') + ', count(' + L('N° BL') + ') where ' + L('N° BL') + ' is not null and ' + L('Confirmation (heure)') + ' is not null group by ' + L('Chauffeur') + ', ' + L('Camion') + ' label ' + L('Chauffeur') + ' \'Chauffeur\', ' + L('Camion') + ' \'Camion\', count(' + L('N° BL') + ') \'BL livrés\'",0),"Pas encore de données")');

  sh.getRange('M1').setValue('Résultats par chauffeur').setFontWeight('bold').setFontColor('#0D47A1');
  sh.getRange('M2').setFormula('=IFERROR(QUERY({' + plage + '},"select ' + L('Chauffeur') + ', count(' + L('N° BL') + ') where ' + L('Résultat livraison') + ' is not null group by ' + L('Chauffeur') + ' pivot ' + L('Résultat livraison') + '",0),"Pas encore de données")');
  [5, 9, 13].forEach(function (c) { sh.setColumnWidth(c, 180); });
}

// ---------------------------------------------------------------------------
// Web App : lecture (GET) et enregistrement (POST)
// ---------------------------------------------------------------------------

function doGet(e) {
  try {
    const p = (e && e.parameter) || {};
    if (!p.action) return json_({ ok: true, service: 'SAS CI – Suivi des livraisons' });
    verifierCle_(p.key);
    if (p.action === 'ping') return json_({ ok: true });
    if (p.action === 'chauffeurs') {
      return json_({ ok: true, chauffeurs: chauffeurs_().map(function (c) { return { nom: c.nom }; }) });
    }
    if (p.action === 'bootstrap') {
      const c = authentifier_(p.chauffeur, p.pin);
      return json_(Object.assign({ ok: true }, donneesChauffeur_(c)));
    }
    throw new Error('Action inconnue : ' + p.action);
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const b = JSON.parse(e.postData.contents);
    verifierCle_(b.key);
    const c = authentifier_(b.chauffeur, b.pin);
    lock.waitLock(30000);
    if (b.eventId && dejaRecu_(b.eventId)) return json_({ ok: true, doublon: true });

    let ref;
    switch (b.action) {
      case 'depart': ref = actionDepart_(b, c); break;
      case 'fin_tournee': ref = actionFinTournee_(b, c); break;
      case 'arrivee': ref = actionArrivee_(b, c); break;
      case 'fin_dechargement': ref = actionFinDechargement_(b, c); break;
      case 'confirmation': ref = actionConfirmation_(b, c); break;
      case 'incident': ref = actionIncident_(b, c); break;
      default: throw new Error('Action inconnue : ' + b.action);
    }
    journal_(b.eventId, b.action, c.nom, ref);
    SpreadsheetApp.flush();
    return json_({ ok: true, ref: ref });
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  } finally {
    try { lock.releaseLock(); } catch (e2) { /* rien */ }
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function verifierCle_(cle) {
  const attendue = cleApi_();
  if (attendue && String(cle || '').trim() !== attendue) {
    throw new Error('Clé API invalide');
  }
}

/** Clé API telle qu'affichée dans l'onglet Paramètres (texte exact, sans espaces). */
function cleApi_() {
  const sh = classeur_().getSheetByName(ONGLET.PARAMS);
  if (!sh || sh.getLastRow() < 1) return '';
  const v = sh.getRange(1, 1, sh.getLastRow(), 2).getDisplayValues();
  for (let i = 0; i < v.length; i++) if (String(v[i][0]).trim() === P.CLE) return String(v[i][1]).trim();
  return '';
}

function chauffeurs_() {
  const t = table_(ONGLET.CHAUFFEURS);
  return t.lignes.map(function (r) {
    return {
      nom: String(r[t.idx['Nom']] || '').trim(),
      pin: String(r[t.idx['Code PIN']] || '').trim(),
      camion: String(r[t.idx['Camion attitré']] || '').trim(),
      actif: String(r[t.idx['Actif']] || 'Oui').trim().toLowerCase() !== 'non',
    };
  }).filter(function (c) { return c.nom && c.actif; });
}

function authentifier_(nom, pin) {
  const n = String(nom || '').trim().toLowerCase();
  const c = chauffeurs_().filter(function (x) { return x.nom.toLowerCase() === n; })[0];
  if (!c) throw new Error('Chauffeur inconnu ou inactif : ' + nom);
  if (c.pin && !memePin_(c.pin, pin)) throw new Error('Code PIN incorrect');
  return c;
}

function memePin_(attendu, saisi) {
  const a = String(attendu || '').trim(), b = String(saisi || '').trim();
  if (a === b) return true;
  return /^\d+$/.test(a) && /^\d+$/.test(b) && Number(a) === Number(b);
}

/** Tournées non clôturées du chauffeur (des 3 derniers jours et à venir) avec leurs BL. */
function donneesChauffeur_(c) {
  const tz = classeur_().getSpreadsheetTimeZone();
  const limite = new Date(); limite.setDate(limite.getDate() - 3);
  const limiteIso = Utilities.formatDate(limite, tz, 'yyyy-MM-dd');
  const nom = c.nom.toLowerCase();

  const t = table_(ONGLET.TOURNEES);
  const tournees = [];
  t.lignes.forEach(function (r) {
    const id = String(r[t.idx['ID Tournée']] || '').trim();
    if (!id) return;
    if (String(r[t.idx['Chauffeur']] || '').trim().toLowerCase() !== nom) return;
    const statut = String(r[t.idx['Statut']] || '');
    const date = dateIso_(r[t.idx['Date']], tz);
    if (statut === 'Clôturée' || (date && date < limiteIso)) return;
    tournees.push({
      id: id, date: date, camion: String(r[t.idx['Camion']] || ''), statut: statut,
      depart: r[t.idx['Départ (heure)']] ? 'oui' : '',
      retour: r[t.idx['Retour parc (heure)']] ? 'oui' : '',
      bls: [],
    });
  });

  const parId = {};
  tournees.forEach(function (x) { parId[x.id] = x; });
  const b = table_(ONGLET.BL);
  b.lignes.forEach(function (r) {
    const tid = String(r[b.idx['ID Tournée']] || '').trim();
    const tour = parId[tid];
    const num = String(r[b.idx['N° BL']] || '').trim();
    if (!tour || !num) return;
    tour.bls.push({
      bl: num,
      ordre: Number(r[b.idx['Ordre']]) || 0,
      client: String(r[b.idx['Client']] || ''),
      adresse: String(r[b.idx['Adresse']] || ''),
      gpsClient: String(r[b.idx['GPS client']] || ''),
      marchandise: String(r[b.idx['Marchandise']] || ''),
      quantite: String(r[b.idx['Quantité prévue']] || ''),
      statut: String(r[b.idx['Statut']] || 'À livrer'),
    });
  });
  tournees.sort(function (a, b2) { return a.date < b2.date ? -1 : a.date > b2.date ? 1 : 0; });

  return {
    chauffeur: { nom: c.nom, camion: c.camion },
    params: { telLogistique: String(param_(P.TEL) || '') },
    tournees: tournees,
  };
}

// ---------------------------------------------------------------------------
// Actions envoyées par l'application
// ---------------------------------------------------------------------------

function actionDepart_(b, c) {
  const t = table_(ONGLET.TOURNEES);
  const i = trouver_(t, 'ID Tournée', b.tourneeId);
  if (i < 0) throw new Error('Tournée inconnue : ' + b.tourneeId);
  const maj = { 'Départ (heure)': quand_(b), 'Départ GPS': lienGps_(b.gps) };
  const statut = String(t.lignes[i][t.idx['Statut']] || '');
  if (!statut || statut === 'Planifiée') maj['Statut'] = 'En cours';
  ecrire_(t, i, maj);
  return b.tourneeId;
}

function actionFinTournee_(b, c) {
  const t = table_(ONGLET.TOURNEES);
  const i = trouver_(t, 'ID Tournée', b.tourneeId);
  if (i < 0) throw new Error('Tournée inconnue : ' + b.tourneeId);
  const maj = { 'Retour parc (heure)': quand_(b), 'Retour GPS': lienGps_(b.gps) };
  const statut = String(t.lignes[i][t.idx['Statut']] || '');
  if (statut !== 'Clôturée') maj['Statut'] = 'Terminée';
  ecrire_(t, i, maj);
  majCompteursTournee_(b.tourneeId);
  return b.tourneeId;
}

function actionArrivee_(b, c) {
  const t = table_(ONGLET.BL);
  const i = ligneBL_(t, b.bl);
  const quand = quand_(b);
  const maj = {
    'Arrivée (heure)': quand,
    'Arrivée GPS': lienGps_(b.gps),
    'Photo camion': enregistrerPhotos_(b.photos, b.bl + '_1_camion', quand),
  };
  const anomalies = [];
  if (!b.gps) {
    anomalies.push('GPS absent à l\'arrivée');
  } else {
    const gc = lireGps_(t.lignes[i][t.idx['GPS client']]);
    if (gc) {
      const d = Math.round(distance_(gc.lat, gc.lng, Number(b.gps.lat), Number(b.gps.lng)));
      maj['Distance client (m)'] = d;
      const max = Number(param_(P.DIST)) || 500;
      if (d > max) anomalies.push('Arrivée hors zone client (' + d + ' m)');
    }
  }
  if (!b.photos || !b.photos.length) anomalies.push('Photo camion manquante');
  statutBL_(t, i, 1, maj);
  ecrire_(t, i, maj);
  ajouterAnomalies_(t, i, anomalies);
  return b.bl;
}

function actionFinDechargement_(b, c) {
  const t = table_(ONGLET.BL);
  const i = ligneBL_(t, b.bl);
  const quand = quand_(b);
  const maj = {
    'Fin déchargement (heure)': quand,
    'Fin déchargement GPS': lienGps_(b.gps),
    'Photo marchandise': enregistrerPhotos_(b.photos, b.bl + '_2_marchandise', quand),
  };
  const anomalies = [];
  const arrivee = t.lignes[i][t.idx['Arrivée (heure)']];
  if (arrivee instanceof Date) {
    const minutes = Math.round((quand.getTime() - arrivee.getTime()) / 60000);
    maj['Durée déchargement (min)'] = minutes;
    const max = Number(param_(P.DUREE)) || 90;
    if (minutes > max) anomalies.push('Déchargement long (' + minutes + ' min)');
  }
  if (!b.gps) anomalies.push('GPS absent en fin de déchargement');
  statutBL_(t, i, 2, maj);
  ecrire_(t, i, maj);
  ajouterAnomalies_(t, i, anomalies);
  return b.bl;
}

function actionConfirmation_(b, c) {
  const t = table_(ONGLET.BL);
  const i = ligneBL_(t, b.bl);
  const quand = quand_(b);
  const resultat = RESULTATS.indexOf(b.resultat) >= 0 ? b.resultat : 'Complète';
  const maj = {
    'Photo BL signé': enregistrerPhotos_(b.photos, b.bl + '_3_BL_signe', quand),
    'Résultat livraison': resultat,
    'Quantité concernée': texte_(b.quantite),
    'Motif': texte_(b.motif),
    'Confirmation (heure)': quand,
  };
  const anomalies = [];
  if (resultat === 'Partielle') anomalies.push('Livraison partielle');
  if (resultat === 'Refusée') anomalies.push('Livraison refusée');
  if (!b.photos || !b.photos.length) anomalies.push('Photo BL signé manquante');
  statutBL_(t, i, 3, maj);
  ecrire_(t, i, maj);
  ajouterAnomalies_(t, i, anomalies);
  majCompteursTournee_(String(t.lignes[i][t.idx['ID Tournée']] || ''));
  if (resultat !== 'Complète') {
    alerter_('Livraison ' + resultat.toLowerCase() + ' – BL ' + b.bl,
      '<p>Le chauffeur <b>' + echapper_(c.nom) + '</b> a déclaré une livraison <b>' + resultat.toLowerCase() + '</b>.</p>' +
      '<p>Client : ' + echapper_(t.lignes[i][t.idx['Client']]) + '<br>Quantité concernée : ' + echapper_(b.quantite) +
      '<br>Motif : ' + echapper_(b.motif) + '</p>');
  }
  return b.bl;
}

function actionIncident_(b, c) {
  const quand = quand_(b);
  const tz = classeur_().getSpreadsheetTimeZone();
  const id = 'INC-' + Utilities.formatDate(quand, tz, 'yyyyMMdd-HHmmss') + '-' + Math.floor(Math.random() * 900 + 100);
  const niveau = NIVEAUX.indexOf(b.niveau) >= 0 ? b.niveau : 'Mineur';
  const t = table_(ONGLET.INCIDENTS);
  const ligne = {
    'ID Incident': id,
    'Date / heure': quand,
    'Chauffeur': c.nom,
    'Véhicule': texte_(b.vehicule || c.camion),
    'Catégorie': texte_(b.categorie),
    'Type': texte_(b.type),
    'Niveau': niveau,
    'N° BL': texte_(b.bl),
    'ID Tournée': texte_(b.tourneeId),
    'Description': texte_(b.description),
    'GPS': lienGps_(b.gps),
    'Photos': enregistrerPhotos_(b.photos, id, quand),
    'Statut': 'Ouvert',
  };
  ajouterLigne_(t, ligne);

  if (niveau !== 'Mineur') {
    alerter_('Incident ' + niveau.toUpperCase() + ' – ' + b.type + ' – ' + c.nom,
      '<p><b>Niveau :</b> ' + niveau + '<br><b>Catégorie :</b> ' + echapper_(b.categorie) +
      '<br><b>Type :</b> ' + echapper_(b.type) + '<br><b>Chauffeur :</b> ' + echapper_(c.nom) +
      '<br><b>Véhicule :</b> ' + echapper_(b.vehicule) + '<br><b>BL :</b> ' + echapper_(b.bl || '—') +
      '<br><b>Heure :</b> ' + Utilities.formatDate(quand, tz, 'dd/MM/yyyy HH:mm') + '</p>' +
      '<p><b>Description :</b><br>' + echapper_(b.description) + '</p>' +
      (b.gps ? '<p><a href="https://maps.google.com/?q=' + b.gps.lat + ',' + b.gps.lng + '">Voir la position sur la carte</a></p>' : '') +
      (ligne['Photos'] ? '<p>Photos :<br>' + ligne['Photos'].split('\n').map(function (u) { return '<a href="' + u + '">' + u + '</a>'; }).join('<br>') + '</p>' : ''));
  }
  return id;
}

/** Le statut d'un BL ne recule jamais (sauf modification manuelle par la logistique). */
function statutBL_(t, i, niveau, maj) {
  const actuel = STATUTS_BL.indexOf(String(t.lignes[i][t.idx['Statut']] || ''));
  if (actuel < niveau) maj['Statut'] = STATUTS_BL[niveau];
}

function ligneBL_(t, bl) {
  const i = trouver_(t, 'N° BL', bl);
  if (i < 0) throw new Error('BL inconnu : ' + bl);
  return i;
}

function ajouterAnomalies_(t, i, liste) {
  if (!liste.length) return;
  const c = t.idx['Anomalies'];
  const actuel = String(t.lignes[i][c] || '');
  const nouvelles = liste.filter(function (a) { return actuel.indexOf(a) < 0; });
  if (!nouvelles.length) return;
  const val = (actuel ? actuel + ' ; ' : '') + nouvelles.join(' ; ');
  t.feuille.getRange(i + 2, c + 1).setValue(val);
  t.lignes[i][c] = val;
}

function majCompteursTournee_(tourneeId) {
  if (!tourneeId) return;
  const bl = table_(ONGLET.BL);
  let total = 0, livres = 0;
  bl.lignes.forEach(function (r) {
    if (String(r[bl.idx['ID Tournée']] || '').trim() !== tourneeId) return;
    total++;
    if (STATUTS_BL.indexOf(String(r[bl.idx['Statut']] || '')) >= 3) livres++;
  });
  const t = table_(ONGLET.TOURNEES);
  const i = trouver_(t, 'ID Tournée', tourneeId);
  if (i >= 0) ecrire_(t, i, { 'Nb BL': total, 'Nb livrés': livres });
}

function recalculerTournees() {
  const t = table_(ONGLET.TOURNEES);
  t.lignes.forEach(function (r) {
    const id = String(r[t.idx['ID Tournée']] || '').trim();
    if (id) majCompteursTournee_(id);
  });
}

// ---------------------------------------------------------------------------
// Import des tableaux Excel des tournées
// ---------------------------------------------------------------------------

/**
 * Lit l'onglet « Import Excel » (données collées depuis le fichier Excel de la logistique),
 * crée les tournées et les BL manquants, puis vide l'onglet.
 */
function importerTournees() {
  const ss = classeur_();
  const tz = ss.getSpreadsheetTimeZone();
  const imp = table_(ONGLET.IMPORT);
  const bl = table_(ONGLET.BL);
  const tr = table_(ONGLET.TOURNEES);
  const chauffeursConnus = {};
  chauffeurs_().forEach(function (c) { chauffeursConnus[c.nom.toLowerCase()] = c; });

  const blExistants = {};
  bl.lignes.forEach(function (r) { blExistants[String(r[bl.idx['N° BL']]).trim()] = true; });
  const tourExistantes = {};
  tr.lignes.forEach(function (r) { tourExistantes[String(r[tr.idx['ID Tournée']]).trim()] = true; });

  const nouveauxBL = [], nouvellesTournees = [], erreurs = [], aGarder = [];
  let ignores = 0;
  imp.lignes.forEach(function (r, k) {
    const get = function (n) { return r[imp.idx[n]]; };
    const num = String(get('N° BL') || '').trim();
    if (!num) return;
    if (blExistants[num]) { ignores++; return; }
    const date = lireDate_(get('Date'));
    const chauffeur = String(get('Chauffeur') || '').trim();
    if (!date || !chauffeur) { erreurs.push('BL ' + num + ' : date ou chauffeur manquant (ligne laissée dans l\'onglet Import)'); aGarder.push(r); return; }
    const camion = String(get('Camion') || '').trim() ||
      (chauffeursConnus[chauffeur.toLowerCase()] ? chauffeursConnus[chauffeur.toLowerCase()].camion : '');
    let tid = String(get('N° Tournée (optionnel)') || '').trim();
    if (!tid) tid = 'T' + Utilities.formatDate(date, tz, 'yyMMdd') + '-' + (camion || chauffeur).replace(/[^A-Za-z0-9]/g, '').toUpperCase();
    if (!tourExistantes[tid]) {
      tourExistantes[tid] = true;
      const o = {}; o['ID Tournée'] = tid; o['Date'] = date; o['Chauffeur'] = chauffeur; o['Camion'] = camion; o['Statut'] = 'Planifiée';
      nouvellesTournees.push(o);
    }
    blExistants[num] = true;
    const ligne = {};
    ligne['N° BL'] = num; ligne['ID Tournée'] = tid; ligne['Date'] = date; ligne['Chauffeur'] = chauffeur;
    ligne['Camion'] = camion; ligne['Ordre'] = Number(get('Ordre')) || (nouveauxBL.length + 1);
    ligne['Client'] = get('Client'); ligne['Adresse'] = get('Adresse');
    ligne['GPS client'] = String(get('GPS client (optionnel : lat,lng)') || '');
    ligne['Marchandise'] = get('Marchandise'); ligne['Quantité prévue'] = get('Quantité');
    ligne['Statut'] = 'À livrer';
    nouveauxBL.push(ligne);
    if (!chauffeursConnus[chauffeur.toLowerCase()]) erreurs.push('Chauffeur « ' + chauffeur + ' » absent de l\'onglet Chauffeurs (BL ' + num + ')');
  });

  ajouterLignes_(tr, nouvellesTournees);
  ajouterLignes_(bl, nouveauxBL);
  if (imp.lignes.length) {
    // Vide l'onglet Import en ne gardant que les lignes à corriger
    imp.feuille.getRange(2, 1, imp.lignes.length, imp.entetes.length).clearContent();
    if (aGarder.length) imp.feuille.getRange(2, 1, aGarder.length, imp.entetes.length).setValues(aGarder);
  }
  const tids = {};
  nouveauxBL.forEach(function (l) { tids[l['ID Tournée']] = true; });
  Object.keys(tids).forEach(majCompteursTournee_);

  const msg = nouvellesTournees.length + ' tournée(s) et ' + nouveauxBL.length + ' BL importés.' +
    (ignores ? '\n' + ignores + ' BL déjà présents ignorés.' : '') +
    (erreurs.length ? '\n\nÀ vérifier :\n' + erreurs.slice(0, 20).join('\n') : '');
  try { SpreadsheetApp.getUi().alert('Import des tournées', msg, SpreadsheetApp.getUi().ButtonSet.OK); } catch (e) { Logger.log(msg); }
}

// ---------------------------------------------------------------------------
// Rapport quotidien et alertes
// ---------------------------------------------------------------------------

function rapportQuotidien() {
  const ss = classeur_();
  const tz = ss.getSpreadsheetTimeZone();
  const auj = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const bl = table_(ONGLET.BL);
  const inc = table_(ONGLET.INCIDENTS);
  const g = function (r, n) { return r[bl.idx[n]]; };

  const duJour = bl.lignes.filter(function (r) { return g(r, 'N° BL') && dateIso_(g(r, 'Date'), tz) === auj; });
  const realisees = duJour.filter(function (r) { return STATUTS_BL.indexOf(String(g(r, 'Statut'))) >= 3; });
  const nonTerminees = duJour.filter(function (r) { return STATUTS_BL.indexOf(String(g(r, 'Statut'))) < 3; });
  const anomalies = duJour.filter(function (r) { return String(g(r, 'Anomalies') || ''); });
  const incidentsJour = inc.lignes.filter(function (r) { return r[inc.idx['ID Incident']] && dateIso_(r[inc.idx['Date / heure']], tz) === auj; });
  const aValider = bl.lignes.filter(function (r) { return String(g(r, 'Statut')) === 'Livré – à valider'; });
  const incOuverts = inc.lignes.filter(function (r) { return r[inc.idx['ID Incident']] && String(r[inc.idx['Statut']]) !== 'Clôturé'; });

  const heure = function (v) { return v instanceof Date ? Utilities.formatDate(v, tz, 'HH:mm') : ''; };
  const tableau = function (titres, lignes) {
    if (!lignes.length) return '<p style="color:#757575">Aucun.</p>';
    return '<table style="border-collapse:collapse;font-size:13px" border="1" cellpadding="4"><tr style="background:#0D47A1;color:#fff">' +
      titres.map(function (x) { return '<th>' + x + '</th>'; }).join('') + '</tr>' +
      lignes.map(function (l) { return '<tr>' + l.map(function (x) { return '<td>' + echapper_(x) + '</td>'; }).join('') + '</tr>'; }).join('') +
      '</table>';
  };

  const html =
    '<h2 style="color:#0D47A1">Rapport quotidien SAS CI – ' + Utilities.formatDate(new Date(), tz, 'dd/MM/yyyy') + '</h2>' +
    '<p><b>' + duJour.length + '</b> BL prévus aujourd\'hui • <b>' + realisees.length + '</b> livrés • <b>' + nonTerminees.length +
    '</b> non terminés • <b>' + anomalies.length + '</b> avec anomalie • <b>' + incidentsJour.length + '</b> incident(s) déclaré(s)</p>' +
    '<h3>Livraisons réalisées</h3>' + tableau(['BL', 'Chauffeur', 'Client', 'Arrivée', 'Fin', 'Durée (min)', 'Résultat'],
      realisees.map(function (r) { return [g(r, 'N° BL'), g(r, 'Chauffeur'), g(r, 'Client'), heure(g(r, 'Arrivée (heure)')), heure(g(r, 'Fin déchargement (heure)')), g(r, 'Durée déchargement (min)'), g(r, 'Résultat livraison')]; })) +
    '<h3>Livraisons non terminées</h3>' + tableau(['BL', 'Chauffeur', 'Client', 'Statut'],
      nonTerminees.map(function (r) { return [g(r, 'N° BL'), g(r, 'Chauffeur'), g(r, 'Client'), g(r, 'Statut')]; })) +
    '<h3>Anomalies</h3>' + tableau(['BL', 'Chauffeur', 'Client', 'Anomalies'],
      anomalies.map(function (r) { return [g(r, 'N° BL'), g(r, 'Chauffeur'), g(r, 'Client'), g(r, 'Anomalies')]; })) +
    '<h3>Incidents déclarés</h3>' + tableau(['ID', 'Heure', 'Chauffeur', 'Niveau', 'Type', 'BL', 'Description'],
      incidentsJour.map(function (r) { const x = function (n) { return r[inc.idx[n]]; }; return [x('ID Incident'), heure(x('Date / heure')), x('Chauffeur'), x('Niveau'), x('Type'), x('N° BL'), x('Description')]; })) +
    '<h3>Dossiers restant à traiter</h3>' +
    '<p><b>' + aValider.length + '</b> BL en attente de validation • <b>' + incOuverts.length + '</b> incident(s) non clôturé(s)</p>' +
    tableau(['BL', 'Date', 'Chauffeur', 'Client', 'Résultat'],
      aValider.map(function (r) { return [g(r, 'N° BL'), dateIso_(g(r, 'Date'), tz), g(r, 'Chauffeur'), g(r, 'Client'), g(r, 'Résultat livraison')]; })) +
    '<p><a href="' + ss.getUrl() + '">Ouvrir le tableau de suivi</a></p>';

  const dest = String(param_(P.EMAILS) || '').trim();
  if (!dest) { Logger.log('Aucun destinataire configuré'); return; }
  MailApp.sendEmail({ to: dest, subject: '[SAS CI] Rapport quotidien des livraisons – ' + Utilities.formatDate(new Date(), tz, 'dd/MM/yyyy'), htmlBody: html });
}

function alerter_(sujet, html) {
  const dest = String(param_(P.EMAILS) || '').trim();
  if (!dest) return;
  try {
    MailApp.sendEmail({ to: dest, subject: '[SAS CI] ' + sujet, htmlBody: html + '<p><a href="' + classeur_().getUrl() + '">Ouvrir le tableau de suivi</a></p>' });
  } catch (e) { Logger.log('Alerte non envoyée : ' + e); }
}

// ---------------------------------------------------------------------------
// Aides : connexion, données de test
// ---------------------------------------------------------------------------

function afficherConnexion() {
  let url = '';
  try { url = ScriptApp.getService().getUrl() || ''; } catch (e) { url = ''; }
  const urlOk = /\/exec$/.test(url);
  const blocUrl = urlOk
    ? '<textarea style="width:100%;height:60px" readonly>' + url + '</textarea>'
    : '<div style="background:#FFF3E0;padding:8px;border-radius:6px">L\'adresse <b>/exec</b> ne peut pas être lue automatiquement ici.<br>' +
      'Dans l\'éditeur Apps Script : <b>Déployer &gt; Gérer les déploiements</b>, sélectionnez le déploiement « Application Web » ' +
      'et copiez l\'<b>URL qui se termine par /exec</b>.<br><span style="color:#C62828">N\'utilisez jamais l\'adresse qui se termine par /dev ' +
      '(elle ne fonctionne pas sur les téléphones).</span></div>';
  const html = HtmlService.createHtmlOutput(
    '<div style="font-family:Arial;font-size:14px">' +
    '<p><b>URL du serveur :</b><br>' + blocUrl + '</p>' +
    '<p><b>Clé API :</b><br><input style="width:100%" readonly value="' + echapper_(cleApi_()) + '"></p>' +
    '<p><b>Vérification :</b> ouvrez l\'URL /exec dans un navigateur <i>en navigation privée</i> : vous devez voir ' +
    '<code>{"ok":true,"service":"SAS CI – Suivi des livraisons"}</code>. Si Google demande de se connecter, ' +
    'le déploiement n\'est pas en « Qui a accès : Tout le monde ».</p>' +
    '<p style="color:#C62828">Après chaque modification du script : Déployer &gt; Gérer les déploiements &gt; Modifier &gt; Version : Nouvelle version &gt; Déployer.</p></div>'
  ).setWidth(560).setHeight(420);
  SpreadsheetApp.getUi().showModalDialog(html, 'Connexion de l\'application');
}

function creerDonneesTest() {
  const ss = classeur_();
  const ch = ss.getSheetByName(ONGLET.CHAUFFEURS);
  if (trouver_(table_(ONGLET.CHAUFFEURS), 'Nom', 'Chauffeur Test') < 0) {
    ch.appendRow(['Chauffeur Test', '1234', 'CAMION-01', '', 'Oui']);
  }
  const imp = ss.getSheetByName(ONGLET.IMPORT);
  const d = new Date(); d.setHours(0, 0, 0, 0);
  const s = String(Date.now()).slice(-5);
  imp.getRange(imp.getLastRow() + 1, 1, 3, COLONNES.IMPORT.length).setValues([
    [d, '', 'Chauffeur Test', 'CAMION-01', 1, 'BL-T' + s + '1', 'Client Alpha', 'Zone industrielle, Abidjan', '5.3364,-4.0267', 'Ciment 50 kg', '200 sacs'],
    [d, '', 'Chauffeur Test', 'CAMION-01', 2, 'BL-T' + s + '2', 'Client Beta', 'Rue 12, Cocody', '', 'Fer à béton', '50 barres'],
    [d, '', 'Chauffeur Test', 'CAMION-01', 3, 'BL-T' + s + '3', 'Client Gamma', 'Yopougon', '', 'Peinture', '30 seaux'],
  ]);
  importerTournees();
}

// ---------------------------------------------------------------------------
// Utilitaires
// ---------------------------------------------------------------------------

function classeur_() {
  try {
    const a = SpreadsheetApp.getActiveSpreadsheet();
    if (a) return a;
  } catch (e) { /* contexte web app */ }
  return SpreadsheetApp.openById(PropertiesService.getScriptProperties().getProperty('SS_ID'));
}

function table_(nom) {
  const sh = classeur_().getSheetByName(nom);
  if (!sh) throw new Error('Onglet manquant : ' + nom + ' (lancer SAS CI > Installer / réparer)');
  const valeurs = sh.getDataRange().getValues();
  const entetes = (valeurs[0] || []).map(function (h) { return String(h).trim(); });
  const idx = {};
  entetes.forEach(function (h, i) { if (h) idx[h] = i; });
  return { feuille: sh, entetes: entetes, idx: idx, lignes: valeurs.slice(1) };
}

function indexColonne_(sh, nom) {
  const entetes = sh.getRange(1, 1, 1, Math.max(sh.getLastColumn(), 1)).getValues()[0].map(function (h) { return String(h).trim(); });
  return entetes.indexOf(nom);
}

function trouver_(t, colonne, valeur) {
  const c = t.idx[colonne];
  const v = String(valeur || '').trim();
  if (c === undefined || !v) return -1;
  for (let i = 0; i < t.lignes.length; i++) {
    if (String(t.lignes[i][c]).trim() === v) return i;
  }
  return -1;
}

function ecrire_(t, i, obj) {
  Object.keys(obj).forEach(function (k) {
    const c = t.idx[k];
    if (c === undefined) throw new Error('Colonne manquante « ' + k + ' » (lancer SAS CI > Installer / réparer)');
    const cell = t.feuille.getRange(i + 2, c + 1);
    const v = obj[k];
    if (typeof v === 'string' && v.indexOf('=HYPERLINK(') === 0) cell.setFormula(v); else cell.setValue(v);
    t.lignes[i][c] = v;
  });
}

function ajouterLigne_(t, obj) { ajouterLignes_(t, [obj]); }

function ajouterLignes_(t, objs) {
  if (!objs.length) return;
  const n = t.entetes.length;
  const rows = objs.map(function (o) {
    return t.entetes.map(function (h) { return o[h] !== undefined && o[h] !== null ? o[h] : ''; });
  });
  const debut = t.feuille.getLastRow() + 1;
  t.feuille.getRange(debut, 1, rows.length, n).setValues(rows);
  rows.forEach(function (r) { t.lignes.push(r); });
}

function journal_(id, action, chauffeur, ref) {
  if (!id) return;
  classeur_().getSheetByName(ONGLET.JOURNAL).appendRow([id, new Date(), action, chauffeur, ref || '']);
}

function dejaRecu_(id) {
  const sh = classeur_().getSheetByName(ONGLET.JOURNAL);
  if (sh.getLastRow() < 2) return false;
  return !!sh.getRange(2, 1, sh.getLastRow() - 1, 1).createTextFinder(String(id)).matchEntireCell(true).findNext();
}

function param_(nom) {
  const sh = classeur_().getSheetByName(ONGLET.PARAMS);
  if (!sh || sh.getLastRow() < 1) return '';
  const v = sh.getRange(1, 1, sh.getLastRow(), 2).getValues();
  for (let i = 0; i < v.length; i++) if (String(v[i][0]).trim() === nom) return v[i][1];
  return '';
}

function setParam_(nom, valeur) {
  const sh = classeur_().getSheetByName(ONGLET.PARAMS);
  const v = sh.getRange(1, 1, sh.getLastRow(), 1).getValues();
  for (let i = 0; i < v.length; i++) {
    if (String(v[i][0]).trim() === nom) { sh.getRange(i + 1, 2).setValue(valeur); return; }
  }
  sh.appendRow([nom, valeur, '']);
}

function dossierPhotos_() {
  const id = String(param_(P.DOSSIER) || '').trim();
  if (id) {
    try { return DriveApp.getFolderById(id); } catch (e) { /* recréé ci-dessous */ }
  }
  const f = DriveApp.createFolder('SAS CI - Photos livraisons');
  setParam_(P.DOSSIER, f.getId());
  return f;
}

function enregistrerPhotos_(photos, nom, quand) {
  if (!photos || !photos.length) return '';
  const tz = classeur_().getSpreadsheetTimeZone();
  const racine = dossierPhotos_();
  const jour = Utilities.formatDate(quand, tz, 'yyyy-MM-dd');
  const it = racine.getFoldersByName(jour);
  const dossier = it.hasNext() ? it.next() : racine.createFolder(jour);
  const base = String(nom).replace(/[^A-Za-z0-9_-]/g, '_');
  return photos.map(function (b64, k) {
    const blob = Utilities.newBlob(Utilities.base64Decode(b64), 'image/jpeg',
      base + '_' + Utilities.formatDate(quand, tz, 'HHmmss') + '_' + (k + 1) + '.jpg');
    return dossier.createFile(blob).getUrl();
  }).join('\n');
}

function quand_(b) {
  const t = Number(b.time);
  return t > 0 ? new Date(t) : new Date();
}

function lienGps_(g) {
  if (!g || g.lat === undefined || g.lng === undefined) return '';
  const lat = Number(g.lat).toFixed(6), lng = Number(g.lng).toFixed(6);
  const acc = g.acc !== undefined ? ' (±' + Math.round(Number(g.acc)) + ' m)' : '';
  return '=HYPERLINK("https://maps.google.com/?q=' + lat + ',' + lng + '","' + lat + ',' + lng + acc + '")';
}

function lireGps_(v) {
  const m = String(v || '').match(/(-?\d+(?:\.\d+)?)\s*[,; ]\s*(-?\d+(?:\.\d+)?)/);
  return m ? { lat: Number(m[1]), lng: Number(m[2]) } : null;
}

function distance_(lat1, lon1, lat2, lon2) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad, dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function dateIso_(v, tz) {
  if (v instanceof Date) return Utilities.formatDate(v, tz, 'yyyy-MM-dd');
  const d = lireDate_(v);
  return d ? Utilities.formatDate(d, tz, 'yyyy-MM-dd') : '';
}

/** Accepte une date Google Sheets, "jj/mm/aaaa" ou "aaaa-mm-jj". */
function lireDate_(v) {
  if (v instanceof Date) return v;
  const s = String(v || '').trim();
  let m = s.match(/^(\d{1,2})[\/.-](\d{1,2})[\/.-](\d{2,4})/);
  if (m) {
    const y = Number(m[3]) < 100 ? 2000 + Number(m[3]) : Number(m[3]);
    return new Date(y, Number(m[2]) - 1, Number(m[1]));
  }
  m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return null;
}

/** Empêche l'injection de formules depuis le texte saisi par le chauffeur. */
function texte_(v) {
  const s = String(v === undefined || v === null ? '' : v);
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function echapper_(v) {
  return String(v === undefined || v === null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function lettre_(n) {
  let s = '';
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}
