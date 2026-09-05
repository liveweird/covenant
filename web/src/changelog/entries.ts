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
