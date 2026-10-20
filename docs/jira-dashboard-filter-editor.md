# Dashboard-Filter direkt bearbeiten (Jira Data Center)

Neue, eigenständige ScriptRunner-Datei: `jira/dashboardFilterEditor.groovy`.
Vorhandene Endpoints werden nicht verändert. Zielplattform: Jira Data Center 11 (Jakarta) und eine ScriptRunner-Version mit HAPI `Users`.

## Installation

1. `jira/dashboardFilterEditor.groovy` in das ScriptRunner-Skriptverzeichnis kopieren.
2. Unter **Administration → ScriptRunner → REST Endpoints** einen Custom Endpoint
   als **Datei** registrieren und diese Datei auswählen.
3. Als angemeldeter Jira-Administrator öffnen:

   ```text
   https://<jira>/rest/scriptrunner/latest/custom/dashboardFilterEditor
   ```

   Bei einem Jira-Kontextpfad diesen vor `/rest` setzen.

Die beiden Deklarationen sind auf `jira-administrators` beschränkt. Bei abweichender
Administratorgruppe beide `groups`-Angaben anpassen. Zusätzlich prüfen beide
Routen die globale Jira-Berechtigung **Administer Jira**.

## Bedienung

- Dashboard-Namen oder ID eingeben und **Suchen** wählen. Namen werden ohne
  Beachtung der Großschreibung als Teilstring gesucht; IDs exakt.
- Dashboard auswählen. Auch private Dashboards werden für Administratoren aus
  der lokalen Jira-Konfiguration gelesen.
- Unter **Charts und verwendete Filter** steht pro Gadget der verwendete
  gespeicherte Filter mit Name und ID. Kreisdiagramm und zweidimensionale
  Filterstatistik erhalten verständliche Gadget-Namen. Ein Klick auf den
  Filternamen springt zur JQL desselben Filters weiter unten.
- Nutzt ein Chart stattdessen ein Projekt oder direkt hinterlegte JQL, wird diese
  Datenquelle angezeigt. Die Projekt-ID bzw. die aufklappbare JQL lässt sich
  nachsehen; daraus wird kein gespeicherter Filter zum Bearbeiten erzeugt.
- Jeder erkannte Filter erscheint einmal mit ID, Eigentümer, referenzierenden
  Gadget-Namen und IDs und direkt bearbeitbarem JQL-Textfeld. Mehrere Charts mit
  demselben Filter teilen sich einen Editor.
- JQL anpassen und am betreffenden Filter **JQL speichern** wählen. Syntax und
  JQL-Validierung laufen unter dem angemeldeten Administrator; es werden keine
  Issues gesucht. Fehler erscheinen an demselben Filter.

Speichern aktualisiert den bestehenden Filter. Der angemeldete Benutzer kommt
aus HAPI `Users`; Filter werden über Jiras `SearchRequestManager` geladen und
aktualisiert. Eine HAPI-Klasse `Filters` wird nicht vorausgesetzt. Der Endpoint
ist eine administrative Wartungsfunktion für bestehende, auch fremde Filter.
Es erfolgt keine Benutzer-Impersonation; verweigerte Änderungen erscheinen als Fehler.
Name, Eigentümer, Freigaben und Gadget-Einstellungen werden nicht geändert.
Die neue JQL wirkt überall, wo derselbe Filter genutzt wird, einschließlich
Abonnements. Die Validierung als Administrator beweist nicht, dass alle anderen
Benutzer dieselben Projekte oder Issues sehen können.

## Abdeckung der Gadgets

Direkte Filterreferenzen aus den Jira-Gadget-Präferenzen `filterId`, `filter`,
`filterIds` sowie `projectOrFilterId=filter-<ID>` werden erkannt und dedupliziert.
`projectOrFilterId=project-<ID>` wird als Projektquelle angezeigt; `jql-<JQL>`
in einer Standard-Quellpräferenz als direkte Gadget-JQL. Diese Quellen sind
keine gespeicherten Filter und werden nur angezeigt.
Die Daten werden lesend über die Jira-Entities `PortalPage`, `PortletConfiguration`
und `GadgetUserPreference` geladen; es gibt keine direkten Datenbank-Schreibzugriffe.

Die Übersicht zeigt für jedes Gadget, ob eine Standardquelle gefunden wurde
oder es ungeprüft bleibt. Unlesbare oder gelöschte Filter und Lesefehler werden
sichtbar ausgewiesen. Ein defekter Filter verdeckt keine weiteren Referenzen
desselben Gadgets. Eine leere Filterliste bedeutet deshalb nicht automatisch, dass
keines der Gadgets Filter verwendet. App-Gadgets mit eigener Konfigurationsablage,
eingebettete JQL und Filter, die innerhalb anderer Filter referenziert werden,
sind nicht automatisch abgedeckt. Für solche Apps muss deren konkrete Ablage
zusätzlich integriert werden.

Unter **Datenquelle prüfen** stehen die ausgelesenen Standard-Quellpräferenzen
mit ihrem gespeicherten Wert sowie die Namen der vorhandenen Einstellungen.
Das hilft insbesondere bei einem Kreisdiagramm, das bisher nur als „ungeprüft“
erschien: `project-…` bezeichnet ein Projekt, `filter-…` einen gespeicherten Filter.
Nicht erkannte Werte bleiben sichtbar und werden nicht als Filter-ID geraten.
Werte anderer Gadget-Einstellungen werden nicht ausgegeben. Die Originalkennung
des Gadget-Typs bleibt sichtbar; die lesbaren Namen werden lokal aus bekannten
Standarddeskriptoren bestimmt, ohne Abruf der Gadget-URL.

## Schreibschutz und parallele Änderungen

GET liest ausschließlich. Der separate POST-Endpoint `dashboardFilterEditorSave`
fordert JSON, eine bestehende Sitzung, ein sitzungsgebundenes CSRF-Token im
zusätzlichen Request-Header und erneut Administratorrechte. Vor jedem Schreiben
wird geprüft, ob der Filter noch zum Dashboard gehört und die ursprüngliche JQL
noch unverändert ist. Endpoint-Schreibzugriffe werden je Filter mit einem
Data-Center-Cluster-Lock serialisiert. Andere Jira-Editoren verwenden diesen Lock
nicht; eine exakt gleichzeitige externe Änderung zwischen Vergleich und Schreiben
ist damit nicht vollständig ausgeschlossen. Nach einem Konflikt das Dashboard
neu laden. Bei Speicherfehlern ebenfalls neu laden, um den tatsächlichen Zustand
zu prüfen. Erfolgreiche Änderungen werden mit Benutzer-, Dashboard- und Filter-ID
im Serverprotokoll erfasst, ohne den JQL-Inhalt zu protokollieren.

## Lokale Prüfung

Vom Repository-Stamm mit Groovy 3.0.21 und Node:

```bash
groovy tools/parsecheck.groovy jira/dashboardFilterEditor.groovy
groovy jira/tests/dashboardFilterEditor.tests.groovy
groovy jira/tests/dashboardFilterEditor.compilation.tests.groovy
node --test tools/tests/dashboard-filter-editor-browser.tests.mjs
```

Diese Prüfungen belegen Syntax, reine Filter-/Speicherlogik und den Browserablauf
mit synthetischen Antworten. Sie lösen keine Jira-Typen auf. Eine Installation
oder Kompatibilitätsprüfung auf einer echten Jira-Instanz wurde hier nicht ausgeführt.
Vor produktiver Nutzung auf einem Testsystem prüfen: Namens-/ID-Suche, ein privates
Dashboard, mehrere Standardgadgets mit demselben und unterschiedlichen Filtern,
ein App-Gadget, einen fremden Filter, ungültige JQL, Konflikt nach externer Änderung,
Abweisung ohne Administratorrechte und ohne CSRF-Token sowie Erhalt von Eigentümer
und Freigaben nach dem Speichern. Insbesondere die Jira-Entity-Felder und
`SearchRequestManager.update(ApplicationUser, SearchRequest)` müssen gegen die
installierte Jira-Version geprüft werden.

## ScriptRunner-Typprüfung und Zeichenkodierung

Die Datei ist ausschließlich ASCII-kodiert. Deutsche UI-Texte verwenden
Groovy-Unicode-Escapes; zur Laufzeit erscheinen normale Umlaute. Dadurch kann
auch eine Zwischenablage mit falscher UTF-8-Erkennung den Quelltext nicht mehr
in Zeichenfolgen wie `Ã¤` umwandeln. Die neue Datei vollständig ersetzen; bereits
beschädigten Text im Editor nicht weiterverwenden.

Die dynamischen Jira-/Servlet-Zugriffe und die ScriptRunner-Registrierung sind
explizit mit `@TypeChecked(TypeCheckingMode.SKIP)` gekennzeichnet. Das ist der
Groovy-Modus für diese dynamischen Plattformgrenzen. Die REST-Closures verwenden
für Jira 11 explizit Jakarta `MultivaluedMap<String, String>`,
`HttpServletRequest` und `HttpSession`. Deren Query- und Session-Zugriffe werden
zusätzlich mit eingeschalteter Typprüfung geprüft. Die Registrierung liegt in einer entsprechend
markierten Methode, damit die enthaltenen Closures denselben Modus verwenden.
Ein zusätzlicher Test kompiliert das vollständige Skript mit aktivierter
`TypeChecked`-Transformation und prüft beide registrierten Administratorrouten.
Die Plattform-Stubs dieses Tests ersetzen keine echte Jira-API-Prüfung.

Der angemeldete Benutzer wird im HAPI-Stil über `Users.getLoggedInUser()` ermittelt.
JQL-Parsing und -Validierung bleiben bei Jiras `SearchService`.

## JQL-Validierungsanzeige

Jedes JQL-Feld wird beim Laden und 500 ms nach der letzten Eingabe mit Jiras
`SearchService.parseQuery` und `validateQuery` geprüft. Ein grüner Kreis mit
Häkchen kennzeichnet gültige JQL. Ungültige JQL zeigt ein rotes Symbol und die
Jira-Fehlermeldungen; bei Verbindungsfehlern bleibt der Zustand ungeprüft.
Neue Eingaben entfernen sofort eine vorherige Erfolgsmeldung. Veraltete Antworten
werden verworfen. Die Prüfung erfolgt per sitzungs- und CSRF-geschütztem POST
an `dashboardFilterEditorSave?action=validate` ohne Speicherung und ohne Issue-Suche.
Die vorhandene Prüfung beim eigentlichen Speichern bleibt bestehen.
