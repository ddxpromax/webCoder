<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pluginRoutes, type PluginRoute } from './pluginRuntime'
import { workbench } from './state'

const route = useRoute(), router = useRouter(), root = ref<HTMLElement | null>(null), error = ref('')
let dispose: (() => void) | void
function current(): PluginRoute | undefined { return pluginRoutes.find(plugin => plugin.path === route.path) }
async function render() {
  dispose?.(); error.value = ''
  const plugin = current()
  await nextTick()
  if (!plugin || !root.value) { error.value = 'This plugin page is no longer available.'; return }
  try { dispose = plugin.render(root.value, { agentId: workbench.agentId, api: (path, options = {}) => fetch(`/api${path}`, { ...options, headers: { ...(options.headers ?? {}), 'X-Agent-Id': workbench.agentId } }), navigate: path => void router.push(path) }) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'The plugin page could not start.' }
}
watch(() => route.fullPath, () => void render())
onMounted(() => void render())
onBeforeUnmount(() => dispose?.())
</script>
<template><section class="settings-page plugin-host-page"><p v-if="error" class="inline-error" role="alert">{{ error }}</p><div ref="root" class="plugin-host" /></section></template>
