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
