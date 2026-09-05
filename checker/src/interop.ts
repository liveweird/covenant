/**
 * The Spectral and AsyncAPI packages are CommonJS. What an `import x from "cjs-pkg"` yields
 * depends on WHO loads it: real Node hands over `module.exports` (so the class sits at
 * `x.Parser`), while vitest/Vite may resolve the package's ESM `module` build, where the
 * class is a named export and `default` is something else (or absent). Named imports are worse
 * still — Node's CJS lexer misses tslib's `__exportStar` re-exports ("does not provide an
 * export named 'Spectral'"). This one helper covers every shape; the boot smoke test in
 * test/server.test.ts proves the real-Node path.
 */
export function cjsExport<T>(module: unknown, name: string): T {
  const record = module as Record<string, unknown> & { default?: Record<string, unknown> };
  const found = record[name] ?? record.default?.[name];
  if (found === undefined) throw new Error(`CJS interop: export "${name}" not found`);
  return found as T;
}
