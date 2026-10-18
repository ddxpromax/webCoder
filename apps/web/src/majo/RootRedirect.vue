<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { apiRequest } from './api'

const router = useRouter()
onMounted(async () => {
  try {
    const mode = await apiRequest<{ enabled: boolean }>('/coding-mode')
    await router.replace(mode.enabled ? '/coding' : '/chat')
  } catch { await router.replace('/chat') }
})
</script>

<template><div class="root-redirect">Loading workspace…</div></template>
