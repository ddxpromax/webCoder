<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
const error=ref(''),notice=ref('')
const toolGuard=ref({enabled:true,denied_tools:[] as string[]})
const fileGuard=ref({enabled:true,paths:[] as string[]})
const sandbox=ref(false)
async function refresh(){try{const [tools,files,box]=await Promise.all([apiRequest<typeof toolGuard.value>('/config/security/tool-guard'),apiRequest<typeof fileGuard.value>('/config/security/file-guard'),apiRequest<{enabled:boolean}>('/config/security/sandbox')]);toolGuard.value=tools;fileGuard.value=files;sandbox.value=box.enabled;error.value=''}catch(cause){error.value=cause instanceof Error?cause.message:'Could not load security policies.'}}
async function save(){try{await Promise.all([apiRequest('/config/security/tool-guard',{method:'PUT',body:JSON.stringify(toolGuard.value)}),apiRequest('/config/security/file-guard',{method:'PUT',body:JSON.stringify(fileGuard.value)}),apiRequest('/config/security/sandbox',{method:'PUT',body:JSON.stringify({enabled:sandbox.value})})]);notice.value='Security policies saved.'}catch(cause){error.value=cause instanceof Error?cause.message:'Could not save security policies.'}}
function split(value:string){return value.split('\n').map(item=>item.trim()).filter(Boolean)}
onMounted(()=>void refresh())
</script>
<template><section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">SECURITY</p><h1>Security policies</h1></div></header><p v-if="error" class="inline-error">{{error}}</p><p v-if="notice" class="success-notice">{{notice}}</p><form class="stacked-form settings-card narrow-card" @submit.prevent="save"><label class="skills-checkbox"><input v-model="toolGuard.enabled" type="checkbox"> Enable tool guard</label><label>Denied tool names, one per line<textarea :value="toolGuard.denied_tools.join('\n')" @input="toolGuard.denied_tools=split(($event.target as HTMLTextAreaElement).value)" /></label><label class="skills-checkbox"><input v-model="fileGuard.enabled" type="checkbox"> Enable file guard</label><label>Protected paths, one per line<textarea :value="fileGuard.paths.join('\n')" @input="fileGuard.paths=split(($event.target as HTMLTextAreaElement).value)" /></label><label class="skills-checkbox"><input v-model="sandbox" type="checkbox"> Enable sandbox policy</label><button class="primary" type="submit">Save security policies</button></form></section></template>
