<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
type App={id:string;name:string;version:string;description:string;category:string;status:string}
const apps=ref<App[]>([]),error=ref(''),loading=ref(false)
async function refresh(){loading.value=true;try{apps.value=(await apiRequest<{apps:App[]}>('/pawapps')).apps;error.value=''}catch(cause){error.value=cause instanceof Error?cause.message:'Could not load apps.'}finally{loading.value=false}}
async function remove(app:App){if(!window.confirm(`Uninstall ${app.name}?`))return;try{await apiRequest(`/pawapps/${encodeURIComponent(app.id)}`,{method:'DELETE'});await refresh()}catch(cause){error.value=cause instanceof Error?cause.message:'Could not uninstall app.'}}
onMounted(()=>void refresh())
</script>
<template><section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">APP CENTER</p><h1>Apps</h1></div><button @click="refresh">Refresh</button></header><p v-if="error" class="inline-error">{{error}}</p><div class="settings-card"><p v-if="loading">Loading apps…</p><p v-else-if="!apps.length">No apps installed.</p><div v-for="app in apps" :key="app.id" class="skills-item"><span><strong>{{app.name}}</strong><small>{{app.category||'App'}} · {{app.version}} · {{app.description}}</small></span><button @click="remove(app)">Uninstall</button></div></div></section></template>
