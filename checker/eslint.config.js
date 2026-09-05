import js from '@eslint/js'
import globals from 'globals'
import sonarjs from 'eslint-plugin-sonarjs'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// The checker's quality gate — the sibling of web/eslint.config.js and config/detekt/detekt.yml:
// zero findings, no baseline, one commented override per deliberate idiom.
export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.ts'],
    extends: [js.configs.recommended, tseslint.configs.recommended, sonarjs.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // http:// literals are the healthcheck/test fixtures against 127.0.0.1 — the service
      // is never exposed beyond the compose/k8s internal network.
      'sonarjs/no-clear-text-protocols': 'off',
      // Test names are literal sentences by convention; test.each would generate dynamic names.
      'sonarjs/parameterized-tests': 'off',
      // A handler over node:http is one function by design; backstops only.
      'sonarjs/cognitive-complexity': ['error', 25],
      complexity: ['error', 30],
      'max-lines-per-function': ['error', { max: 200, skipBlankLines: true, skipComments: true }],
      'max-depth': ['error', 5],
      'max-params': ['error', 6],
    },
  },
])
