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
    version: "1.0.3",
    date: "2026-09-22",
    en: `**Reliable sign-out confirmation.**

- The sign-out confirmation remains visible when the login page retries rendering.`,
    pl: `**Niezawodne potwierdzenie wylogowania.**

- Potwierdzenie wylogowania pozostaje widoczne, gdy strona logowania ponawia renderowanie.`,
  },
  {
    version: "1.0.2",
    date: "2026-09-22",
    en: `**Predictable navigation after signing out.**

- Signing in after an explicit sign-out opens the Hierarchy instead of returning to the previous account's page.
- Signing in to open a protected link still preserves its destination, query parameters and anchor.`,
    pl: `**Przewidywalna nawigacja po wylogowaniu.**

- Gdy wylogujesz się i zalogujesz ponownie, otworzysz Hierarchię zamiast strony poprzedniego konta.
- Gdy zalogujesz się, aby otworzyć chroniony link, zachowasz jego adres docelowy, parametry zapytania i kotwicę.`,
  },
  {
    version: "1.0.1",
    date: "2026-09-22",
    en: `**Clearer setup for Toadie registry synchronization.**

- Opening registry sync on an unconfigured connection now explains the required setup and links directly to its settings.
- Failed candidate requests stop loading and offer a retry instead of leaving a spinner visible.`,
    pl: `**Czytelniejsza konfiguracja synchronizacji rejestrów z Toadie.**

- Otwarcie synchronizacji rejestrów dla nieskonfigurowanego połączenia wyjaśnia wymagane ustawienia i prowadzi bezpośrednio do konfiguracji.
- Nieudane pobieranie rekordów kończy stan ładowania i umożliwia ponowienie próby zamiast pozostawiać widoczny wskaźnik ładowania.`,
  },
  {
    version: "1.0.0",
    date: "2026-09-21",
    en: `**Covenant 1.0 — contract lifecycle management connected to your architecture.**

- Preview and import selected domains, systems and teams from Toadie's Port ontology, or link them to existing Covenant records.
- Linked metadata follows Toadie, with visible source and synchronization status. Missing upstream records preserve their local contracts and environments.
- Team memberships, contract permissions and lifecycle decisions remain managed in Covenant. Local-only registries continue to work.`,
    pl: `**Covenant 1.0 — zarządzanie cyklem życia kontraktów połączone z architekturą.**

- Przeglądaj i importuj wybrane domeny, systemy i zespoły z ontologii Port w Toadie lub łącz je z istniejącymi rekordami Covenant.
- Metadane powiązanych rekordów są aktualizowane z Toadie, a źródło i stan synchronizacji pozostają widoczne. Brak rekordu w źródle nie usuwa lokalnych kontraktów ani środowisk.
- Członkostwo w zespołach, uprawnienia do kontraktów i decyzje dotyczące cyklu życia nadal są zarządzane w Covenant. Rejestry lokalne pozostają dostępne.`,
  },
  {
    version: "0.16.0",
    date: "2026-09-21",
    en: `**More reliable and efficient Toadie refreshes.**

- Refreshes check that Toadie's ontology revision stays consistent across the complete read and retry once if it changes.
- Scheduled refreshes confirm freshness without rereading an unchanged graph. Manual refresh still reads the full graph.
- Failed checks preserve the last complete usage observation and show it as stale. Requires Toadie 2.12 or newer.`,
    pl: `**Bardziej niezawodne i wydajne odświeżanie danych z Toadie.**

- Odświeżanie sprawdza, czy rewizja ontologii Toadie pozostaje taka sama przez cały odczyt, i ponawia go raz w przypadku zmiany.
- Zaplanowane odświeżanie potwierdza aktualność danych bez ponownego odczytu niezmienionego grafu. Ręczne odświeżanie nadal odczytuje cały graf.
- Nieudane sprawdzenie zachowuje ostatnie kompletne dane o użyciu i oznacza je jako nieaktualne. Wymagane jest Toadie 2.12 lub nowsze.`,
  },
  {
    version: "0.15.0",
    date: "2026-09-21",
    en: `**Share migration plans and declared impact.**

- Download a Markdown report from a release line's impact dialog for planning discussions.
- Reports include the saved support dates, replacement, recommendation, migration instructions and declared provider/consumer services with their systems and teams.
- Generation and observation timestamps, unavailable mappings and stale usage warnings travel with the report. Usage describes the whole contract; exact version and release-line adoption remain unknown.`,
    pl: `**Udostępniaj plany migracji i deklarowane zależności.**

- Pobierz raport Markdown z okna wpływu wycofania linii wydań do rozmów o planowanej migracji.
- Raport zawiera zapisane daty wsparcia, kontrakt zastępujący, rekomendowaną wersję, instrukcje migracji oraz deklarowane usługi dostawców i konsumentów wraz z ich systemami i zespołami.
- Raport zachowuje daty wygenerowania i obserwacji, niedostępne powiązania oraz ostrzeżenia o nieaktualnych danych. Użycie dotyczy całego kontraktu; używane wersje i linie wydań pozostają nieznane.`,
  },
  {
    version: "0.14.3",
    date: "2026-09-21",
    en: `**Catalog integrity and reliable selections.**

- Concurrent creation, moves and ownership changes now coordinate with parent deletion to keep the catalog consistent.
- Prerelease versions follow SemVer order across pages, error reports and exports, including rc.2 and rc.10.
- Switching Toadie connections clears stale API choices; syncing a published version retains its source major line.
- System, team and comparison pickers include choices beyond the first 100 records.
- Environment HTTP validation rejects equivalent link-local address literals while preserving internal targets.`,
    pl: `**Spójność katalogu i poprawny wybór rekordów.**

- Równoczesne tworzenie, przenoszenie i zmiana właściciela uwzględniają usuwanie rekordów nadrzędnych, zachowując spójność katalogu.
- Wersje przedpremierowe są sortowane zgodnie z SemVer na wszystkich stronach list, w raporcie błędów i eksporcie, także dla rc.2 i rc.10.
- Zmiana połączenia z Toadie usuwa nieaktualne opcje API, a synchronizacja opublikowanej wersji zachowuje jej główną linię wydań.
- Pola wyboru systemu, zespołu i porównywanych wersji obejmują rekordy poza pierwszą setką.
- Walidacja adresów HTTP środowisk odrzuca równoważne zapisy adresów link-local, nadal dopuszczając adresy wewnętrzne.`,
  },
  {
    version: "0.14.2",
    date: "2026-09-21",
    en: `**Reliable validation and breaking-change checks.**

- OpenAPI breaking-change detection now handles compact JSON consistently with formatted JSON and YAML.
- The checker accepts the full document size limit, including comparison baselines and JSON encoding overhead.
- When a breaking change blocks a draft save or edit, Save anyway retains the contract baseline and shows the findings for your confirmation.`,
    pl: `**Spójna walidacja i wykrywanie zmian niezgodnych wstecznie.**

- Wykrywanie zmian niezgodnych wstecznie w OpenAPI obsługuje teraz zwarty JSON tak samo jak sformatowany JSON i YAML.
- Usługa sprawdzająca przyjmuje dokumenty do pełnego limitu rozmiaru, z uwzględnieniem wersji porównawczej i narzutu kodowania JSON.
- Gdy zmiana niezgodna wstecznie blokuje zapis lub edycję szkicu, opcja Zapisz mimo to zachowuje wersję porównawczą kontraktu i pokazuje wyniki sprawdzania do potwierdzenia.`,
  },
  {
    version: "0.14.1",
    date: "2026-09-21",
    en: `**Keep sign-in sessions consistent.**

- Delayed session renewal can no longer restore a signed-out account or overwrite a newer sign-in.
- Password changes and resets invalidate earlier session renewal tokens and pending sign-in codes, including changes within the same second.
- After this update, existing sessions require a fresh sign-in when their access token expires.`,
    pl: `**Spójne sesje logowania.**

- Opóźnione odnowienie sesji nie przywraca już wylogowanego konta ani nie zastępuje nowszego logowania.
- Zmiany i resetowanie hasła unieważniają wcześniejsze tokeny odnawiania sesji oraz oczekujące kody logowania, również przy zmianach w tej samej sekundzie.
- Po tej aktualizacji istniejące sesje wymagają ponownego logowania po wygaśnięciu tokenu dostępu.`,
  },
  {
    version: "0.14.0",
    date: "2026-09-20",
    en: `**Find contract reviews that need attention.**

- Open the Review inbox for proposed versions of contracts you own, your teams own, or you follow; broaden the scope to the whole catalog when needed.
- Find reviews awaiting your decision, requests for changes, and proposals needing a fresh review. Older rounds disappear from the inbox when a newer request supersedes them.
- Search and page the results, then open the document's Reviews section. Reviews remain optional and never block publication.`,
    pl: `**Znajduj recenzje kontraktów wymagające uwagi.**

- Otwórz Skrzynkę recenzji, aby zobaczyć proponowane wersje kontraktów należących do Ciebie lub Twoich zespołów oraz kontraktów, które obserwujesz. W razie potrzeby rozszerz zakres na cały katalog.
- Znajduj recenzje czekające na Twoją decyzję, prośby o zmiany i propozycje wymagające nowej recenzji. Nowa runda zastępuje poprzednią w skrzynce.
- Wyszukuj i przeglądaj wyniki, a następnie otwieraj sekcję recenzji dokumentu. Recenzje pozostają opcjonalne i nigdy nie blokują publikacji.`,
  },
  {
    version: "0.13.0",
    date: "2026-09-20",
    en: `**Review proposed contract versions together.**

- Request review from the version page, discuss the proposal, and record approval or changes requested.
- Decisions belong to the content that was reviewed. Editing the document makes earlier reviews outdated, while preserving their discussion and decisions.
- Reviews are optional and never block publication. Existing ownership and lifecycle rules still govern changes to a contract.`,
    pl: `**Wspólnie recenzujcie proponowane wersje kontraktów.**

- Poproś o recenzję na stronie wersji, omów propozycję i zapisz akceptację lub prośbę o zmiany.
- Decyzje dotyczą recenzowanej treści. Edycja dokumentu dezaktualizuje wcześniejsze recenzje, zachowując dyskusję i decyzje.
- Recenzje są opcjonalne i nigdy nie blokują publikacji. Dotychczasowe zasady własności i cyklu życia nadal określają możliwość zmiany kontraktu.`,
  },
  {
    version: "0.12.0",
    date: "2026-09-20",
    en: `**See lifecycle plans across the catalog.**

- Browse major release lines together and filter by owner, domain, system, contract type, support status and deadline window.
- Focus on approaching deadlines, support-end dates already reached, incomplete migration plans and unavailable usage information.
- Open a line's migration plan and declared consumer impact, or edit its policy with existing owner permissions.
- Dates remain advisory. Cached Toadie usage describes the whole contract and does not establish which version or release line a service uses.`,
    pl: `**Przeglądaj plany cyklu życia w całym katalogu.**

- Przeglądaj razem linie wydań major i filtruj je według właściciela, domeny, systemu, typu kontraktu, statusu wsparcia i przedziału terminów.
- Sprawdzaj zbliżające się terminy, osiągnięte daty zakończenia wsparcia, niepełne plany migracji i niedostępne informacje o wykorzystaniu.
- Otwieraj plan migracji i informacje o deklarowanych odbiorcach albo edytuj zasady zgodnie z dotychczasowymi uprawnieniami właściciela.
- Daty pozostają informacyjne. Dane z pamięci podręcznej Toadie dotyczą całego kontraktu i nie określają, której wersji lub linii wydań używa usługa.`,
  },
  {
    version: "0.11.0",
    date: "2026-09-20",
    en: `**Plan deprecation and review retirement impact.**

- Add a deprecation date, replacement contract or release line, and migration instructions to a line's support policy.
- Followers receive in-app reminders as deprecation and support-end dates approach or pass.
- Review declared consumers, teams and data freshness before retiring a version or ending line support. Dates stay advisory; lifecycle changes remain explicit owner decisions.`,
    pl: `**Planuj wycofanie i sprawdzaj jego skutki.**

- Dodawaj do zasad wsparcia linii datę wycofania z zalecanego użycia, kontrakt lub linię zastępczą oraz instrukcje migracji.
- Osoby obserwujące otrzymują przypomnienia w aplikacji o zbliżającym się lub osiągniętym terminie wycofania i końca wsparcia.
- Przed wycofaniem wersji lub zakończeniem wsparcia sprawdzaj zadeklarowanych odbiorców, zespoły i aktualność danych. Daty są informacyjne; zmiany cyklu życia wymagają decyzji właściciela/właścicielki.`,
  },
  {
    version: "0.10.0",
    date: "2026-09-20",
    en: `**See contract usage from Toadie's Port ontology.**

- Link contracts to existing Toadie APIs and see the services that provide or consume them, with their systems, teams and links back to Toadie.
- Administrators can configure Toadie connections and ontology mappings. Usage refreshes periodically and on demand; failed refreshes retain the last successful observation and mark it stale.
- Contract versions and release lines remain managed in Covenant. Usage does not infer which version a service uses or change anyone's permissions.`,
    pl: `**Sprawdzaj wykorzystanie kontraktów na podstawie ontologii Port w Toadie.**

- Łącz kontrakty z istniejącymi API w Toadie i sprawdzaj, które usługi je udostępniają lub wykorzystują, wraz z ich systemami, zespołami i odnośnikami do Toadie.
- Administratorzy/administratorki mogą konfigurować połączenia z Toadie i mapowanie ontologii. Dane odświeżają się okresowo i na żądanie; nieudane odświeżenie zachowuje ostatnią udaną obserwację i oznacza ją jako nieaktualną.
- Wersje kontraktów i linie wydań nadal są zarządzane w Covenant. Informacje o wykorzystaniu nie określają automatycznie używanej wersji ani nie zmieniają uprawnień.`,
  },
  {
    version: "0.9.0",
    date: "2026-09-20",
    en: `**Maintain several release lines in parallel.**

- Keep 1.x and 2.x available together, and add maintenance backports even after newer minor or major versions exist.
- Give each major line its own support status, support-end date and policy notes, with an automatic or pinned recommended stable version.
- Browse and create versions within a release line. The highest catalog version is shown separately from recommendations.
- Compatibility checks use the relevant published predecessor, including deprecated versions. Ending support for a line leaves version lifecycles unchanged.`,
    pl: `**Utrzymuj kilka linii wydań równolegle.**

- Udostępniaj jednocześnie linie 1.x i 2.x oraz dodawaj poprawki do starszych wersji także po utworzeniu nowszych wersji minor lub major.
- Określaj osobno dla każdej linii major status wsparcia, datę jego zakończenia i zasady oraz wybieraj zalecaną stabilną wersję automatycznie lub ręcznie.
- Przeglądaj i twórz wersje w wybranej linii wydań. Najwyższa wersja w katalogu jest pokazywana oddzielnie od rekomendacji.
- Sprawdzanie zgodności uwzględnia właściwą wcześniejszą opublikowaną wersję, również przestarzałą. Zakończenie wsparcia linii nie zmienia cyklu życia jej wersji.`,
  },
  {
    version: "0.8.1",
    date: "2026-09-20",
    en: `**Maintenance and reliability fixes.**

- Version saves reject stale validation results and recheck write permissions; imports create each contract and its first version together.
- Contract checks are better protected against recursive and resource-intensive documents.
- The owner picker now shows available users when opened, without silently filtering by the current owner's name.
- Fixed source synchronization, inferred drafts, links to findings and error states in the editor.
- Updated dependencies and runtime images, including security fixes in development tooling.`,
    pl: `**Poprawki utrzymaniowe i większa niezawodność.**

- Zapis wersji odrzuca nieaktualne wyniki walidacji i ponownie sprawdza uprawnienia do edycji; import tworzy kontrakt i jego pierwszą wersję razem.
- Sprawdzanie kontraktów jest lepiej chronione przed dokumentami rekurencyjnymi i nadmiernie obciążającymi zasoby.
- Lista właścicieli pokazuje dostępne osoby po otwarciu, bez niejawnego filtrowania według nazwy obecnego właściciela.
- Poprawiono synchronizację ze źródłem, szkice tworzone przez wnioskowanie, odnośniki do ustaleń i obsługę błędów w edytorze.
- Zaktualizowano zależności i obrazy środowiska uruchomieniowego, w tym poprawki bezpieczeństwa narzędzi deweloperskich.`,
  },
  {
    version: "0.8.0",
    date: "2026-09-08",
    en: `**Contracts from what actually flows.**

- Infer a contract — a fourth way in, beside paste, import and sync: hand Covenant a sample of what really flows and get a draft to review. An HTTP exchange becomes an OpenAPI 3.1 draft (templated paths, query parameters, request and response schemas, security schemes from the header names). Event payloads become an AsyncAPI 3.0 draft (one channel, one message, a CloudEvents envelope recognised). A database table becomes an ODCS draft (columns, types, NOT NULL, the primary key).
- Three ways to bring a sample: paste it, upload a HAR file from your browser's DevTools (parsed in the browser — header values and credentials never leave it), or observe it live through an environment: an HTTP call, a tail of a Kafka topic, a PostgreSQL table described from its catalog.
- Every heuristic is named: an Inference note beside the draft says what was guessed — a templated path segment, a merged sample set, a column type it did not recognise, a view whose nullability cannot be read.
- Nothing is stored until you save: the draft opens in the editor you already know, with its live findings, and goes through the ordinary save.`,
    pl: `**Kontrakty z tego, co naprawdę płynie.**

- Wnioskowanie kontraktu — czwarta droga obok wklejenia, importu i synchronizacji: podaj Covenantowi próbkę tego, co naprawdę płynie, i dostań szkic do przeglądu. Wymiana HTTP staje się szkicem OpenAPI 3.1 (ścieżki z parametrami, parametry zapytania, schematy żądania i odpowiedzi, schematy bezpieczeństwa z nazw nagłówków). Ładunki zdarzeń stają się szkicem AsyncAPI 3.0 (jeden kanał, jedna wiadomość, rozpoznana koperta CloudEvents). Tabela bazy danych staje się szkicem ODCS (kolumny, typy, NOT NULL, klucz główny).
- Trzy sposoby na próbkę: wklej ją, wczytaj plik HAR z DevTools przeglądarki (parsowany w przeglądarce — wartości nagłówków i dane uwierzytelniające nigdy jej nie opuszczają) albo zaobserwuj na żywo przez środowisko: wywołanie HTTP, ogon tematu Kafki, tabelę PostgreSQL opisaną z jej katalogu.
- Każda heurystyka ma nazwę: notatka wnioskowania obok szkicu mówi, co zostało zgadnięte — segment ścieżki zamieniony na parametr, połączony zestaw próbek, nierozpoznany typ kolumny, widok, którego nullowalności nie da się odczytać.
- Nic nie jest zapisywane, dopóki nie zapiszesz: szkic otwiera się w znanym edytorze, z bieżącymi ustaleniami, i przechodzi przez zwykły zapis.`,
  },
  {
    version: "0.7.0",
    date: "2026-09-07",
    en: `**Compatibility, named.**

- The Compare page now says whether the two versions you picked are fully, backward, forward or not compatible — and why: the breaking changes in each direction, and whether the version bump promised what it delivered (a minor or patch bump promises backward compatibility; a major bump permits a break).
- Every version page shows its verdict against the active version before it, with a Details link into the comparison.
- The verdict is computed on demand from the stored documents for OpenAPI, AsyncAPI and ODCS alike; nothing is stored, and a side that cannot be compared is reported as unknown rather than guessed.`,
    pl: `**Zgodność nazwana po imieniu.**

- Strona Porównaj mówi teraz, czy dwie wybrane wersje są w pełni zgodne, zgodne wstecz, zgodne w przód czy niezgodne — i dlaczego: zmiany łamiące w każdym kierunku oraz to, czy podbicie wersji dotrzymało obietnicy (podbicie minor lub patch obiecuje zgodność wstecz; podbicie major dopuszcza zmianę łamiącą).
- Każda strona wersji pokazuje swój werdykt względem poprzedzającej ją aktywnej wersji, z linkiem Szczegóły do porównania.
- Werdykt jest liczony na żądanie z zapisanych dokumentów — tak samo dla OpenAPI, AsyncAPI i ODCS; nic nie jest zapisywane, a strona, której nie da się porównać, jest zgłaszana jako nieznana, a nie zgadywana.`,
  },
  {
    version: "0.6.1",
    date: "2026-09-07",
    en: `**Three fixes for reading comfort.**

- The sidebar collapses to an icon rail from a button in the header and remembers your choice; the labels return in a tooltip.
- The Hierarchy can hide domains and systems with no matching contract — a switch beside Expand all and Collapse all.
- The version page has one side panel: Contents above Findings, on the right, for every contract type. Hide it for a full-width document; wide dataset tables now scroll inside their own frame instead of pushing the page.`,
    pl: `**Trzy poprawki dla wygody czytania.**

- Pasek boczny zwija się do paska ikon przyciskiem w nagłówku i pamięta Twój wybór; etykiety wracają w podpowiedzi.
- Hierarchia może ukryć domeny i systemy bez pasujących kontraktów — przełącznik obok Rozwiń wszystko i Zwiń wszystko.
- Strona wersji ma jeden panel boczny: Spis treści nad Uwagami, po prawej, dla każdego typu kontraktu. Ukryj go, aby czytać dokument na całej szerokości; szerokie tabele zbiorów danych przewijają się teraz w swojej ramce, zamiast rozpychać stronę.`,
  },
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
