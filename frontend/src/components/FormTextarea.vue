<script setup lang="ts">
import type { TextareaHTMLAttributes } from 'vue'

interface Props extends /* @vue-ignore */ Omit<
  TextareaHTMLAttributes,
  'value'
> {
  compact?: boolean
}

defineProps<Props>()
const model = defineModel<string>({ required: true })
</script>

<template>
  <textarea
    v-model="model"
    class="bk-textarea"
    :class="{ 'bk-textarea-compact': compact }"
  ></textarea>
</template>
