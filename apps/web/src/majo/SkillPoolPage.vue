<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'

interface PoolSkill { name: string; description: string; tags: string[]; installed_from: string; auto_update: boolean }
interface Builtin { skill_name: string }
const items = ref<PoolSkill[]>([]); const builtins = ref<Builtin[]>([]); const loading = ref(false); const importing = ref(false); const error = ref(''); const notice = ref('')
async function refresh(){loading.value=true;error.value='';try{items.value=await apiRequest<PoolSkill[]>('/skills/pool',{},workbench.agentId)}catch(cause){error.value=cause instanceof Error?cause.message:'Could not load the skill pool.'}finally{loading.value=false}}
async function download(item:PoolSkill){error.value='';try{const result=await apiRequest<{success:boolean;reason?:string}>('/skills/pool/download',{method:'POST',body:JSON.stringify({skill_name:item.name})},workbench.agentId);if(!result.success)throw new Error(result.reason==='conflict'?'This workspace already has a skill with this name.':'Could not download skill.');notice.value=`Added ${item.name} to this workspace.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not download skill.'}}
async function broadcast(item:PoolSkill){if(!window.confirm(`Add ${item.name} to every agent workspace?`))return;error.value='';try{const result=await apiRequest<{results:Array<{success:boolean}>}>('/skills/pool/download',{method:'POST',body:JSON.stringify({skill_name:item.name,all_workspaces:true})},workbench.agentId);const failed=result.results.filter(entry=>!entry.success).length;notice.value=failed?`Added ${item.name}; ${failed} workspace${failed===1?'':'s'} failed.`:`Added ${item.name} to ${result.results.length} workspaces.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not broadcast skill.'}}
async function remove(item:PoolSkill){try{await apiRequest(`/skills/pool/${encodeURIComponent(item.name)}`,{method:'DELETE'},workbench.agentId);await refresh();notice.value=`Deleted ${item.name} from the pool.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not delete skill.'}}
async function toggleAutoUpdate(item:PoolSkill){error.value='';try{const enabled=!item.auto_update;const result=await apiRequest<{synced:string[];failed:string[]}>(`/skills/pool/${encodeURIComponent(item.name)}/auto-update`,{method:'PUT',body:JSON.stringify({enabled,targets:enabled?[workbench.agentId]:[]})},workbench.agentId);await refresh();notice.value=enabled?`Auto-update enabled${result.failed.length?`; ${result.failed.length} target failed.`:'.'}`:'Auto-update disabled.'}catch(cause){error.value=cause instanceof Error?cause.message:'Could not update synchronization.'}}
async function importBuiltins(){importing.value=true;error.value='';try{const result=await apiRequest<{imported:string[];conflicts:string[]}>('/skills/pool/import-builtin',{method:'POST',body:JSON.stringify({skill_names:builtins.value.map(item=>item.skill_name)})},workbench.agentId);await refresh();notice.value=`Imported ${result.imported.length} built-in skill${result.imported.length===1?'':'s'}.`}catch(cause){error.value=cause instanceof Error?cause.message:'Could not import built-in skills.'}finally{importing.value=false}}
onMounted(async()=>{await refresh();try{builtins.value=await apiRequest<Builtin[]>('/skills/pool/builtin-sources',{},workbench.agentId)}catch{builtins.value=[]}})
</script>
<template>
  <section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">SHARED LIBRARY</p><h1>Skill Pool</h1></div><button @click="refresh">Refresh</button></header>
    <p v-if="error" class="inline-error">{{error}}</p><p v-if="notice" class="success-notice">{{notice}}</p>
    <section v-if="builtins.length" class="settings-card"><div class="skills-editor-heading"><div><p class="eyebrow">MAJO BUILT-INS</p><h2>English skill library</h2></div><button :disabled="importing" @click="importBuiltins">{{importing?'Importing…':'Import all'}}</button></div><p class="muted">{{builtins.length}} packaged skills are available for the shared Pool.</p></section>
    <section class="settings-card"><p v-if="loading">Loading skills…</p><p v-else-if="!items.length" class="muted">No shared skills yet. Upload a workspace skill from the Skills page.</p>
      <div v-for="item in items" :key="item.name" class="skills-item"><span><strong>{{item.name}}</strong><small>{{item.description || 'No description'}}<template v-if="item.tags?.length"> · {{item.tags.join(', ')}}</template></small></span><span><button @click="download(item)">Add to workspace</button><button @click="broadcast(item)">Add to all workspaces</button><button @click="toggleAutoUpdate(item)">{{item.auto_update?'Disable sync':'Auto-update this agent'}}</button><button @click="remove(item)">Delete</button></span></div>
    </section>
  </section>
</template>
