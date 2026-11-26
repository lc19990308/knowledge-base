<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import type { EditorView } from '@codemirror/view'
import { Codemirror } from 'vue-codemirror'
import { javascript } from '@codemirror/lang-javascript'
import {
  autocompletion,
  type CompletionContext,
} from '@codemirror/autocomplete'
import { linter, type Diagnostic } from '@codemirror/lint'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import SelectMenu from './SelectMenu.vue'
import FormRadioGroup from './FormRadioGroup.vue'

const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

type EditorMode = 'structured' | 'advanced'
/** 编辑模式选项：radio 卡片的标题与用途说明，切换时复用 switchMode。 */
const EDITOR_MODES: { value: EditorMode; label: string; hint: string }[] = [
  {
    value: 'structured',
    label: '结构化',
    hint: '按段落编排，可插入规则区块',
  },
  {
    value: 'advanced',
    label: '高级文本',
    hint: '直接编辑模板原文，支持变量补全',
  },
]
const mode = ref<EditorMode>('structured')
const localBody = ref(props.modelValue)
const structuredBody = ref(props.modelValue)
const activeEditor = ref(false)
const codeView = shallowRef<EditorView>()

const TOKENS = [
  { label: '用户问题', value: '{question}' },
  { label: '引用列表', value: '{references}' },
  { label: '政策有效性', value: '{validity}' },
] as const

const RULE_BLOCKS = [
  { title: '回答要求', text: '请依据提供的资料回答，内容清晰、准确。' },
  { title: '先给结论', text: '先用一到两句话回答核心问题，再补充必要说明。' },
  {
    title: '引用资料',
    text: '使用资料时标明对应引用，不得编造来源。\n{references}',
  },
  {
    title: '无依据处理',
    text: '资料不足以回答时，明确说明缺少哪些信息，不要猜测。',
  },
  { title: '政策有效性', text: '回答涉及政策时，说明其有效性状态：{validity}' },
] as const

const diagnostics = computed(() => validateTemplate(localBody.value))
const editor = useEditor({
  content: toStructuredHtml(structuredBody.value),
  editable: !props.disabled,
  extensions: [
    StarterKit.configure({ heading: { levels: [2, 3] } }),
    Placeholder.configure({ placeholder: '添加规则区块，使用工具栏插入变量…' }),
  ],
  onUpdate: ({ editor: current }) => {
    const text = fromStructuredHtml(current.getHTML())
    structuredBody.value = text
    if (mode.value === 'structured') publish(text)
  },
})

watch(
  () => props.modelValue,
  (value) => {
    if (value === localBody.value) return
    localBody.value = value
    structuredBody.value = value
    if (!activeEditor.value)
      editor.value?.commands.setContent(toStructuredHtml(value))
  },
)

watch(
  () => props.disabled,
  (value) => editor.value?.setEditable(!value),
)

function publish(value: string): void {
  localBody.value = value
  emit('update:modelValue', value)
}

function toStructuredHtml(value: string): string {
  const escaped = value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
  return escaped
    .split(/\n\s*\n/)
    .map((part) => `<p>${part.replaceAll('\n', '<br>')}</p>`)
    .join('')
}

function fromStructuredHtml(value: string): string {
  const doc = new DOMParser().parseFromString(value, 'text/html')
  return Array.from(doc.body.children)
    .map((node) => node.textContent || '')
    .join('\n\n')
}

function switchMode(next: EditorMode): void {
  if (next === mode.value) return
  if (mode.value === 'structured') {
    structuredBody.value = localBody.value
    editor.value?.commands.setContent(toStructuredHtml(localBody.value))
  } else {
    publish(
      fromStructuredHtml(
        editor.value?.getHTML() ?? toStructuredHtml(localBody.value),
      ),
    )
  }
  mode.value = next
}

function insertToken(token: string): void {
  if (mode.value === 'structured' && editor.value) {
    editor.value.chain().focus().insertContent(token).run()
    return
  }
  const selection = codeView.value?.state.selection.main
  const start = selection?.from ?? localBody.value.length
  const end = selection?.to ?? start
  const next = `${localBody.value.slice(0, start)}${token}${localBody.value.slice(end)}`
  publish(next)
}

function insertRule(text: string): void {
  const current =
    mode.value === 'structured'
      ? fromStructuredHtml(editor.value?.getHTML() ?? '')
      : localBody.value
  const next = [current.trim(), text].filter(Boolean).join('\n\n')
  if (mode.value === 'structured' && editor.value) {
    editor.value.commands.setContent(toStructuredHtml(next))
  }
  publish(next)
}

function validateTemplate(value: string): Diagnostic[] {
  const issues: Diagnostic[] = []
  const supported = new Set(TOKENS.map((token) => token.value.slice(1, -1)))
  const pattern = /\{[^{}]*\}/g
  for (const match of value.matchAll(pattern)) {
    const name = match[0].slice(1, -1)
    const start = match.index
    if (!supported.has(name)) {
      issues.push({
        from: start,
        to: start + match[0].length,
        severity: 'error',
        message: `不支持的变量：${match[0]}`,
      })
    }
  }
  const open = value.indexOf('{')
  if (open >= 0 && value.indexOf('}', open) < 0) {
    issues.push({
      from: open,
      to: value.length,
      severity: 'error',
      message: '变量缺少右花括号。',
    })
  }
  if (!value.includes('{question}')) {
    issues.push({
      from: 0,
      to: 0,
      severity: 'warning',
      message: '模板未插入用户问题变量。',
    })
  }
  if (!value.includes('{references}')) {
    issues.push({
      from: 0,
      to: 0,
      severity: 'warning',
      message: '模板未插入引用列表变量。系统仍会执行引用规则。',
    })
  }
  return issues
}

const extensions = [
  javascript(),
  autocompletion({ override: [completeToken] }),
  linter((view) => validateTemplate(view.state.doc.toString())),
]

function completeToken(context: CompletionContext) {
  const word = context.matchBefore(/\{[a-zA-Z]*/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  return {
    from: word.from,
    options: TOKENS.map((token) => ({
      label: token.value,
      detail: token.label,
      type: 'variable',
    })),
  }
}

function handleCodeReady(payload: { view: EditorView }): void {
  codeView.value = payload.view
}

function onEditorFocus(): void {
  activeEditor.value = true
}

function onEditorBlur(): void {
  activeEditor.value = false
  publish(localBody.value)
}

defineExpose({
  validate: () => diagnostics.value,
  preview: (context: Record<string, string>) =>
    renderTemplate(localBody.value, context),
})

function renderTemplate(
  value: string,
  context: Record<string, string>,
): string {
  return value.replace(
    /\{([^{}]+)\}/g,
    (match, key: string) => context[key] ?? match,
  )
}
</script>

<template>
  <div class="template-editor">
    <div class="editor-toolbar">
      <FormRadioGroup
        :model-value="mode"
        :options="EDITOR_MODES"
        label="编辑模式"
        :disabled="disabled"
        @update:model-value="switchMode"
      />
      <div class="insert-control">
        <span>插入变量</span>
        <SelectMenu
          :options="
            TOKENS.map((token) => ({ label: token.label, value: token.value }))
          "
          placeholder="选择变量…"
          label="插入变量"
          :disabled="disabled"
          @update:model-value="insertToken($event)"
        />
      </div>
      <div v-if="mode === 'structured'" class="insert-control">
        <span>添加规则区块</span>
        <SelectMenu
          :options="
            RULE_BLOCKS.map((rule) => ({
              label: rule.title,
              value: rule.title,
              detail: rule.text,
            }))
          "
          placeholder="选择区块…"
          label="添加规则区块"
          :disabled="disabled"
          @update:model-value="
            insertRule(
              RULE_BLOCKS.find((rule) => rule.title === $event)?.text ?? '',
            )
          "
        />
      </div>
    </div>

    <div
      v-if="mode === 'structured'"
      class="structured-editor"
      @focusin="onEditorFocus"
      @focusout="onEditorBlur"
    >
      <EditorContent v-if="editor" :editor="editor" />
    </div>
    <Codemirror
      v-else
      v-model="localBody"
      class="template-code-editor"
      :extensions="extensions"
      :disabled="disabled"
      placeholder="输入回答模板；输入 { 可查看变量补全"
      @ready="handleCodeReady"
      @update:model-value="publish"
    />

    <div class="validation-panel" aria-live="polite">
      <div class="validation-head">
        <strong>格式检查</strong>
        <span
          :class="
            diagnostics.some((item) => item.severity === 'error')
              ? 'bk-badge bk-badge-danger'
              : diagnostics.length
                ? 'bk-badge bk-badge-review'
                : 'bk-badge bk-badge-ok'
          "
        >
          {{
            diagnostics.some((item) => item.severity === 'error')
              ? '存在错误'
              : diagnostics.length
                ? `${diagnostics.length} 条提示`
                : '格式正确'
          }}
        </span>
      </div>
      <ul v-if="diagnostics.length">
        <li
          v-for="(item, index) in diagnostics"
          :key="`${item.from}-${index}`"
          :class="item.severity"
        >
          {{ item.message }}
        </li>
      </ul>
      <p v-else class="bk-muted">变量格式和支持范围检查通过。</p>
    </div>
  </div>
</template>

<style>
.template-editor {
  display: grid;
  min-width: 0;
  max-width: 100%;
  gap: var(--space-3);
}

.editor-toolbar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  min-width: 0;
  max-width: 100%;
  gap: var(--space-3);
}

.insert-control {
  display: grid;
  gap: 4px;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.structured-editor,
.template-code-editor {
  min-width: 0;
  max-width: 100%;
  min-height: 240px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  overflow: hidden;
}

.structured-editor .tiptap {
  min-height: 220px;
  padding: var(--space-4);
  outline: none;
  font: inherit;
  line-height: 1.65;
}

.structured-editor .tiptap p {
  margin: 0 0 var(--space-3);
}

.structured-editor .tiptap p.is-editor-empty:first-child::before {
  color: var(--color-text-muted);
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}

.template-code-editor .cm-editor {
  max-width: 100%;
  min-height: 240px;
  font-size: var(--text-sm);
}

.template-code-editor .cm-scroller {
  overflow: auto;
}

/* 高级文本模式：正文两侧与上下留白，避免文字紧贴边框。 */
.template-code-editor .cm-content {
  padding: var(--space-4);
}

.validation-panel {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-3);
  background: var(--color-canvas);
}

.validation-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.validation-panel ul {
  margin: var(--space-2) 0 0;
  padding-inline-start: var(--space-5);
}

.validation-panel li {
  margin-top: 4px;
  font-size: var(--text-xs);
}

.validation-panel li.error {
  color: var(--color-text-danger);
}

.validation-panel li.warning {
  color: var(--color-text-warning);
}
</style>
