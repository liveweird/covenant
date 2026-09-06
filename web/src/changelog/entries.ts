// The changelog is a build-time artifact: entries are authored here (newest first) and bundled
// into the SPA, so it can only change with a deploy — never at runtime. The newest entry's
// version must equal APP_VERSION in ./version.ts (the shell's eager import — this file stays
// out of the main bundle): a release adds the entry AND bumps that literal; entries.test.ts
// pins the pair. Bodies are markdown, one per language (content, not chrome — hence not in
// locales/); keep the phrases tests assert on in plain text runs, and follow the Polish style
// conventions (inclusive slash forms, active voice).
interface ChangelogEntry {
  version: string;
  /** Release date, YYYY-MM-DD. Keep the array strictly descending by date. */
  date: string;
  /** Markdown body, English. */
  en: string;
  /** Markdown body, Polish. */
  pl: string;
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  {
    version: "0.6.0",
    date: "2026-09-06",
    en: `**The Errors report: every finding in the catalog, on one page.**

- A new Errors view sits beside Hierarchy and Contracts: every version still carrying a finding, grouped under its contract, with the findings shown as badges and a link straight into the version. The same filters as the Contracts list narrow it — plus the version's own lifecycle, with retired versions hidden until you ask for them.
- Three tiles say how many contracts, versions and findings match; the Error, Warning and Note chips and the source chips (Schema, Semantic, Lint, Breaking change, System) filter the report and show what turning each one on would yield. The report opens on errors and warnings; notes are one click away.
- Sample contracts for trying it out: clean, warning-only and deliberately invalid OpenAPI, AsyncAPI and ODCS documents, with a loader that fills a local instance.`,
    pl: `**Raport błędów: każda uwaga w katalogu na jednej stronie.**

- Nowy widok Błędy stoi obok Hierarchii i Kontraktów: każda wersja, która nadal ma uwagi, pogrupowana pod swoim kontraktem, z uwagami pokazanymi jako odznaki i linkiem prosto do wersji. Zawężają go te same filtry co listę Kontraktów — plus własny cykl życia wersji, z wycofanymi wersjami ukrytymi, dopóki o nie nie poprosisz.
- Trzy kafelki mówią, ile kontraktów, wersji i uwag pasuje; przełączniki Błąd, Ostrzeżenie i Informacja oraz przełączniki źródeł (Schemat, Semantyka, Lint, Zmiana łamiąca, System) filtrują raport i pokazują, co dałoby włączenie każdego z nich. Raport otwiera się na błędach i ostrzeżeniach; informacje są jedno kliknięcie dalej.
- Przykładowe kontrakty do wypróbowania: czyste, tylko z ostrzeżeniami i celowo niepoprawne dokumenty OpenAPI, AsyncAPI i ODCS, z programem ładującym, który wypełnia lokalną instancję.`,
  },
  {
    version: "0.5.1",
    date: "2026-09-06",
    en: `**The checkup release: guardrails, hardening and a few gaps closed.**

- Deleting a system that still holds contracts, or a team that still owns them, is refused — move or transfer the contracts first. Systems now show how many contracts they hold.
- The deployment gained health and readiness probes, a non-root container and a hardened Kubernetes pod; the CI pipeline gained a check that the client types match the API contract, a check that every documented response is exercised by a test, and nightly browser runs.
- Accessibility: every dialog's close button is named, long code values wrap instead of scrolling, and the read-only editor can be reached from the keyboard — all found by the widened automated sweep over detail pages, drawers and dialogs.
- The reader's POST badge is gray, like the other methods: the brand purple stays for the actions you can click.`,
    pl: `**Wydanie po przeglądzie: bariery jakości, wzmocnienia i kilka domkniętych luk.**

- Usunięcie systemu, który nadal zawiera kontrakty, albo zespołu, który nadal jest ich właścicielem, jest odmawiane — najpierw przenieś kontrakty. Systemy pokazują teraz, ile kontraktów zawierają.
- Wdrożenie zyskało sondy działania i gotowości, kontener bez uprawnień roota i wzmocniony pod Kubernetes; potok CI zyskał sprawdzenie, że typy klienta zgadzają się z kontraktem API, sprawdzenie, że każda udokumentowana odpowiedź ma swój test, oraz nocne przebiegi w przeglądarce.
- Dostępność: przycisk zamknięcia każdego okna dialogowego ma nazwę, długie wartości kodu zawijają się zamiast przewijać, a edytor tylko do odczytu jest osiągalny z klawiatury — wszystko wykryte przez rozszerzony automatyczny przegląd stron szczegółów, szuflad i okien dialogowych.
- Odznaka metody POST w Czytniku jest szara jak pozostałe: fiolet marki zostaje dla akcji, które da się kliknąć.`,
  },
  {
    version: "0.5.0",
    date: "2026-09-05",
    en: `**The contract reader.**

- Every version now has a Reader view beside the Source: the document rendered for reading — for OpenAPI, the operations grouped by tag with their parameters, request bodies, responses and security; for AsyncAPI, the servers, channels, operations and messages; for ODCS, the datasets with their columns, quality rules, servers, team and service levels.
- Schemas are navigable trees: types, formats, constraints, enumerations, defaults and examples, references resolved in place (and linked to the Schemas section), recursion and unresolvable references marked. A table of contents follows you down the page.
- A finding in the Reader jumps to the element it concerns. A published version opens on the Reader, an editable one on the Source; your own choice is remembered, and the Hierarchy links straight into the Reader.`,
    pl: `**Czytnik kontraktów.**

- Każda wersja ma teraz widok Czytnika obok Źródła: dokument przygotowany do czytania — dla OpenAPI operacje pogrupowane według tagów z parametrami, treścią żądań, odpowiedziami i zabezpieczeniami; dla AsyncAPI serwery, kanały, operacje i komunikaty; dla ODCS zbiory danych z kolumnami, regułami jakości, serwerami, zespołem i poziomami usług.
- Schematy to nawigowalne drzewa: typy, formaty, ograniczenia, wyliczenia, wartości domyślne i przykłady, referencje rozwiązane w miejscu (i podlinkowane do sekcji Schematy), rekurencja i nierozwiązywalne referencje oznaczone. Spis treści towarzyszy Ci w dół strony.
- Uwaga w Czytniku przenosi do elementu, którego dotyczy. Opublikowana wersja otwiera się w Czytniku, edytowalna w Źródle; Twój własny wybór jest zapamiętywany, a Hierarchia prowadzi prosto do Czytnika.`,
  },
  {
    version: "0.4.0",
    date: "2026-09-05",
    en: `**Follow contracts, count before you filter, and try a contract against a real system.**

- Follow a contract and every event on it — versions created, edited, published, synced, a breaking change waived, the owner changed — lands in your bell; your own actions stay silent. Open a notification to jump to the version, mark one or all as seen.
- Every filter option on the catalog says how many contracts picking it would yield.
- Administrators register Environments per system: an HTTP base URL, a Kafka cluster, a read-only PostgreSQL database. Passwords are stored encrypted and never shown again.
- Try it, on any version: send a request to an OpenAPI operation, publish a record onto an AsyncAPI channel (contract writers only) or read its newest records, read a sample of an ODCS dataset — through an environment, with the response, the records or the columns measured against the contract as conformance findings. Nothing is stored; credentials you type are sent once and forgotten.`,
    pl: `**Obserwuj kontrakty, licz przed filtrowaniem i wypróbuj kontrakt na prawdziwym systemie.**

- Obserwuj kontrakt, a każde zdarzenie — utworzone, edytowane, opublikowane i zsynchronizowane wersje, zaakceptowana zmiana łamiąca, zmiana właściciela/właścicielki — trafi do Twojego dzwonka; własne działania pozostają ciche. Otwórz powiadomienie, by przejść do wersji, oznacz jedno albo wszystkie jako przeczytane.
- Każda opcja filtra w katalogu mówi, ile kontraktów da jej wybranie.
- Administratorzy/administratorki rejestrują Środowiska per system: bazowy adres HTTP, klaster Kafka, bazę PostgreSQL tylko do odczytu. Hasła są przechowywane zaszyfrowane i nigdy więcej nie pokazywane.
- Wypróbuj, na dowolnej wersji: wyślij żądanie do operacji OpenAPI, opublikuj rekord na kanale AsyncAPI (tylko osoby piszące kontrakt) albo odczytaj jego najnowsze rekordy, pobierz próbkę zbioru danych ODCS — przez środowisko, z odpowiedzią, rekordami lub kolumnami zmierzonymi względem kontraktu jako uwagi o zgodności. Nic nie jest zapisywane; wpisane dane uwierzytelniające są wysyłane raz i zapominane.`,
  },
  {
    version: "0.3.0",
    date: "2026-09-05",
    en: `**Breaking changes, history and sync from the repository.**

- Every save and the live check compare the document against the contract's active version: a breaking change without a major bump is a blocking finding you can still waive; with the bump it is a note. OpenAPI, AsyncAPI and ODCS each have their own comparison.
- The contract page shows its history — versions created, edited, published, synced, owners changed — one line per event.
- A version can be linked to its file in the repository. Sync fetches the repository copy, shows which side changed and the line diff, and overwrites a draft or starts a new version from the repository copy when the version is published. A document imported from a URL is linked automatically.`,
    pl: `**Zmiany łamiące, historia i synchronizacja z repozytorium.**

- Każdy zapis i sprawdzanie na żywo porównują dokument z aktywną wersją kontraktu: zmiana łamiąca bez podniesienia wersji głównej to blokująca uwaga, którą nadal można zaakceptować; z podniesieniem — tylko informacja. OpenAPI, AsyncAPI i ODCS mają własne porównania.
- Strona kontraktu pokazuje jego historię — utworzone, edytowane, opublikowane i zsynchronizowane wersje, zmiany właściciela — po jednej linii na zdarzenie.
- Wersję można powiązać z jej plikiem w repozytorium. Synchronizacja pobiera kopię z repozytorium, pokazuje, która strona się zmieniła, oraz różnice linia po linii, a następnie nadpisuje szkic albo — gdy wersja jest opublikowana — zaczyna nową wersję od kopii z repozytorium. Dokument zaimportowany z adresu URL zostaje powiązany automatycznie.`,
  },
  {
    version: "0.2.0",
    date: "2026-09-05",
    en: `**The contract catalog: domains, systems, teams, contracts and their versions.**

- Browse the catalog as a Domain → System → Contract tree on the home page, or as a filterable list.
- Contracts belong to a system, follow one standard (OpenAPI, AsyncAPI or ODCS) and are owned by a team or a person; owners and administrators edit them, everyone reads.
- Every version is the standard document itself, kept byte-exact, numbered with strict SemVer and moved through a lifecycle: draft → proposed → active → deprecated → retired. Published text is locked.
- The editor checks the document as you type — syntax, schema, semantic and lint findings, with jump-to-line — and lets you save with soft findings after an explicit confirmation.
- Import a document by pasting, uploading or fetching a public URL; compare two versions line by line; download any version; export a contract with all its versions.`,
    pl: `**Katalog kontraktów: domeny, systemy, zespoły, kontrakty i ich wersje.**

- Przeglądaj katalog jako drzewo Domena → System → Kontrakt na stronie startowej albo jako filtrowaną listę.
- Kontrakt należy do systemu, trzyma się jednego standardu (OpenAPI, AsyncAPI lub ODCS) i ma właściciela — zespół albo osobę; edytują go właściciele i administratorzy/rki, czytają wszyscy.
- Każda wersja to sam dokument standardu, przechowywany bajt w bajt, numerowany ścisłym SemVer i prowadzony przez cykl życia: szkic → zaproponowana → aktywna → przestarzała → wycofana. Opublikowany tekst jest zablokowany.
- Edytor sprawdza dokument w trakcie pisania — uwagi składniowe, schematowe, semantyczne i lint, z przejściem do linii — i pozwala zapisać z miękkimi uwagami po wyraźnym potwierdzeniu.
- Importuj dokument wklejając go, wgrywając plik lub pobierając z publicznego adresu URL; porównuj dwie wersje linia po linii; pobieraj dowolną wersję; eksportuj kontrakt ze wszystkimi wersjami.`,
  },
  {
    version: "0.1.0",
    date: "2026-09-05",
    en: `**Covenant is born: the foundation for a shared contract repository.**

- Sign in with your account, change your password, reset it by email, and opt in to a one-time sign-in code (MFA).
- Administrators manage accounts and per-user feature flags.
- The interface speaks English and Polish, follows your light or dark preference, and opens any page from the ⌘K / Ctrl K palette.
- Coming next: domains, systems, contracts (OpenAPI, AsyncAPI, ODCS) and their SemVer versions with lifecycles.`,
    pl: `**Covenant startuje: fundament wspólnego repozytorium kontraktów.**

- Zaloguj się na swoje konto, zmień hasło, zresetuj je e-mailem i włącz jednorazowy kod logowania (MFA).
- Administratorzy/rki zarządzają kontami i flagami funkcji per użytkownik/czka.
- Interfejs mówi po angielsku i po polsku, podąża za Twoim jasnym lub ciemnym motywem i otwiera każdą stronę z palety ⌘K / Ctrl K.
- Następne w kolejce: domeny, systemy, kontrakty (OpenAPI, AsyncAPI, ODCS) i ich wersje SemVer z cyklami życia.`,
  },
];
