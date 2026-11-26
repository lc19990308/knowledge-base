<script setup lang="ts">
/**
 * 公共复选框：原生 checkbox 语义 + 统一的方框外观。
 *
 * 页面里不允许直接写 `<input>`（eslint 限制），多选表格与全选都需要它；
 * 支持 `indeterminate` 的半选状态，供「本页全选」在部分选中时展示。
 */
const model = defineModel<boolean>({ required: true })

withDefaults(
  defineProps<{
    /** 可读文本；也可以直接用默认插槽。 */
    label?: string
    /** 纯图标场景（表头全选）用它给原生 checkbox 提供可读名称。 */
    accessibleName?: string
    disabled?: boolean
    /** 半选：只表达视觉与语义，不改变 modelValue。 */
    indeterminate?: boolean
  }>(),
  { label: '', accessibleName: '', disabled: false, indeterminate: false },
)
</script>

<template>
  <label class="bk-check" :class="{ 'is-disabled': disabled }">
    <input
      v-model="model"
      class="visually-hidden bk-check-input"
      type="checkbox"
      :disabled="disabled"
      :indeterminate="indeterminate"
      :aria-label="accessibleName"
    />
    <span class="bk-check-box" aria-hidden="true"></span>
    <span v-if="label !== '' || $slots.default" class="bk-check-label">
      <slot>{{ label }}</slot>
    </span>
  </label>
</template>

<style scoped>
.bk-check {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  user-select: none;
}

.bk-check.is-disabled {
  cursor: not-allowed;
  opacity: var(--form-disabled-opacity);
}

.bk-check-box {
  position: relative;
  flex: none;
  width: 16px;
  height: 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  transition:
    background var(--motion-fast) var(--ease-standard),
    border-color var(--motion-fast) var(--ease-standard),
    box-shadow var(--motion-fast) var(--ease-standard);
}

.bk-check:hover:not(.is-disabled) .bk-check-box {
  border-color: color-mix(in oklab, var(--color-border), var(--color-text) 24%);
}

.bk-check-input:focus-visible + .bk-check-box {
  box-shadow: var(--shadow-focus);
}

.bk-check-input:checked + .bk-check-box,
.bk-check-input:indeterminate + .bk-check-box {
  background: var(--color-action);
  border-color: var(--color-action);
}

/* 对勾用两条边框旋转而成，避免额外引入图标依赖。 */
.bk-check-input:checked + .bk-check-box::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 4px;
  height: 8px;
  margin: auto;
  border: solid var(--color-action-text);
  border-width: 0 2px 2px 0;
  transform: translateY(-1px) rotate(45deg);
}

/* 半选画一条横线，与全选的勾区分开。 */
.bk-check-input:indeterminate + .bk-check-box::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 8px;
  height: 2px;
  margin: auto;
  background: var(--color-action-text);
}

.bk-check-label {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (prefers-reduced-motion: reduce) {
  .bk-check-box {
    transition: none;
  }
}
</style>
