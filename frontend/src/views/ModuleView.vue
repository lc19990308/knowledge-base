<script setup lang="ts">
import { RouterLink } from 'vue-router'

const { title, message = '此模块尚未实现。' } = defineProps<{
  title: string
  message?: string
}>()
</script>

<template>
  <div class="module">
    <h1>{{ title }}</h1>
    <p>{{ message }}</p>
    <RouterLink to="/">返回工作台</RouterLink>
  </div>
</template>

<style scoped>
.module {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
}

h1 {
  font-size: var(--text-title);
}

p {
  color: var(--color-text-secondary);
}
</style>
