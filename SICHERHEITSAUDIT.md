# 🔍 Sicherheits- & Bug-Audit – EconomySystem

**Revision:** 2
**Datum:** 18.11.2026
**Prüfstand:** Arbeitskopie über Commit `2533d50` (15 geänderte Dateien, noch nicht committet)
**Prüfumfang:** Gesamtes Projekt (`src/main/java`, `src/main/resources`, `pom.xml`, `.github`, `.gitignore`)
**Methode:** Vollständige Diff-Review aller 15 geänderten Dateien, erneute Code-Review der betroffenen Klassen (42 Java-Dateien), gezielte Suchläufe nach gefährlichen API-Mustern (`Runtime.exec`, `ObjectInputStream`, SQL-Konkatenation, `dispatchCommand`, Berechtigungsprüfungen) sowie Kompilierlauf mit `javac` gegen Paper-API `26.3.build.249-stable`.

---

## 📊 Übersicht

### Status der Befunde aus Revision 1

| Schweregrad | Ursprünglich | ✅ behoben | 🟠 teilweise | ❌ offen |
|---|---|---|---|---|
| 🔴 **EXTREM** | 0 | – | – | – |
| 🔴 **ROT** | 2 | 1 | **1** | 0 |
| 🟠 **ORANGE** | 6 | 5 | 1 | 0 |
| 🟡 **GELB** | 17 | 8 | 1 | **8** |
| **Summe** | **25** | **14** | **3** | **8** |

### Neu in Revision 2 entstanden

| Schweregrad | Anzahl | Kern |
|---|---|---|
| 🟠 **ORANGE** | 2 | Endlos wartender Shutdown; MySQL-8-Login bricht bei `use-ssl: false` |
| 🟡 **GELB** | 5 | Befehls-Preload als `instanceof`-Kette, Placeholder leer beim Erstaufruf, Kredit lädt Konto als `unknown`, globaler 300-ms-Klickfilter, Circuit-Breaker-Erstprüfung |

> **Fazit:** Die wichtigsten Spieler-Angriffsflächen sind geschlossen. `ROT-1` ist im Spielbetrieb behoben, `ROT-2` nur zur Hälfte (Überweisungen ja, Kredite und YAML nein). Die neuen Befunde sind **Nachwirkungen der Fixes**, nicht neue Angriffsvektoren – mit einer Ausnahme: der unbegrenzt wartende Shutdown kann einen Server-Stopp blockieren.

---

# ✅ Was in dieser Revision behoben wurde

## ROT-1: Blockierende Speicherzugriffe auf dem Haupt-Thread – ✅ behoben

**Umsetzung:**
- `EconomyService.find()` (`EconomyService.java:144`) liest **nur noch** den Cache; kein `storage.loadByName()` mehr auf dem Haupt-Thread.
- `EconomyService.account()` (`EconomyService.java:77`) wirft auf dem Primär-Thread eine `StorageException`, statt blockierend nachzuladen – Fail-Fast statt Freeze.
- Neu: `accountAsync()` (`EconomyService.java:110`) und `findAsync()` (`EconomyService.java:124`) laden ausschließlich im Storage-Executor.
- `AbstractEconomyCommand.onCommand()` (`AbstractEconomyCommand.java:43-83`) lädt Spieler- und Zielkonto asynchron vor und führt den Befehl erst im nächsten Tick auf dem Haupt-Thread aus.
- `PlaceholderData.balance()` (`PlaceholderData.java:121-124`) blockiert nie mehr, sondern aktualisiert asynchron (mit `pending`-Set gegen Fluten).
- `CreditService.processDueLoans()` (`CreditService.java:318-335`) lädt den Schuldner asynchron und dedupliziert parallele Läufe über `pendingCollections` (`CreditService.java:70`).
- `MySqlStorage` hat einen Circuit-Breaker: nach einem Fehler wird 5 s lang **nicht** neu verbunden (`MySqlStorage.java:147-160`, `368-370`).

**Ergebnis:** `/pay`, `/money`, `/kredit` und Platzhalter können keinen Datenbank-Timeout mehr auf dem Tick-Thread auslösen. Ein hängender MySQL-Server führt zu verzögerten Antworten, nicht zum Server-Freeze.

**Rest:** `storage.init()` und `CreditService.init()` blockieren weiterhin beim Start (vertretbar), `isValid(3)` kostet pro Aufruf bis zu 3 s (jetzt aber off-thread).

## ROT-2: Geldtransaktionen nicht atomar – 🟠 teilweise behoben

**Behoben:**
- `transfer()` schreibt Quelle und Ziel jetzt **gemeinsam** über `saveAll()` (`EconomyService.java:343`). Im MySQL-Modus ist das eine echte Transaktion (`MySqlStorage.saveAll(), MySqlStorage.java:239-279`) inkl. Rollback.
- `shutdownNow()` wurde entfernt (`EconomyService.java:651`); es wird gewartet, bis alle Speichervorgänge durch sind.

**Noch offen:**
- **Kredite:** `takeLoan()` (Einzahlung → `saveAsync`, danach `saveLoanAsync`) und `payLoans()`/`collect()` (Abbuchung → `saveAsync`, danach Kredit speichern) bleiben zwei unabhängige Schreibvorgänge. Ein Absturz dazwischen erzeugt weiterhin Gratis-Geld bzw. Doppelbelastung.
- **YAML:** `YamlStorage.saveAll()` (`YamlStorage.java:171-175`) ist nur eine Schleife über `save()`. Jede Datei ist atomar, die **Kombination** beider Konten nicht. Der Kommentar „MySQL: eine Datenbanktransaktion“ stimmt, suggeriert aber eine Stärke, die YAML nicht hat.

**Empfehlung:** Konto- und Kreditänderung in **einem** Storage-Aufruf bündeln (z. B. `saveTransfer(from, to, loan)`), damit MySQL eine Transaktion und YAML ein Journal schreiben kann.

## ORANGE-1: Unsichere MySQL-Standardkonfiguration – ✅ behoben

- Neuer Standardnutzer `economysystem` statt `root` (`Settings.java:91`, `settings.yml`).
- `use-ssl: true` als Standard (`Settings.java:94`), `sslMode=VERIFY_IDENTITY` (`MySqlStorage.java:95`), `allowPublicKeyRetrieval=false` (`MySqlStorage.java:96`), `autoReconnect` entfernt.
- `socket-timeout-ms` ist jetzt konfigurierbar (`Settings.java:393`, `MySqlStorage.java:100`).
- README-Abschnitt „MySQL-Sicherheit“ dokumentiert Nutzerrechte, Zertifikatsanforderungen, Klartext-Passwort und dass bestehende Konfigurationen **nicht** überschrieben werden.

⚠️ Die Härtung erzeugt einen neuen Konfigurations-Fallstrick → siehe **NEU-2**.

## ORANGE-2: Zahlungen namensbasiert – ✅ behoben (Zahlungen)

- `PayCommand` löst den Empfänger nur noch über **online** befindliche Spieler und deren **UUID** auf (`PayCommand.java:42-48`); offline → neue Meldung `pay-recipient-offline`.
- README und `messages.yml` wurden konsistent nachgezogen.
- **Rest:** `/money <name>` und die Admin-Geldbefehle lesen weiterhin über den Namen (`MySqlStorage.loadByName()` mit `ORDER BY updated_at DESC`). Für reine Anzeige/Admin unkritisch, nach einem Namenswechsel aber potenziell das falsche Konto.

## ORANGE-3: Getilgte Kredite blockieren Kreditplätze – ✅ behoben

`CreditService.init()` (`CreditService.java:80-83`) erkennt `loan.isPaid()` und löscht die Zeile asynchron, statt sie zu laden.

## ORANGE-4: `tests` erlaubt Geldschöpfung – 🟠 teilweise behoben

- ✅ `tests()` prüft jetzt `interest.enabled` vor dem Start (`EconomySystemAdminCommand.java:287-290`), neue Meldung `tests-interest-disabled`; README entsprechend korrigiert.
- ❌ Die Testbefehle sind weiterhin in **jedem** Build enthalten; es gibt kein Maven-Profil, das sie aus Produktionsartefakten entfernt. `tests.enabled: true` bleibt eine bewusst zu setzende, aber leicht zu vergessende Freigabe.

## ORANGE-5: `rounding: UNNECESSARY` zerstört Zinsen – ✅ behoben

`Settings.parseRounding()` (`Settings.java:190-199`) fängt `UNNECESSARY` ab, protokolliert eine Warnung und fällt auf `HALF_UP` zurück. `UNNECESSARY` wurde aus der Optionsliste in `settings.yml` entfernt.

## ORANGE-6: Datenverlust beim Herunterfahren – ✅ behoben

`EconomyService.shutdown()` (`EconomyService.java:646-657`) verwirft keine eingereihten Vorgänge mehr; es wird gewartet und protokolliert, bis der Executor leer ist. ⚠️ Dadurch entsteht ein neues Risiko → siehe **NEU-1**.

## GELB – Status im Einzelnen

| # | Status | Kurzbegründung |
|---|---|---|
| GELB-1 | ❌ offen | `accounts`-Cache wird weiterhin nie bereinigt (`EconomyService.java:36`). |
| GELB-2 | ✅ behoben | `preload()` nutzt `accountAsync()`; `RejectedExecutionException` wird abgefangen (`EconomyService.java:353-361`). |
| GELB-3 | ✅ behoben | Nur noch Klicks ins **obere** Inventar werden abgebrochen; im eigenen Inventar ist wieder alles erlaubt (`GuiManager.java:779-791`), Shift-/Doppelklicks bleiben gesperrt. |
| GELB-4 | ✅ behoben | `holder.handled()` + 300-ms-Filter verhindern Doppelausführung (`GuiManager.java:213-236`). ⚠️ Nebenwirkung → **NEU-6**. |
| GELB-5 | ❌ offen | `{input}`/`{inputN}` werden weiterhin ungeprüft in Befehle eingesetzt (`GuiManager.substitute()`). |
| GELB-6 | ❌ offen | GUI-Items ohne `permission` bleiben für alle sichtbar/klickbar (`GuiManager.hasAccess(), GuiManager.java:903`). Rettend: Befehle prüfen selbst. |
| GELB-7 | ✅ behoben | Kontoanlage läuft jetzt ausschließlich im Single-Thread-Storage-Executor, damit serialisiert zum Zinszyklus. |
| GELB-8 | ❌ offen | `capacity` wird weiterhin vor `loan.pay()` berechnet (`CreditService.java:246`, `283`). |
| GELB-9 | 🟠 teilweise | `autoReconnect` entfernt, `socketTimeout` konfigurierbar, Circuit-Breaker vorhanden. Offen: **eine** Verbindung, alles `synchronized` (`MySqlStorage.java:35`). |
| GELB-10 | ❌ offen | `paper-api` weiterhin mit offenem Bereich `[26.3.build,)` in `pom.xml` → nicht reproduzierbare Builds. |
| GELB-11 | ✅ behoben | Nur `plugin.yml` wird gefiltert, alle anderen Ressourcen nicht mehr (`pom.xml`, `build/resources`). |
| GELB-12 | ✅ behoben | `ConfigUpdater` und `Settings.writeFactor` schreiben in eine Temp-Datei und ersetzen atomar (`ConfigUpdater.java:77-90`, `Settings.java:159-172`), mit Fallback ohne `ATOMIC_MOVE`. |
| GELB-13 | ✅ behoben | `reload` ist von `try/catch (RuntimeException)` umschlossen (`EconomySystemAdminCommand.java:100-107`). |
| GELB-14 | ✅ behoben | `/money help` prüft `economysystem.balance` und erscheint nur noch berechtigt in der Tab-Vervollständigung (`MoneyCommand.java:38-45`, `95-98`). |
| GELB-15 | ❌ offen | `table-prefix` erlaubt weiterhin den leeren String (`MySqlStorage.java:44`); `storage.yaml.folder` akzeptiert `../`. |
| GELB-16 | ❌ offen | `max-balance: "-1"` bleibt unbegrenzt, während die Spalte `DECIMAL(30,8)` ist. |
| GELB-17 | ❌ offen | Actions weiterhin auf Tags (`@v7`/`@v6`), keine statische Analyse, kein `src/test`. |

---

# 🆕 Neu entstanden (Revision 2)

## NEU-1 🟠 Shutdown kann unbegrenzt hängen

**Stelle:** `EconomyService.java:646-657`

```java
try {
    while (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        plugin.getLogger().warning("Speichervorgänge laufen noch; der Server wartet auf ihren Abschluss.");
    }
}
```

**Problem:** Der Fix gegen Datenverlust hat die Obergrenze ersatzlos gestrichen. Ist MySQL nicht erreichbar, blockiert jeder eingereihte Speichervorgang bis zu `connect-timeout` (5 s) bzw. `socket-timeout` (30 s). Bei vielen vorgemerkten Konten kann `onDisable()` damit **beliebig lange** auf dem Haupt-Thread hängen: Server-Stopp, `/reload` und Plugin-Deaktivierung kommen nicht mehr zurück. Es gibt keinen Watchdog, der das während des Herunterfahrens abfängt.

**Empfehlung:** Gesamtfrist setzen (z. B. 60 s), danach `shutdownNow()` und die Anzahl verworfener Vorgänge **laut** protokollieren:

```java
long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
while (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
    if (System.nanoTime() > deadline) {
        List<Runnable> dropped = executor.shutdownNow();
        plugin.getLogger().severe("Speicherung beim Stoppen abgebrochen, "
                + dropped.size() + " Vorgänge verworfen.");
        break;
    }
}
```

## NEU-2 🟠 `use-ssl: false` ist mit MySQL 8 nicht mehr nutzbar

**Stellen:** `MySqlStorage.java:95-96`

```java
+ "?sslMode=" + (config.useSsl() ? "VERIFY_IDENTITY" : "DISABLED")
+ "&allowPublicKeyRetrieval=false"
```

**Problem:** `allowPublicKeyRetrieval` ist jetzt **hart** auf `false` gesetzt. MySQL 8 verwendet standardmäßig `caching_sha2_password`; ohne TLS muss der Treiber für die vollständige Authentifizierung den öffentlichen Schlüssel des Servers abrufen dürfen. Mit `sslMode=DISABLED` + `allowPublicKeyRetrieval=false` scheitert die Anmeldung mit `Public Key Retrieval is not allowed` – die vom Plugin weiterhin angebotene Option `use-ssl: false` ist damit praktisch funktionslos.

Zweite Nebenwirkung: Frische Installationen erhalten `use-ssl: true` + `VERIFY_IDENTITY`. Bei selbstsignierten Zertifikaten oder einem Zertifikatsnamen, der nicht zum konfigurierten Host passt, startet das Plugin nicht mehr (`init()` schlägt fehl → Plugin deaktiviert sich). Die README dokumentiert das, der Fehler bleibt für Anwender aber schwer zuzuordnen.

**Empfehlung:** `allowPublicKeyRetrieval` nur bei aktivem TLS auf `false` setzen **oder** als eigenen Schlüssel (`allow-public-key-retrieval`) konfigurierbar machen; zusätzlich `ssl-mode` (`VERIFY_IDENTITY` / `REQUIRED` / `DISABLED`) statt eines Booleans anbieten und die Startfehlermeldung um einen Hinweis auf Zertifikat/Hostname ergänzen.

## NEU-3 🟡 Befehls-Preload hängt an einer `instanceof`-Kette

**Stelle:** `AbstractEconomyCommand.java:43-70`

**Problem:** Ob ein Konto vorab geladen wird, entscheidet eine fest verdrahtete Liste (`MoneyCommand`, `PayCommand`, `KreditCommand`, `EconomySystemAdminCommand` + Argumentmuster). Gleichzeitig wirft `EconomyService.account()` auf dem Haupt-Thread jetzt eine `StorageException` (`EconomyService.java:77`). Jeder künftige Befehl, jede neue Argumentform oder jeder zusätzliche Aufrufpfad, der nicht in dieser Kette steht, endet damit nicht mehr in einem blockierenden Ladevorgang, sondern in einer **generischen „storage-error“-Meldung** – obwohl der Speicher in Ordnung ist. Zusätzlich wird die Bukkit-Befehlsausführung pauschal um mindestens einen Tick verzögert, und der Rückgabewert von `execute()` geht verloren.

**Empfehlung:** Preload in die Befehle selbst verlagern (überschreibbarer Hook `CompletableFuture<?> prepare(sender, args)`, Standard: fertig) statt `instanceof`-Prüfungen in der Basisklasse. So bleibt die Basisklasse stabil und neue Befehle können nicht vergessen werden.

## NEU-4 🟡 Platzhalter liefern beim ersten Abruf `null`

**Stelle:** `PlaceholderData.java:121-124`

**Problem:** Für Offline-Konten wird der Wert beim ersten Aufruf nur angestoßen und `null` zurückgegeben – auch auf dem Haupt-Thread. `%money_<offline>%` bzw. `%economysystem_money_<offline>%` sind damit nach jedem Neustart bzw. nach Ablauf der 30-s-TTL mindestens einen Refresh-Zyklus lang **leer**. In einmaligen Kontexten (Chat-Nachricht, Schild, `/papi parse`) bleibt der Platzhalter dauerhaft leer, weil kein zweiter Abruf erfolgt. Das ist der bewusste Preis für ROT-1, sollte aber dokumentiert und nach Möglichkeit abgefedert werden.

**Empfehlung:** Stale-while-revalidate (letzten bekannten Wert zurückgeben, im Hintergrund erneuern) oder Offline-Namen beim Start vorwärmen; mindestens einen Hinweis in der README.

## NEU-5 🟡 Fälliger Kredit legt Konto als `unknown` an

**Stelle:** `CreditService.java:318`

```java
economy.accountAsync(loan.owner(), null).whenComplete(...)
```

**Problem:** Vorher wurde `loan.ownerName()` übergeben. Fehlt der Kontodatensatz (z. B. nach einem Absturz zwischen Konto- und Kredit-Schreibvorgang – genau das Szenario aus ROT-2), legt `loadAccount()` nun ein Konto mit `null` an; der `Account`-Konstruktor ersetzt das durch `"unknown"`, und dieser Name wird sofort gespeichert. Ergebnis: ein Phantomkonto mit Startguthaben unter `unknown`, das erst beim nächsten Login korrigiert wird – und `%money_unknown%` ist abfragbar.

**Empfehlung:** `economy.accountAsync(loan.owner(), loan.ownerName())` verwenden (ein Einzeiler).

## NEU-6 🟡 Globaler 300-ms-Klickfilter verschluckt Klicks

**Stelle:** `GuiManager.java:216-222`

**Problem:** `lastCommandClick` ist **pro Spieler**, nicht pro Item/Slot. Wer zwei verschiedene Menüpunkte innerhalb von 300 ms anklickt (z. B. erst „Überweisen“, dann sofort „Kredite“), verliert den zweiten Klick lautlos – ohne Rückmeldung. Der `handled`-Flag allein würde Doppelklicks im selben Tick bereits ausreichend abfangen.

**Empfehlung:** Filter auf `(Spieler, Slot)` schlüsseln oder ganz auf `holder.handled()` verlassen und die Zeitdrossel entfernen.

## NEU-7 🟡 Circuit-Breaker sperrt bei negativem `nanoTime()` dauerhaft

**Stelle:** `MySqlStorage.java:147`

```java
if (connection == null && System.nanoTime() - retryAfterNanos < 0) { ... }
```

**Problem:** `retryAfterNanos` startet mit `0`. `System.nanoTime()` hat laut Spezifikation einen beliebigen Ursprung und darf negativ sein. Ist er beim ersten Aufruf negativ, greift die Sperre sofort, `retryAfterNanos` bleibt `0`, und die Verbindung wird **nie** aufgebaut – der Zustand ist nicht selbstheilend. Praktisch selten (HotSpot/Windows liefert positive Werte), aber ein Ein-Zeilen-Fix.

**Empfehlung:**

```java
if (connection == null && retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) { ... }
```

---

# ✅ Was weiterhin gut gelöst ist

- **Prepared Statements durchgängig**, Tabellenpräfix per Regex validiert (`MySqlStorage.java:44`) → keine SQL-Injection.
- **`BigDecimal` statt `double`** überall, `DECIMAL(30,8)` in der DB → keine Gleitkomma-Fehler.
- **Konten synchronisiert** (`Account`, `Loan`) und alle Schreibvorgänge auf **einem** Storage-Executor serialisiert → keine Split-Brain-Konten; behebt zusätzlich die Join-/Zinszyklus-Race (GELB-7).
- **YAML-Schreibvorgänge atomar** (Temp-Datei + `ATOMIC_MOVE`, `YamlStorage.java:150-165`), jetzt auch für Konfigurationen.
- **`saveAll()` mit echter Transaktion** inkl. Rollback (`MySqlStorage.java:239-279`) – jetzt auch für `/pay` genutzt.
- **GUI-Klicks ins Plugin-Inventar abgefangen**, Drag nur bei Beteiligung des oberen Inventars gesperrt (`GuiManager.java:775-830`) → kein Item-Duplizieren, aber eigenes Inventar bleibt bedienbar.
- **Befehle prüfen Permissions selbst**, GUI führt Befehle als Spieler aus → Fehlkonfigurationen der `gui.yml` eskalieren nicht.
- **Betrags-Parsing lehnt negative Werte und zu viele Nachkommastellen ab** (`Currency.java:78-100`) → kein Minus-Betrag-Exploit.
- **Fail-Fast statt Freeze:** `account()` auf dem Haupt-Thread zu werfen ist die richtige Richtung – der Aufrufpfad muss nur sauber gekapselt sein (siehe NEU-3).
- **Dokumentation im Gleichschritt:** README, `settings.yml` und `messages.yml` wurden mit den Fixes aktualisiert (Online-Zwang bei `/pay`, `UNNECESSARY` entfernt, `interest.enabled` für Tests, MySQL-Sicherheitsabschnitt).

---

# 🎯 Priorisierte Empfehlungen

1. **Sofort:** Shutdown-Frist mit `shutdownNow()`-Fallback und lautem Verlust-Log (NEU-1) – der einzige neue Befund, der den Server blockieren kann.
2. **Sofort:** `allowPublicKeyRetrieval` an TLS koppeln bzw. konfigurierbar machen und `ssl-mode` statt Boolean anbieten (NEU-2).
3. **Kurzfristig:** Kreditaufnahme/-tilgung transaktional bündeln und YAML-Journal nachziehen (ROT-2-Rest) – Kerninvariante eines Economy-Plugins.
4. **Kurzfristig:** `accountAsync(loan.owner(), loan.ownerName())` korrigieren (NEU-5), Circuit-Breaker-Erstprüfung härten (NEU-7), Klickfilter auf Slots umstellen (NEU-6).
5. **Mittelfristig:** Preload-Hook in den Befehlen statt `instanceof`-Kette (NEU-3); Platzhalter-Stale-Value (NEU-4); `tests` per Maven-Profil aus Produktions-Builds entfernen (ORANGE-4-Rest).
6. **Laufend:** Offene GELB-Punkte 1, 5, 6, 8, 10, 15, 16, 17 abarbeiten – insbesondere Paper-Version festnageln (GELB-10), `table-prefix`-Mindestlänge (GELB-15), Obergrenze bei `max-balance: "-1"` (GELB-16) und Actions auf Commit-SHAs pinnen (GELB-17).

---

# 🧪 Prüfumfang dieser Revision

| Prüfung | Ergebnis |
|---|---|
| Kompilierung (`javac`, Paper-API `26.3.build.249-stable`) | ✅ erfolgreich, keine Fehler/Warnungen |
| `mvn verify` | ⚠️ nicht ausführbar – Maven ist auf dem Prüfsystem nicht installiert (kein Wrapper vorhanden) |
| Unit-Tests | ⚠️ nicht vorhanden (`src/test` fehlt) |
| Laufzeittest gegen Paper-Server | ⚠️ nicht durchgeführt |
| MySQL-Verhalten (`use-ssl`, Auth-Plugin) | ⚠️ nur statisch bewertet, nicht gegen eine echte MySQL-8-Instanz getestet |

Die Aussagen zu NEU-1, NEU-2 und NEU-5 sind aus dem Code abgeleitete Risiken; sie wurden **nicht** im laufenden Betrieb reproduziert.

---

# 📌 Änderungsprotokoll (Revision 1 → Revision 2)

Geprüfte Arbeitskopie-Änderungen: `README.md`, `pom.xml`, `AbstractEconomyCommand`, `EconomySystemAdminCommand`, `MoneyCommand`, `PayCommand`, `ConfigUpdater`, `Settings`, `CreditService`, `EconomyService`, `GuiManager`, `PlaceholderData`, `MySqlStorage`, `messages.yml`, `settings.yml`.

- **Revision 1:** Erstaudit, 2 ROT, 6 ORANGE, 17 GELB.
- **Revision 2:** Fixstand bewertet – 14 behoben, 3 teilweise, 8 offen; 7 neue Befunde dokumentiert (2 ORANGE, 5 GELB).
