# 🔍 Sicherheits- & Bug-Audit – EconomySystem

**Revision:** 5
**Datum:** 20.11.2026
**Prüfstand:** Arbeitskopie über Commit `853e0d2` (nicht committete Umbauten: Modul-Befehlsbaum, Netzwerk-/Audit-Schicht, ROT-2-Fix, Rate-Limit-Durchsetzung, GUI-Eingabeprüfung)
**Prüfumfang:** Gesamtes Projekt – 72 Java-Dateien in `src/main/java`, 6 Testklassen in `src/test`, alle Ressourcen (`settings.yml`, `messages.yml`, `commands.yml`, `gui.yml`, `help.yml`, `plugin.yml`), `pom.xml`, `.github/workflows/build.yml`
**Methode:** Vollständige Code-Review aller sicherheitsrelevanten Pfade (Router, Speicher, Geldlogik, GUI, Konfiguration), gezielte Suchläufe nach gefährlichen Mustern (`Runtime.exec`, `ObjectInputStream`, `Class.forName`, `setAccessible`, `performCommand`, SQL-Konkatenation), automatisierter Abgleich aller im Code verwendeten Message-Keys gegen `messages.yml`, sowie vollständiger Kompilierlauf mit `javac`.

---

## 📊 Übersicht

| Schweregrad | Anzahl | Kern |
|---|---|---|
| 🔴 **EXTREM** | **0** | Kein Weg für unprivilegierte Spieler zu Geldvermehrung oder Rechteausweitung gefunden. |
| 🔴 **ROT** | **0** | Keine Befunde. |
| 🟠 **ORANGE** | **5** | `tests`-Recht eigenständig; Audit faktisch leer; eine defekte Kontodatei stoppt alles; Fernbuchung nicht idempotent; Namenssuche kann falsches Konto treffen. |
| 🟡 **GELB** | **18** | Fehlende Schreibsicherung bei Shutdown, wirkungslose Flags, Rechte-/Doku-Abweichungen, Namens- und Locale-Fallstricke, Lease- und Zyklus-Randfälle, Build-Reproduzierbarkeit. |
| **Summe** | **23** | |

> **Wichtigster Hinweis:** Revision 2 dieses Dokuments beschreibt die frühere Struktur (`AbstractEconomyCommand`, `MoneyCommand`, `PayCommand`, `EconomySystemAdminCommand`). Diese Klassen sind **gelöscht**; Befehle sind jetzt Module in einem Baum (`command/module/*`), Routing und Prüfreihenfolge sitzen in `CommandRegistry`. Alle Aussagen der Revision 2 mit Datei-/Zeilennummern sind damit **überholt** und wurden vollständig ersetzt.

> **Fazit:** Die Basis ist solide – Prepared Statements durchgängig, `BigDecimal` überall, echte Speicher-Transaktionen für Kredite, fail-fast statt Blockade auf dem Tick-Thread, Shutdown mit 60-Sekunden-Frist. Die verbliebenen Risiken liegen **nicht** in klassischen Exploits, sondern in **Umgehungswegen an den Schutzmechanismen vorbei** (Audit) und in **fehlender Fehlertoleranz und Konsistenz** an den Rändern (defekte Kontodatei, Idempotenz, Namensauflösung).

---

# 🔴 EXTREM

**Keine Befunde.**

Begründung (geprüft und ausgeschlossen):

- **Kein Weg zu Geldvermehrung ohne Recht.** Alle zwölf Pfade, die Geld erzeugen oder verschieben (`admin.balance.add|remove|set|reset`, `credit.take`, `credit.repay`, `pay`, `admin.test.*`), liegen entweder unter `admin` (Router-Prüfung Stufe 4, `CommandRegistry.java:429`) oder prüfen ihr Recht im Modul selbst. `BalanceModule.permission()` und `HelpModule.permission()` sind bewusst leer – beide prüfen anschließend selbst bzw. lesen nur.
- **Keine SQL-Injection.** Sämtliche Werte laufen über `PreparedStatement`; alle interpolierten Bezeichner stammen ausschließlich aus dem `table-prefix`, der in `MySqlStorage.java:52` gegen `[A-Za-z0-9_]{0,40}` validiert wird.
- **Keine Code-/Befehlausführung aus Eingaben:** GUI-Aufrufe laufen als genau eine Befehlszeile über `CommandRegistry.dispatchGui`, und die Antworten der Inventar-Eingaben werden seit Revision 7 vor dem Einsetzen je Typ geprüft (kein Leerzeichen, kein führendes `--`, Typprüfung) – ein Textfeld kann damit weder einen zweiten Befehl noch zusätzliche Argumente einschleusen.
- **Keine Deserialisierung, kein `Runtime.exec`, kein Reflective-Loading.** `Class.forName` wird nur für den MySQL-Treiber verwendet (`MySqlStorage.java:84`).
- **Kein Auth-Bypass im Router.** Die Reihenfolge (Pfad → Herkunft → `READ_ONLY` → Permission → Syntax → Bestätigung → Cooldown) steht an genau einer Stelle und wird von keinem Modul umgangen; `ReadOnlyPolicy` ist eine Positivliste, neue Pfade sind dort automatisch gesperrt.

---

# 🔴 ROT

**Keine Befunde.**

---

# 🟠 ORANGE

## ORANGE-1: `economysystem.tests` schaltet die Testpfade **allein** frei

**Stellen:** `CommandPermissions.java:148-150`, `AdminTestInterestCycleModule.java:66-76`

```java
if (path.startsWith("admin.test")) { candidates.add("economysystem.tests"); }
```

`candidates()` liefert damit für `/eco admin test …` **drei** unabhängige Freischaltungen: `economysystem.command.admin` (bzw. `economysystem.admin`) **oder** `economysystem.tests`. Laut README und `plugin.yml` ist `economysystem.tests` ausdrücklich „**zusätzlich** zu `economysystem.admin`“ gedacht. Wer also nur `economysystem.tests` erhält (etwa ein „Test-Operator“, der Geldbefehle nicht darf), bekommt vollen Zugriff auf die Zinssimulation – und die **erzeugt echtes Geld** (`Zinszyklen` buchen Zinsen auf alle Konten). Voraussetzung ist lediglich `tests.enabled: true` in der `settings.yml`, was laut README der übliche Weg ist, die Funktion überhaupt zu nutzen.

**Wirkung:** Rechteausweitung gegenüber dem dokumentierten Modell, mit Geldvermehrung als Folge. `commands.yml` bildet zusätzlich `tests` als Synonym auf `test` ab, sodass auch `/economysystem-admin tests zins-zyklus add 1000` diesen Weg nimmt.

**Empfehlung:** `economysystem.tests` **nicht** als Alternative in `candidates()` führen – die Prüfung im Modul (`run()`) genügt als Zusatzhürde. Alternativ in der Doku klarstellen, dass das Recht allein ausreicht.

## ORANGE-2: Das Audit ist für alle lokalen Geldbewegungen faktisch leer

**Stellen:** `CommandModule.java:78-80` (`auditActions()`), `AuditEntry.java`, `MySqlStorage.java:600-660`, `EconomyService.java:570` (`recordTransferAudit`)

`auditActions()` ist nicht von einem einzigen Modul überschrieben (Suchlauf: nur die Default-Deklaration). Ein Audit-Eintrag entsteht ausschließlich in `EconomyService.recordTransferAudit()` – und der wird nur von `remoteTransferAsync()`, also **nur bei Fernbuchungen** aufgerufen. Damit bleiben ohne Spur:

- `/pay` an einen **lokalen** Empfänger (der Normalfall),
- alle `admin.balance.add|remove|set|reset`,
- Kreditaufnahme, Rückzahlung, Zwangsabbuchung, Zinserlass,
- Zinszyklen und Testzyklen.

Erschwerend: Das Flag `--reason` wird geparst, auf 160 Zeichen gekürzt, auf Steuerzeichen gefiltert (`CommandParser.java:184-198`) – aber **von keinem Modul gelesen** (Suchlauf: kein `flag("reason")`). Die Begründung landet also nirgends, obwohl sie überall im Hilfetext steht.

**Wirkung:** Ein Economy-Plugin ohne Spur kann Missbrauch durch eigene Teammitglieder nicht aufklären; genau dafür ist `AuditEntry` (mit `actor`, `origin`, `action`, `balance_after`, `result`) gebaut.

**Empfehlung:** `auditActions()` je Modul füllen, und in `AbstractModule` einen Helfer `audit(context, action, target, amount, reason, result)` bereitstellen, der über den Storage-Executor schreibt. `--reason` an diesen Helfer durchreichen.

## ORANGE-3: Eine einzige defekte Kontodatei legt den gesamten YAML-Betrieb lahm

**Stellen:** `YamlStorage.java:134`, `YamlStorage.java:156-170`

```java
BigDecimal balance; try { … } catch (NumberFormatException e) {
    throw new StorageException("Ungültiges Guthaben in " + file.getName() …, e);
}
```

`load()` wirft bei einer unlesbaren Datei. `loadAll()` fängt das **nicht** ab und iteriert ohne Schutz:

```java
for (File file : files) { … load(uniqueId).ifPresent(accounts::add); }
```

Damit reißt ein einziger beschädigter Account (abgeschnittene Datei nach Stromausfall, manuell bearbeitetes `balance: abc`, doppeltes YAML-Dokument) **alle** Aufrufer mit: den Zinszyklus, `/eco balance top`, die Spielerauswahl im GUI und `accountsAsync()`.

**Wirkung:** Kein Zinszyklus mehr für **niemanden**, keine Bestenliste, keine Spielerauswahl – bis ein Administrator die Datei findet. Das ist die falsche Fehlergranularität: Fail-fast gehört auf den Start, nicht in jede Aggregatabfrage.

**Empfehlung:** In `loadAll()` je Datei `try/catch` und die Datei überspringen (mit lautem Log und einer Zählung im Ergebnis), damit der Rest weiterläuft; die defekte Datei separat melden.

## ORANGE-4: Fernbuchungen sind nicht idempotent gegenüber einem Retry des Spielers

**Stellen:** `TransferData.java:59-66` (`create`), `PayModule.java:118-130`

```java
UUID id = UUID.randomUUID();
return new TransferData(id, id.toString(), …);
```

Der Idempotenzschlüssel ist die **pro Aufruf frisch erzeugte** tx-id. Die Zusage „derselbe Auftrag bucht nur einmal“ gilt damit nur für exakt dasselbe `TransferData`-Objekt. Genau der Fall, für den Idempotenz gebraucht wird – der Spieler sieht keine Antwort (Timeout, Lag, `socketTimeout` von 30 s), tippt den Befehl erneut – erzeugt einen **neuen** Schlüssel und bucht ein **zweites Mal**.

**Wirkung:** Doppelüberweisung bei instabiler Verbindung; bei `transfer.mode: ANY` ohne jede Rückfrage.

**Empfehlung:** Schlüssel aus stabilen Eingaben ableiten (Absender-UUID, Empfänger-UUID, Betrag und ein vom Aufrufer mitgegebener `--idempotency-key` oder ein kurzes Zeitfenster), sodass ein Wiederholungsversuch innerhallb weniger Sekunden als `DUPLICATE` erkannt wird.

## ORANGE-5: Namensbasierte Suche kann nach einer Umbenennung das falsche Konto treffen

**Stellen:** `MySqlStorage.java:297-313`, `YamlStorage.java:142-152`, `YamlStorage.java:432-436`

`loadByName` wählt in MySQL das Konto mit dem **jüngsten** `updated_at`:

```sql
WHERE LOWER(`name`) = LOWER(?) ORDER BY `updated_at` DESC LIMIT 1
```

Im YAML-Modus gewinnt der **zuletzt geschriebene** Eintrag (`nameIndex`). Wird ein Name freigegeben und von einem anderen Spieler übernommen (oder umgekehrt), zeigt `/eco balance <name>`, `admin balance …` und `admin credit list` auf das Konto des Falschen. AGENT.md verlangt ausdrücklich UUID-basierte Backend-Logik; Namen dürfen nur Ein-/Ausgabe sein – hier ist die **Auflösung** namensbasiert.

**Wirkung:** Falsche Anzeige, und im Adminfall eine Geldbuchung auf das falsche Konto. Kein Exploit ohne Namenswechsel, aber ein Datenintegritätsrisiko.

**Empfehlung:** Offline-Namen über eine `uuid_by_name`-Historie (UUID + Zeitfenster) auflösen und bei Mehrdeutigkeit ablehnen (`player-not-found` bzw. „mehrdeutiger Name“), statt stillschweigend zu raten.

---

# 🟡 GELB

| # | Befund | Stelle |
|---|---|---|
| GELB-1 | **Erfolgsmeldung ohne Schreibvorgang:** `transfer()` verändert beide Konten im Speicher und überspringt die Persistenz, wenn der Executor bereits herunterfährt – `PayModule` meldet trotzdem „gesendet“. | `EconomyService.java:389` |
| GELB-2 | **Kredit ohne Schreibvorgang:** `saveLoanTransaction`, `saveLoanAsync` und `deleteLoanAsync` kehren bei geschlossenem Executor still zurück; `takeLoan()` liefert dennoch `SUCCESS` und der Spieler bekommt Geld gutgeschrieben, das nie gespeichert wird. | `EconomyService.java:734-806` |
| GELB-3 | **Wirkungslose Flags:** `--silent` und `--dry-run` sind im Parser registriert (admin-only) und werden akzeptiert, aber von keinem Modul gelesen. Sie sehen wie Funktionen aus und tun nichts – `--dry-run` suggeriert sogar, eine gefährliche Aktion würde nur simuliert. | `CommandParser.java:217-218` |
| GELB-4 | **`economysystem.reload` allein schaltet `admin.reload` frei** – dasselbe Muster wie ORANGE-1, dort nur geringere Wirkung (Konfiguration neu laden). | `CommandPermissions.java:145-147` |
| GELB-5 | **Rechte-Name inkonsistent:** `LEGACY_TO_MODERN` bildet `economysystem.balance.others` auf `economysystem.command.balance.other` (Singular) ab; README, `Commands.md` und `plugin.yml` sprechen von `…balance.others`. Wer den dokumentierten modernen Knoten vergibt, bewirkt nichts. | `CommandPermissions.java:30` |
| GELB-6 | **Benachrichtigungen prüfen nur den Altknoten:** Beitritts-Guthaben und Zinsgutschrift verlangen `hasPermission("economysystem.balance")` direkt, statt `CommandPermissions.granted(...)`. Wer nur `economysystem.command.balance` hat, sieht sein Guthaben beim Join und seine Zinsen nie. | `PlayerConnectionListener.java:29`, `InterestScheduler.java:119` |
| GELB-7 | **GUI-Items ohne `permission` sind für alle sichtbar und klickbar** (`hasAccess` liefert bei leerem Wert `true`). Rettend: die ausgelösten Befehle prüfen selbst. Für künftige `gui.yml`-Einträge bleibt es eine Falle. | `GuiManager.java:914` |
| GELB-8 | **Pfad-Ausbruch über `storage.yaml.folder`:** der Wert wird ungeprüft an `new File(dataFolder, folder)` gegeben; `"../world"` schreibt Kontodateien außerhalb des Plugin-Ordners. | `Settings.java:88`, `YamlStorage.java:51` |
| GELB-9 | **`table-prefix` darf leer sein** (`{0,40}`): die Tabellen heißen dann `accounts`, `loans`, `transfers` … und kollidieren mit anderen Plugins, die dasselbe tun. Kein Injection-Risiko, aber Datenvermischung. | `MySqlStorage.java:49` |
| GELB-10 | **`max-balance: "-1"` (unbegrenzt) gegen `DECIMAL(30,8)`:** oberhalb ~10²² scheitert der Schreibvorgang mit `SQLException`, während der Speicherwert weiterläuft – Anzeige und Datenbank driften auseinander. | `Currency.java:50`, `MySqlStorage.java:133` |
| GELB-11 | **Jeder `SQLException` killt die Verbindung:** `fail()` schließt die Verbindung und schaltet den Circuit-Breaker (5 s) – auch bei fachlichen Fehlern wie Deadlock, Lock-Wait-Timeout oder doppeltem Schlüssel. Ein einzelner Deadlock pausiert damit den gesamten Speicher. | `MySqlStorage.java:787-796` |
| GELB-12 | **Lease-Vergabe ist nicht atomar:** `acquireLease` schreibt per `INSERT … ON DUPLICATE KEY` und prüft das Ergebnis anschließend mit einem **zweiten** `SELECT`. Zwischen beiden Anweisungen kann ein anderer Server übernehmen; der Kommentar „Die Prüfung passiert in derselben Anweisung“ ist unzutreffend. | `MySqlStorage.java:707-735` |
| GELB-13 | **Zinszyklus wird vor der Buchung beansprucht:** `claimInterestRun()` committet den Zyklusschlüssel, *bevor* die Zinsen geschrieben werden. Ein Absturz dazwischen verliert diesen Zyklus endgültig – er gilt als gebucht und wird nie nachgeholt. | `EconomyService.java:624-640` |
| GELB-14 | **Zeitzonen-Mix:** `cycleKey()` und `initialDelayTicks()` rechnen mit `LocalDateTime.now()` (Serverzeitzone), die JDBC-URL erzwingt `connectionTimeZone=UTC`. Bei Zeitzonenwechsel oder Sommerzeit kann derselbe Slot zwei Schlüssel erzeugen oder einen überspringen. | `EconomyService.java:510-522`, `InterestScheduler.java:128` |
| GELB-15 | **NPE beim Start bei explizit leeren Werten:** `parseLocale` ruft `raw.replace(...)` und `decimal()` ruft `raw.trim()` ohne Nullprüfung auf. Ein `currency.locale:` ohne Wert führt zu einer NullPointerException statt zu einer Warnung mit Rückfallwert – das Plugin deaktiviert sich dann ohne brauchbare Meldung. | `Settings.java:363`, `Settings.java:383` |
| GELB-16 | **Klickbare Hilfe aus ungeprüftem Label:** `ClickEvent.runCommand("/" + label + " help " + page)` verwendet das vom Spieler getippte Befehls-Label ungefiltert (Bukkit reicht es inklusive `namespace:`-Präfix durch). | `HelpBook.java:113-122` |
| GELB-17 | **Formatcode-Injection in Meldungen:** `applyPlaceholders` setzt Werte ein, die aus Spielereingaben stammen (`%input%`, `%player%`, GUI-Suchtext), und rendert anschließend `&`-Codes. Spieler können damit Farben/Formatierung in fremden Chat und in GUI-Titel schmuggeln. | `Messages.java:99-112`, `GuiManager.java:922` |
| GELB-18 | **Testzyklen blockieren den Speicher lange:** bis zu 1000 vollständige `loadAll()`+`saveAll()`-Läufe hintereinander auf dem einzigen Storage-Thread, ohne Abbruchmöglichkeit und ohne Fortschrittsmeldung – während dieser Zeit steht die Wirtschaft still. | `AdminTestInterestCycleModule.java:95-133` |

**Ergänzende Kleinigkeiten (nicht einzeln gezählt):**

- `CommandRegistry.cooldowns` wächst pro `(Spieler, Pfad)` und wird nur beim Treffer bzw. bei `reload` bereinigt (`CommandRegistry.java:517-537`).
- `AdminModule.prepare()` lädt nur bei `args.size() >= 2`; `resolve()` liest ausschließlich den Cache – bei kürzeren Aufrufen meldet der Altpfad „player-not-found“, obwohl das Konto existiert (`AdminModule.java:90-97`, `AbstractModule.java:88`).
- `Currency.parseAmount` interpretiert `"1.000"` je nach Locale als Gruppierung (`de-DE` → 1000) oder Dezimalzahl (`en-US` → 1) – ein Faktor 1000, still und ohne Warnung.
- `pom.xml` bindet `paper-api` an den offenen Bereich `[26.3.build,)`; das JAR ist damit nicht reproduzierbar. Die Actions in `.github/workflows/build.yml` zeigen auf Tags (`@v7`/`@v6`) statt auf Commit-SHAs – die Tags existieren, sind aber veränderbar (Supply-Chain).
- `EconomyService.shutdown()` wartet korrekt mit 60-Sekunden-Frist und `shutdownNow()`-Fallback (Revision-2-Befund NEU-1 ist behoben) – aber `AdminReloadModule` kann `plugin.reload()` mitten in laufende Speichervorgänge legen; `Settings.reload()` ist nicht transaktional, ein Fehler hinterlässt eine halb angewandte Konfiguration.

---

# ✅ Was weiterhin gut gelöst ist

- **Prepared Statements durchgängig**, Tabellenpräfix per Regex validiert → keine SQL-Injection.
- **`BigDecimal` ohne `double`**, `DECIMAL(30,8)`, Nutzereingaben werden nie still gerundet (`Currency.parseAmount` lehnt zu viele Nachkommastellen ab).
- **Kredite sind transaktional:** Auszahlung + Kredit bzw. Abbuchung + Tilgung laufen über `saveTransaction` – MySQL als echte Transaktion mit Rollback, YAML über ein Journal (`pending-transaction.yml`), das beim Start idempotent nachgezogen wird.
- **Kein blockierender Speicherzugriff auf dem Tick-Thread:** `EconomyService.account()` wirft, `accountAsync()`/`findAsync()` laden im Storage-Executor, Module deklarieren ihren Bedarf über `prepare()` statt über eine `instanceof`-Kette.
- **Prüfreihenfolge an genau einer Stelle** (`CommandRegistry.dispatch`), `READ_ONLY` als Positivliste vor der Fachlogik, Tab-Vervollständigung nutzt dieselbe Sichtbarkeitsfunktion wie die Ausführung.
- **Befehle prüfen Permissions selbst**, GUI führt sie als Spieler aus → Fehlkonfigurationen in `gui.yml` eskalieren nicht.
- **GUI-Klicks** ins Plugin-Inventar werden abgebrochen, Drag nur bei Beteiligung des oberen Inventars gesperrt, Amboss-Items werden vor dem Schließen geleert (kein Item-Dupe).
- **YAML-Schreibvorgänge atomar** (Temp-Datei + `ATOMIC_MOVE`, mit Fallback), Konfigurations-Updates ebenso.
- **Shutdown mit Frist** (60 s, dann `shutdownNow()` mit lautem Verlust-Log) – der Befund NEU-1 aus Revision 2 ist behoben.
- **`allowPublicKeyRetrieval`** ist an den TLS-Modus gekoppelt, `ssl-mode` ersetzt den Boolean – NEU-2 ist behoben.
- **Keine verwaisten Message-Keys:** der automatische Abgleich aller 80 im Code verwendeten Schlüssel gegen `messages.yml` ergab **keine** fehlenden Einträge.
- **Kompiliert fehlerfrei** (siehe Prüfumfang).

---

# 🎯 Priorisierte Empfehlungen

1. **Kurzfristig – ORANGE-1:** `economysystem.tests` aus `candidates()` nehmen.
2. **Kurzfristig – ORANGE-2:** `auditActions()` füllen, `--reason` durchreichen, alle schreibenden Pfade auditieren.
3. **Kurzfristig – ORANGE-3:** `loadAll()` fehlertolerant machen (eine defekte Datei darf nicht alles stoppen).
4. **Mittelfristig – ORANGE-4/5:** stabilen Idempotenzschlüssel für Fernbuchungen; Offline-Namen über eine UUID-Historie auflösen.
5. **Laufend – GELB:** GELB-1/2 (stille Schreibverluste), GELB-3 (wirkungslose Flags), GELB-5/6 (Rechte-Inkonsistenzen), GELB-8/9/10 (Pfad und Grenzwerte), GELB-11/12/13 (Storage-Härtung), GELB-15 (Startrobustheit), GELB-18 (Testzyklen begrenzen).

---

# 🧪 Prüfumfang dieser Revision

| Prüfung | Ergebnis |
|---|---|
| **Kompilierung** (`mvn -o compile`, Paper-API `26.3-pre-2.build.0-alpha` aus `target/m2`) | ✅ **72/72 Dateien, 0 Fehler, 0 Warnungen** (Exit 0) |
| Message-Key-Abgleich (Code ↔ `messages.yml`, 81 Keys) | ✅ keine fehlenden Schlüssel |
| Suchläufe nach gefährlichen Mustern (`Runtime.exec`, `ProcessBuilder`, `ObjectInputStream`, `setAccessible`, `dispatchCommand`, `eval`) | ✅ keine Treffer außer `Class.forName` für den MySQL-Treiber |
| Suchläufe nach toten/ungelesenen Konfigurationswerten (`network.limits`, `--reason`, `--dry-run`, `--silent`, `cooldownMillis`) | ⚠️ `network.limits` wird seit Revision 6 im Router erzwungen; offen: `--reason`, `--dry-run`, `--silent` → ORANGE-2, GELB-3 |
| Code-Review aller 72 Hauptdateien | ✅ vollständig (Router, Module, Speicher, Geld-/Kreditlogik, GUI, Konfiguration, Placeholder, Listener) |
| `mvn verify` | ⚠️ **nicht als `verify` gelaufen** – Maven ist nicht global installiert und es gibt keinen Wrapper (`mvnw`); gebaut wurde mit dem gebündelten Maven aus `target/tools` gegen das lokale Repo `target/m2` |
| Unit-Tests (`src/test`, 5 Testklassen) | ✅ **24/24 grün** (`mvn -o test`, 0 Fehler, 0 übersprungen) |
| Laufzeittest gegen einen Paper-Server | ⚠️ **nicht durchgeführt** – keine Aussage über tatsächliches Tick-Verhalten, GUI-Klicks oder MySQL-Verhalten |
| MySQL gegen eine echte Instanz | ⚠️ **nur statisch bewertet** – Transaktionen, Lease und Breaker nicht reproduziert |

**Grenzen dieser Revision:** Alle Aussagen sind aus dem Code abgeleitet und statisch geprüft; ausgeführt wurden nur Kompilierung und Unit-Tests (24/24 grün). Die Befunde zu ORANGE-4 (Doppelbuchung) und GELB-11/12 (Deadlock, Lease-Race) sind **nicht** im laufenden Betrieb reproduziert; sie folgen aus der Kontrollfluss- und Transaktionsanalyse. Der ROT-2-Fix ist **nicht unter Last gemessen**, und die neue `ORDER BY … LIMIT`-Abfrage wurde **nicht gegen eine echte MySQL-Instanz** geprüft (keine Laufzeitumgebung in dieser Prüfung). Die Rate-Limit-Durchsetzung aus Revision 6 ist durch Codeanalyse, Kompilierung und Unit-Tests belegt, aber **nicht unter Last** gemessen – das gleitende Fenster lebt im Speicher des Haupt-Threads und wird beim Neustart zurückgesetzt (dokumentiertes Verhalten, kein Persistenzanspruch). Die GUI-Eingabeprüfung aus Revision 7 ist ebenfalls nur statisch und per Unit-Test-Lauf belegt: Amboss- und Regler-Bedienung wurden **nicht** auf einem laufenden Paper-Server durchgeklickt, und ein `pattern` in `gui.yml` ist nur so streng wie die Konfiguration.

---

# 📌 Änderungsprotokoll

- **Revision 1:** Erstaudit der Einzelbefehl-Struktur (2 ROT, 6 ORANGE, 17 GELB).
- **Revision 2:** Fixstand der Einzelbefehl-Struktur (14 behoben, 3 teilweise, 8 offen, 7 neu).
- **Revision 3:** **Vollständige Neufassung** für die umgebaute Struktur (Modul-Befehlsbaum, Netzwerk-/Audit-Schicht). Revision 2 ist inhaltlich überholt, weil die dort geprüften Klassen (`AbstractEconomyCommand`, `MoneyCommand`, `PayCommand`, `EconomySystemAdminCommand`, `KreditCommand`, `EconomySystemCommand`, `EconomySystemGuiCommand`) entfernt wurden. Neu bewertet: 0 EXTREM, 2 ROT, 7 ORANGE, 18 GELB. Bestätigt behoben aus Revision 2: NEU-1 (Shutdown-Frist), NEU-2 (`ssl-mode`/`allowPublicKeyRetrieval`), NEU-5 (`accountAsync` mit Namen), NEU-6 (Klickfilter pro Slot), NEU-7 (Circuit-Breaker-Erstprüfung), ROT-2 (Kredit-Transaktionen inkl. YAML-Journal).
- **Revision 4:** **ROT-1 behoben und aus dem Dokument entfernt.** Bestätigung ist jetzt an die Wirkung gebunden (`apply(..., confirmed)`), die Altform `/economysystem-admin money …` sowie die GUI-Wege (`money-set`, `money-reset`) werden über den kanonischen Pfad `admin balance set|reset …` geroutet, `Origin.GUI` ist echt (`dispatchGui`) und `--confirm` aus der GUI wird abgelehnt; die GUI zeigt dafür einen Bestätigungs-Dialog. Übersicht angepasst: 0 EXTREM, 1 ROT, 7 ORANGE, 18 GELB (Summe 26).
- **Revision 5:** **ROT-2 behoben und aus dem Dokument entfernt.** `loadTop(limit)` im `EconomyStorage`-Interface (MySQL: `ORDER BY balance DESC LIMIT`, YAML: In-Memory-Index für Name und Guthaben statt Datei-Parsing bei jedem Aufruf), `EconomyService.topAsync()` liest nur noch die begrenzte Rangliste und führt sie mit dem Cache der Online-Spieler zusammen, `loadAll()` bleibt Zinszyklus und Spielerauswahl vorbehalten; Cooldowns von 2 s für `balance.top` und `gui` über den Router. Übersicht angepasst: 0 EXTREM, 0 ROT, 7 ORANGE, 18 GELB (Summe 25). Restrisiko: Die Spielerauswahl im GUI (`accountsAsync()`) braucht den vollständigen Bestand und bleibt ein Volltabellen-`SELECT` (YAML: Index ohne Datei-Parsing); gedrosselt auf 2 s, Paging wäre der nächste Schritt.
- **Revision 6:** **ORANGE-2 behoben und aus dem Dokument entfernt.** Die konfigurierten `network.limits` greifen jetzt im Router (Stufe 7, `CommandRegistry.throttleAllowed`): gleitendes Fenster je Spieler und Pfadgruppe (`pay`, `credit.take`, `admin.balance.*` teilen sich je ein Fenster) über `count`/`seconds`, zusätzlich der Mindestabstand `cooldown-seconds`; `count: 0` bzw. `cooldown-seconds: 0` hebt den jeweiligen Teil auf, `economysystem.bypass.rate-limit` und `economysystem.bypass.cooldown` nehmen Administratoren aus. Eine Ablehnung verbraucht kein Kontingent und meldet `command._default.error.rate-limited` mit der Restzeit. Befunde ORANGE-3 bis ORANGE-7 sind auf ORANGE-2 bis ORANGE-6 nachgerückt; Übersicht angepasst: 0 EXTREM, 0 ROT, 6 ORANGE, 18 GELB (Summe 24).
- **Revision 7:** **ORANGE-1 behoben und aus dem Dokument entfernt.** Jede Antwort einer Inventar-Eingabe wird vor dem Einsetzen in die Befehlszeile geprüft (`GuiManager.answersAllowed`): keine Leerzeichen oder Steuerzeichen, kein führendes `--`, höchstens 64 Zeichen, `number` muss dem Zahlenformat und `min`–`max` entsprechen, `player` einem Spielernamen, `text` zusätzlich dem neuen optionalen `pattern` aus der `gui.yml` (die ausgelieferten Zinsfelder erlauben nur Ziffern mit optionalem Dezimalteil). Ungültige Antworten werden mit `gui-input-invalid` abgelehnt und das Menü erneut geöffnet, statt den Befehl auszuführen; die Typprüfung sitzt vor der Substitution, damit `{input}`, `{input1}` usw. nie ungeprüften Text erhalten. Befunde ORANGE-2 bis ORANGE-6 sind auf ORANGE-1 bis ORANGE-5 nachgerückt; Übersicht angepasst: 0 EXTREM, 0 ROT, 5 ORANGE, 18 GELB (Summe 23).
