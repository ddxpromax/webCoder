<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'
type Node={commit:string;name:string;kind:string;timestamp_ms:number}
const nodes=ref<Node[]>([]);const error=ref('')
async function refresh(){try{nodes.value=(await apiRequest<{nodes:Node[]}>('/workspace/checkpoints/graph',{},workbench.agentId)).nodes}catch(e){error.value=e instanceof Error?e.message:'Could not load checkpoints.'}}
async function snapshot(){try{await apiRequest('/workspace/checkpoints/snapshot',{method:'POST',body:JSON.stringify({session_id:`manual-${Date.now()}`,channel:'console',name:'manual snapshot'})},workbench.agentId);await refresh()}catch(e){error.value=e instanceof Error?e.message:'Could not create snapshot.'}}
onMounted(()=>void refresh())
</script>
<template><section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">WORKSPACE SAFETY</p><h1>Checkpoints</h1></div><button @click="snapshot">Create snapshot</button></header><p v-if="error" class="inline-error">{{error}}</p><div class="settings-card"><p v-if="!nodes.length">No checkpoints.</p><div v-for="node in nodes" :key="node.commit" class="skills-item"><span><strong>{{node.name}}</strong><small>{{node.kind}} · {{new Date(node.timestamp_ms).toLocaleString()}}</small></span><code>{{node.commit.slice(0,12)}}</code></div></div></section></template>
