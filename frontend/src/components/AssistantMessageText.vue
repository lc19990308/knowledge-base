<script setup lang="ts">
import { computed, h, type VNode } from 'vue'

const props = defineProps<{
  text: string
  plain?: boolean
}>()

type MessageBlock = { line: number } & (
  | { kind: 'heading'; tag: string; text: string }
  | { kind: 'paragraph' | 'code'; text: string }
  | {
      kind: 'list'
      tag: 'ol' | 'ul'
      start: number | undefined
      items: string[]
    }
  | { kind: 'table'; headers: string[]; rows: string[][] }
)

const heading = /^ {0,3}(#{1,6})[\t ]+(.*)$/
const fence = /^ {0,3}(`{3,}|~{3,})(.*)$/
const fenceClose = /^ {0,3}(`{3,}|~{3,})[\t ]*$/
const listItem = /^ {0,3}(?:([-+*])|(\d{1,9})[.)])[\t ]+(.*)$/
const tableSeparator = /^:?-{3,}:?$/

function tableCells(line: string): string[] | undefined {
  const row = line.trim()
  const cells = row.split(/(?<!\\)\|/)
  if (cells.length === 1) return undefined
  if (cells[0] === '') cells.shift()
  if (cells[cells.length - 1] === '') cells.pop()
  return cells.map((cell) => cell.trim().replaceAll('\\|', '|'))
}

function tableHeader(line: string, next: string): string[] | undefined {
  const headers = tableCells(line)
  const separator = tableCells(next)
  return headers?.length &&
    separator?.length === headers.length &&
    separator.every((cell) => tableSeparator.test(cell))
    ? headers
    : undefined
}

function parseBlocks(text: string): MessageBlock[] {
  const lines = text.split(/\r\n?|\n/)
  const blocks: MessageBlock[] = []
  let index = 0

  while (index < lines.length) {
    const line = index
    const current = lines[index++]
    if (!current?.trim()) continue

    const openingFence = fence.exec(current)?.[1]
    if (openingFence) {
      const contentStart = index
      while (index < lines.length) {
        const closingFence = fenceClose.exec(lines[index] ?? '')?.[1]
        if (closingFence?.startsWith(openingFence)) break
        index++
      }
      blocks.push({
        line,
        kind: 'code',
        text: lines.slice(contentStart, index).join('\n'),
      })
      if (index < lines.length) index++
      continue
    }

    const title = heading.exec(current)
    if (title?.[1] && title[2] !== undefined) {
      blocks.push({
        line,
        kind: 'heading',
        tag: `h${String(title[1].length)}`,
        text: title[2].replace(/[\t ]+#+[\t ]*$/, ''),
      })
      continue
    }

    const item = listItem.exec(current)
    if (item) {
      const ordered = item[2] !== undefined
      const items = [item[3] ?? '']
      while (index < lines.length) {
        const nextItem = listItem.exec(lines[index] ?? '')
        if (!nextItem || (nextItem[2] !== undefined) !== ordered) break
        items.push(nextItem[3] ?? '')
        index++
      }
      blocks.push({
        line,
        kind: 'list',
        tag: ordered ? 'ol' : 'ul',
        start: ordered ? Number(item[2]) : undefined,
        items,
      })
      continue
    }

    const headers = tableHeader(current, lines[index] ?? '')
    if (headers) {
      index++
      const rows: string[][] = []
      while (index < lines.length) {
        const cells = tableCells(lines[index] ?? '')
        if (!cells?.length || cells.length > headers.length) break
        rows.push(headers.map((_, column) => cells[column] ?? ''))
        index++
      }
      blocks.push({ line, kind: 'table', headers, rows })
      continue
    }

    while (index < lines.length) {
      const next = lines[index] ?? ''
      if (
        !next.trim() ||
        heading.test(next) ||
        fence.test(next) ||
        listItem.test(next) ||
        tableHeader(next, lines[index + 1] ?? '')
      ) {
        break
      }
      index++
    }
    blocks.push({
      line,
      kind: 'paragraph',
      text: lines.slice(line, index).join('\n'),
    })
  }

  return blocks
}

// Only these fixed tags are emitted; every piece of message text is a text node.
function InlineText({ text }: { text: string }) {
  const nodes: (string | VNode)[] = []
  let offset = 0
  for (const match of text.matchAll(/`([^`\n]+)`|\*\*([^*\n]+)\*\*/g)) {
    nodes.push(text.slice(offset, match.index))
    nodes.push(
      match[1] === undefined ? h('strong', match[2]) : h('code', match[1]),
    )
    offset = match.index + match[0].length
  }
  nodes.push(text.slice(offset))
  return nodes
}

const blocks = computed(() => (props.plain ? [] : parseBlocks(props.text)))
</script>

<template>
  <div v-if="plain" class="assistant-message-text message-prose">
    {{ text }}
  </div>
  <div v-else class="assistant-message-text">
    <template v-for="block in blocks" :key="block.line">
      <component
        :is="block.tag"
        v-if="block.kind === 'heading'"
        class="message-heading"
      >
        <InlineText :text="block.text" />
      </component>
      <p v-else-if="block.kind === 'paragraph'" class="message-prose">
        <InlineText :text="block.text" />
      </p>
      <pre
        v-else-if="block.kind === 'code'"
        class="message-code"
      ><code>{{ block.text }}</code></pre>
      <component
        :is="block.tag"
        v-else-if="block.kind === 'list'"
        :start="block.start"
        class="message-list"
      >
        <li v-for="(item, index) in block.items" :key="index">
          <InlineText :text="item" />
        </li>
      </component>
      <div v-else-if="block.kind === 'table'" class="message-table">
        <table>
          <thead>
            <tr>
              <th
                v-for="(header, index) in block.headers"
                :key="index"
                scope="col"
              >
                <InlineText :text="header" />
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in block.rows" :key="index">
              <td v-for="(cell, column) in row" :key="column">
                <InlineText :text="cell" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.assistant-message-text {
  min-inline-size: 0;
  overflow-wrap: anywhere;
}

.assistant-message-text > * {
  margin: 0;
}

.assistant-message-text > * + * {
  margin-block-start: var(--space-2);
}

.message-prose {
  white-space: pre-wrap;
}

.message-heading {
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}

h1.message-heading,
h2.message-heading {
  font-size: var(--text-base);
}

.message-list {
  padding-inline-start: var(--space-5);
}

.message-code,
.message-table {
  max-inline-size: 100%;
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.message-code {
  padding: var(--space-2) var(--space-3);
  background: var(--color-canvas);
  white-space: pre;
  overflow-wrap: normal;
}

.assistant-message-text :deep(code) {
  font-family: var(--font-mono);
}

.assistant-message-text :not(pre) > :deep(code) {
  padding-inline: var(--space-1);
  border-radius: var(--radius-sm);
  background: var(--color-canvas);
}

.message-table table {
  inline-size: 100%;
  border-collapse: collapse;
  text-align: start;
}

.message-table th,
.message-table td {
  padding: var(--space-2) var(--space-3);
  text-align: start;
  vertical-align: top;
}

.message-table th {
  background: var(--color-canvas);
}

.message-table td {
  border-block-start: 1px solid var(--color-border);
}
</style>
