<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
type Backup={id:string;name:string;created_at:string;agent_count:number}
const backups=ref<Backup[]>([]);const error=ref('');const loading=ref(false)
async function refresh(){loading.value=true;try{backups.value=await apiRequest<Backup[]>('/backups')}catch(e){error.value=e instanceof Error?e.message:'Could not load backups.'}finally{loading.value=false}}
async function create(){try{await apiRequest('/backups/jobs',{method:'POST',body:JSON.stringify({name:'Workspace backup',scope:{include_agents:true,include_global_config:true}})});await refresh()}catch(e){error.value=e instanceof Error?e.message:'Could not start backup.'}}
async function remove(id:string){await apiRequest('/backups/delete',{method:'POST',body:JSON.stringify({ids:[id]})});await refresh()}
onMounted(()=>void refresh())
</script>
<template><section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">DATA</p><h1>Backups</h1></div><button @click="create">Create backup</button></header><p v-if="error" class="inline-error">{{ error }}</p><div class="settings-card"><p v-if="loading">Loading backups…</p><p v-else-if="!backups.length">No backups yet.</p><div v-for="backup in backups" :key="backup.id" class="skills-item"><span><strong>{{ backup.name || backup.id }}</strong><small>{{ backup.created_at }} · {{ backup.agent_count }} agents</small></span><span><a :href="`/api/backups/${encodeURIComponent(backup.id)}/export`">Export</a><button @click="remove(backup.id)">Delete</button></span></div></div></section></template>
