# FoldMail – stav projektu

Prémiový Android email klient (Kotlin, Compose, Material 3) pre Galaxy Z Fold 7.

## Skutočný stav (bez prikrášlenia)

Prostredie, v ktorom vznikol modul `app`, malo sieť povolenú len pre apt/npm/PyPI a GitHub.
Hosty `dl.google.com`, Google Maven, Maven Central, Gradle Plugin Portal a `services.gradle.org`
boli blokované (HTTP 403 z proxy). Preto:

- **Android SDK sa nedal nainštalovať**, `./gradlew` sa nedal spustiť (nemá odkiaľ stiahnuť Gradle ani pluginy).
- **`assembleDebug` sa nespustil, APK nevzniklo.**
- **Overené (mimo Gradle):** `core-domain` (17 testov) a čistá Kotlin vrstva `app`
  (`MailRepository`, `DemoData`, `Presentation`, 5 testov v `AppLogicTest`) som skompiloval
  kompilátorom Kotlin 2.0.21 z distribúcie Gradle a spustil v JUnit 4.13.2: všetko prešlo.
- **Neoverené kompiláciou:** Compose UI v `app/.../ui`, `MainActivity`, `MailViewModel`,
  `core-designsystem` a všetky `build.gradle.kts`. UI súbory prešli len kontrolou syntaxe.
- Verzie v `gradle/libs.versions.toml` sú stále odhady bez prístupu na sieť. Pred prvým buildom ich
  zvýšte na aktuálne stabilné (a AGP musí sedieť s Gradle 8.14.3 z wrappera).

## Hotové

| Modul | Obsah |
|---|---|
| `core-domain` | `PersonConversations` (chat na osobu / skupinu), `ThreadGrouper` (vlákna podľa hlavičiek), `QuoteSplitter` (zbalenie citácií) + 17 testov |
| `core-designsystem` | Svetlá/tmavá paleta, zaoblené tvary, `FoldMailTheme` |
| `app` | Adaptívny shell podľa šírky okna (prah 600 dp): **kompaktný** = spodná lišta *Chaty · Kontakty · Nový (zvýraznený) · Kalendár*, jeden panel naraz; **rozšírený** (rozložený Fold) = `NavigationRail` + zoznam chatov + otvorený chat vedľa seba. Zoznam chatov, chat s bublinami, predelmi predmetu a zbalenými citáciami, rýchla odpoveď, formulár nového emailu, kontakty z poštovej schránky, kalendár ako placeholder. Dáta: `InMemoryMailRepository` s ukážkovými správami (nič sa neposiela po sieti). |

## Zatiaľ nezačaté

`core-database` (Room), `data-gmail`, `data-imap`, `data-smtp`, `data-pop3`, prílohy, autentifikácia
(Keystore, OAuth), synchronizácia (WorkManager), nastavenia, kalendár (funkcia), notifikácie, HTML renderer,
detekcia pozície zloženia (androidx.window / tabletop) – layout sa zatiaľ riadi len šírkou okna.

## Ako to dokončiť u vás

1. Otvorte priečinok v Android Studiu (aktuálna stabilná verzia), nechajte Gradle sync a opravte verzie.
2. Overenie jadra: `./gradlew :core-domain:test` a `./gradlew :app:testDebugUnitTest`
3. Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`

## Google OAuth (pre budúci Gmail modul)

1. Google Cloud Console → nový projekt → zapnúť Gmail API.
2. OAuth consent screen → pridať scopes (Gmail) a testovacích používateľov.
3. Credentials → OAuth client ID typu **Android** (package name + SHA-1 z podpisového kľúča).
4. Client ID vložiť do `local.properties` (`GOOGLE_CLIENT_ID=...`), súbor nie je vo verzii gitu.
   Gmail scopes sú „restricted", takže verejné vydanie vyžaduje overenie aplikácie Googlom;
   pre osobné použitie stačí režim Testing.
