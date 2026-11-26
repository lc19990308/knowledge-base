<script setup lang="ts">
defineProps<{ title: string; description?: string; compact?: boolean }>()
</script>

<template>
  <div class="page-head" :class="{ compact }">
    <div>
      <h2>{{ title }}</h2>
      <p v-if="description" class="sub">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.compact h2 {
  font-size: var(--text-lg);
  font-weight: 700;
}
</style>
