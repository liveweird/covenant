import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['test/**/*.test.ts'],
    // The engines load rulesets on first use — give the first test room.
    testTimeout: 30_000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.ts'],
      // Floors set just below measured actuals (re-measure with `npm run test:coverage`);
      // raise as coverage improves, never lower.
      // 2026-09-21: lines 92.94 / statements 90.47 / functions 90.12 / branches 84.14.
      thresholds: {
        lines: 92,
        statements: 90,
        functions: 90,
        branches: 84,
      },
    },
  },
})
