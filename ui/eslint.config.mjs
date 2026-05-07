import js from '@eslint/js'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import vuePlugin from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import prettier from 'eslint-config-prettier/flat'
import globals from 'globals'
import autoImports from './.eslintrc-auto-import.json' with { type: 'json' }

const autoImportGlobals = Object.fromEntries(
  Object.entries(autoImports.globals).map(([k, v]) => [k, v === true ? 'readonly' : v])
)

export default [
  {
    ignores: [
      'auto-imports.d.ts',
      'src/main/dist/**',
      'src/menu/dist-menu/**',
      'dist/**',
      'dist-menu/**',
      'target/**',
      'node_modules/**',
      'public/**',
      'coverage/**'
    ]
  },
  js.configs.recommended,
  ...vuePlugin.configs['flat/essential'],
  prettier,
  {
    files: ['**/*.{js,mjs,cjs,ts,tsx,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parser: vueParser,
      parserOptions: {
        parser: tsParser,
        extraFileExtensions: ['.vue']
      },
      globals: {
        ...globals.browser,
        ...globals.es2021,
        ...autoImportGlobals,
        defineProps: 'readonly',
        defineEmits: 'readonly',
        defineExpose: 'readonly'
      }
    },
    plugins: {
      '@typescript-eslint': tsPlugin
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      'vue/script-setup-uses-vars': 'error',
      indent: ['error', 2, { SwitchCase: 1 }],
      semi: ['error', 'never'],
      quotes: ['error', 'single'],
      'vue/html-quotes': ['error', 'double', { avoidEscape: false }],
      '@typescript-eslint/no-explicit-any': 'off',
      'vue/multi-word-component-names': 'off',
      'comma-dangle': ['error', 'never'],
      curly: ['error', 'all'],
      '@typescript-eslint/no-unused-vars': ['error', { caughtErrors: 'none' }]
    }
  },
  {
    files: ['**/*.{ts,tsx,vue}'],
    rules: {
      'no-undef': 'off'
    }
  },
  {
    files: ['vite.config.*', '*.config.{js,ts,mjs}'],
    languageOptions: {
      globals: { ...globals.node }
    }
  }
]
