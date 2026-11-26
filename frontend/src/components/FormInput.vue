<script setup lang="ts" generic="T extends string | number = string">
import type { InputHTMLAttributes } from 'vue'

interface Props extends /* @vue-ignore */ Omit<
  InputHTMLAttributes,
  'type' | 'value' | 'checked'
> {
  type?: 'text' | 'search' | 'date' | 'password' | 'url' | 'number'
  variant?: 'standard' | 'embedded'
}

withDefaults(defineProps<Props>(), { type: 'text', variant: 'standard' })
const model = defineModel<T>({ required: true })
</script>

<template>
  <input
    v-model="model"
    :type="type"
    class="bk-input"
    :class="{ 'bk-input-embedded': variant === 'embedded' }"
  />
</template>
