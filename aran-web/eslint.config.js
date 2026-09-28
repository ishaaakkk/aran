import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      jsxA11y.flatConfigs.recommended,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // Honour the usual convention: a leading underscore marks a binding that is
      // deliberately unused (a stub's parameters, a positional placeholder).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // Warn, don't fail. Both rules police the fetch-on-mount-then-setState
      // pattern, and the idiomatic way to satisfy them is to move data fetching
      // out of effects into a data-fetching library — which this project has
      // ruled out (no React Query, no Redux). Until that decision
      // changes they flag the architecture, not a defect, so they should not gate
      // the build. Genuine derived-state cases have been fixed at the call site.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/refs': 'warn',
    },
  },
  {
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      // Page objects legitimately export non-component values from files that
      // sit outside the React tree.
      'react-refresh/only-export-components': 'off',
      // Playwright fixture factories conventionally name their second
      // parameter `use` (`async ({ page }, use) => { await use(...) }`) —
      // that is Playwright's API, not React's. The rule can't tell the two
      // apart by name and flags every fixture in fixtures.ts as an illegal
      // hook call; e2e/**/*.ts never contains real React components or
      // hooks, so the whole rule is a false positive here.
      'react-hooks/rules-of-hooks': 'off',
    },
  },
])
