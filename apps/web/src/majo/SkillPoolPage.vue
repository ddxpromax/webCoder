<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'

interface PoolSkill { name: string; description: string; tags: string[]; installed_from: string }
const items = ref<PoolSkill[]>([]); const loading = ref(false); const error = ref(''); const notice = ref('')
async function refresh(){loading.value=true;error.value='';try{items.value=await apiRequest<PoolSkill[]>('/skills/pool',{},workbench.agentId)}catch(cause){error.value=cause instanceof Error?cause.message:'Could not load the skill pool.'}finally{loading.value=false}}
async function download(item:PoolSkill){error.value='';try{const result=await apiRequest<{success:boolean;reason?:string}>('/skills/pool/download',{method:'POST',body:JSON.stringify({skill_name:item.name})},workbench.agentId);if(!result.success)throw new Error(result.reason==='conflict'?'This workspace already has a skill with this name.':'Could not download skill.');notice.value=`Added ${item.name} to this workspace.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not download skill.'}}
async function remove(item:PoolSkill){try{await apiRequest(`/skills/pool/${encodeURIComponent(item.name)}`,{method:'DELETE'},workbench.agentId);await refresh();notice.value=`Deleted ${item.name} from the pool.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not delete skill.'}}
onMounted(()=>void refresh())
</script>
<template>
  <section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">SHARED LIBRARY</p><h1>Skill Pool</h1></div><button @click="refresh">Refresh</button></header>
    <p v-if="error" class="inline-error">{{error}}</p><p v-if="notice" class="success-notice">{{notice}}</p>
    <section class="settings-card"><p v-if="loading">Loading skills…</p><p v-else-if="!items.length" class="muted">No shared skills yet. Upload a workspace skill from the Skills page.</p>
      <div v-for="item in items" :key="item.name" class="skills-item"><span><strong>{{item.name}}</strong><small>{{item.description || 'No description'}}<template v-if="item.tags?.length"> · {{item.tags.join(', ')}}</template></small></span><span><button @click="download(item)">Add to workspace</button><button @click="remove(item)">Delete</button></span></div>
    </section>
  </section>
</template>
