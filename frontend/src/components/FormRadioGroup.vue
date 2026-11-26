<script setup lang="ts" generic="T extends string">
import { useId } from 'vue'

const props = defineProps<{
  modelValue?: T
  options: readonly { label: string; value: NoInfer<T>; hint?: string }[]
  label?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()

/** 每组的原生 name 必须唯一，否则同页多组单选会互相串选。 */
const groupName = useId()

function select(value: T): void {
  if (props.disabled || value === props.modelValue) return
  emit('update:modelValue', value)
}
</script>

<template>
  <fieldset class="bk-radio-group" :disabled="disabled">
    <legend v-if="label">{{ label }}</legend>
    <div class="bk-radio-options">
      <label
        v-for="option in options"
        :key="option.value"
        class="bk-radio-option"
        :class="{ 'is-active': modelValue === option.value }"
      >
        <input
          class="visually-hidden bk-radio-input"
          type="radio"
          :name="groupName"
          :value="option.value"
          :checked="modelValue === option.value"
          :disabled="disabled"
          @change="select(option.value)"
        />
        <span class="bk-radio-dot" aria-hidden="true"></span>
        <span class="bk-radio-text">
          <span class="bk-radio-label">{{ option.label }}</span>
          <span v-if="option.hint" class="bk-radio-hint">{{
            option.hint
          }}</span>
        </span>
      </label>
    </div>
  </fieldset>
</template>
