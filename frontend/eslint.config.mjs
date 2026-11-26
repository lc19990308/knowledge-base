import js from '@eslint/js'
import prettier from 'eslint-config-prettier/flat'
import { defineConfig, globalIgnores } from 'eslint/config'
import vue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import vueParser from 'vue-eslint-parser'

export default defineConfig([
  // 临时调试脚本（tmp-*.mjs）是本地一次性产物，不纳入 lint，避免门禁被他人残留脚本卡住。
  globalIgnores(['dist/**', '**/tmp-*.mjs']),
  { files: ['**/*.mjs'], extends: [js.configs.recommended] },
  {
    // e2e 脚本在 Node 里驱动浏览器，page.evaluate 回调里会直接用浏览器全局；
    // 显式声明这些只读全局，避免他人残留的临时探测脚本把全量 lint 卡住。
    files: ['e2e/**/*.mjs'],
    languageOptions: {
      globals: {
        console: 'readonly',
        document: 'readonly',
        location: 'readonly',
        performance: 'readonly',
        window: 'readonly',
        globalThis: 'readonly',
      },
    },
  },
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    extends: [js.configs.recommended, tseslint.configs.strictTypeChecked],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
      'no-eval': 'error',
      'no-new-func': 'error',
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tseslint.parser },
    },
    rules: {
      'vue/block-lang': ['error', { script: { lang: 'ts' } }],
      'vue/no-mutating-props': 'error',
      'vue/no-v-html': 'error',
    },
  },
  {
    files: ['src/**/*.vue'],
    ignores: [
      'src/components/FormInput.vue',
      'src/components/FormTextarea.vue',
      'src/components/FormSwitch.vue',
      'src/components/FormRadioGroup.vue',
      'src/components/FormFileInput.vue',
      'src/components/FormCheckbox.vue',
      'src/components/SelectMenu.vue',
    ],
    rules: {
      'vue/no-restricted-html-elements': [
        'error',
        {
          element: 'input',
          message:
            '请使用 FormInput、FormSwitch、FormRadioGroup 或 FormFileInput。',
        },
        { element: 'textarea', message: '请使用 FormTextarea。' },
        {
          element: ['select', 'option', 'optgroup'],
          message: '请使用 SelectMenu。',
        },
      ],
      'vue/no-restricted-class': [
        'error',
        'bk-input',
        'bk-textarea',
        'bk-switch',
      ],
    },
  },
  prettier,
])
