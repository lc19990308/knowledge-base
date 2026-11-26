<script setup lang="ts">
import { onMounted, useTemplateRef } from 'vue'

const props = withDefaults(
  defineProps<{
    accept?: string
    multiple?: boolean
    /** 选择整个文件夹（`webkitdirectory`）：选择结果会带相对路径。 */
    directory?: boolean
    disabled?: boolean
    /** 次级操作（例如行内「更新」）用浅色按钮，避免与主操作抢视觉。 */
    secondary?: boolean
  }>(),
  {
    accept: '',
    multiple: false,
    directory: false,
    disabled: false,
    secondary: false,
  },
)
const emit = defineEmits<{ change: [files: File[], paths: string[]] }>()
const input = useTemplateRef<HTMLInputElement>('input')

/**
 * `webkitdirectory` 是 Chromium 专有属性，Vue 的输入元素类型里没有它。
 * 直接写模板属性会被当成普通 attribute，这里在挂载后显式设置，保证文件夹选择生效。
 */
onMounted(() => {
  if (props.directory) input.value?.setAttribute('webkitdirectory', '')
})

/**
 * 读取用户选择的文件。
 *
 * 文件夹上传时同时给出相对路径（`webkitRelativePath`），后端据此还原层级；
 * 选完清空 input 的值，否则再次选择同一个文件/文件夹不会触发 change。
 */
function selectFiles(event: Event): void {
  const input = event.currentTarget
  if (!(input instanceof HTMLInputElement) || !input.files) return
  const files = Array.from(input.files)
  emit(
    'change',
    files,
    files.map((file) => file.webkitRelativePath || file.name),
  )
  input.value = ''
}
</script>

<template>
  <label
    class="bk-btn bk-file-picker"
    :class="{
      'bk-btn-primary': !secondary,
      'bk-btn-neutral': secondary,
      'is-secondary': secondary,
      'is-disabled': disabled,
    }"
  >
    <slot />
    <input
      ref="input"
      class="bk-file-input"
      type="file"
      :accept="accept"
      :multiple="multiple || directory"
      :disabled="disabled"
      @change="selectFiles"
    />
  </label>
</template>
