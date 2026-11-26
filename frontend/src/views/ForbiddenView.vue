<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="forbidden">
    <div class="bk-card forbid-card">
      <p class="mark">⛔</p>
      <h1>无权访问该页面</h1>
      <p class="bk-text-sm bk-muted">仅系统管理员可访问。</p>
      <RouterLink class="bk-btn bk-btn-primary" to="/">返回工作台</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.forbidden {
  display: grid;
  place-items: center;
  min-height: 60vh;
}

.forbid-card {
  display: grid;
  gap: var(--space-3);
  justify-items: center;
  text-align: center;
  padding: var(--space-8);
  max-width: 380px;
}

.forbid-card h1 {
  font-size: var(--text-title);
  font-weight: 700;
}

.mark {
  font-size: 34px;
  margin: 0;
}
</style>
