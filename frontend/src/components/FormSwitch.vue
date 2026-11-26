<script setup lang="ts">
import type { InputHTMLAttributes } from 'vue'

interface Props extends /* @vue-ignore */ Omit<
  InputHTMLAttributes,
  'type' | 'value' | 'checked'
> {
  id?: string
  disabled?: boolean
}

defineOptions({ inheritAttrs: false })
defineProps<Props>()
const model = defineModel<boolean>({ required: true })
</script>

<template>
  <span class="bk-switch" :class="$attrs.class">
    <input
      v-bind="{ ...$attrs, class: undefined }"
      :id="id"
      v-model="model"
      :disabled="disabled"
      type="checkbox"
      role="switch"
    />
    <span class="track" aria-hidden="true"></span>
  </span>
</template>
