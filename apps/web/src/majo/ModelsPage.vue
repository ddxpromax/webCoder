<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'

interface Model {
  id: string
  name: string
  hidden?: boolean
}
interface Provider {
  id: string
  name: string
  base_url?: string
  models?: Model[]
  extra_models?: Model[]
  is_custom?: boolean
  support_connection_check?: boolean
  support_model_discovery?: boolean
}
interface ActiveModel {
  active_llm: { provider_id: string; model: string } | null
}

const providers = ref<Provider[]>([])
const selectedId = ref('')
const apiKey = ref('')
const baseUrl = ref('')
const customName = ref('')
const active = ref<ActiveModel>({ active_llm: null })
const loading = ref(false)
const saving = ref(false)
const notice = ref('')
const error = ref('')
const selected = computed(() => providers.value.find((item) => item.id === selectedId.value))
const visibleModels = computed(() =>
  [...(selected.value?.models || []), ...(selected.value?.extra_models || [])].filter(
    (model) => !model.hidden,
  ),
)

async function refresh() {
  loading.value = true
  try {
    const [list, current] = await Promise.all([
      apiRequest<Provider[]>('/models'),
      apiRequest<ActiveModel>('/models/active?scope=global', {}, workbench.agentId),
    ])
    providers.value = list
    active.value = current
    if (!list.some((provider) => provider.id === selectedId.value))
      selectedId.value = list[0]?.id || ''
    choose(selectedId.value)
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load models.'
  } finally {
    loading.value = false
  }
}

function choose(id: string) {
  selectedId.value = id
  baseUrl.value = providers.value.find((provider) => provider.id === id)?.base_url || ''
  apiKey.value = ''
  notice.value = ''
}

async function saveProvider() {
  if (!selected.value) return
  saving.value = true
  error.value = ''
  try {
    await apiRequest(`/models/${encodeURIComponent(selectedId.value)}/config`, {
      method: 'PUT',
      body: JSON.stringify({
        base_url: baseUrl.value,
        ...(apiKey.value ? { api_key: apiKey.value } : {}),
      }),
    })
    apiKey.value = ''
    notice.value = 'Provider saved.'
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save provider.'
  } finally {
    saving.value = false
  }
}

async function useModel(model: Model) {
  error.value = ''
  try {
    active.value = await apiRequest<ActiveModel>('/models/active', {
      method: 'PUT',
      body: JSON.stringify({ provider_id: selectedId.value, model: model.id, scope: 'global' }),
    })
    notice.value = `${model.name} is now active.`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not activate model.'
  }
}

async function createProvider() {
  if (!customName.value.trim()) return
  error.value = ''
  try {
    const created = await apiRequest<Provider>('/models/custom-providers', {
      method: 'POST',
      body: JSON.stringify({ name: customName.value.trim() }),
    })
    customName.value = ''
    await refresh()
    choose(created.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not create provider.'
  }
}

async function discover() {
  if (!selected.value) return
  error.value = ''
  try {
    await apiRequest(`/models/${encodeURIComponent(selectedId.value)}/discover`, { method: 'POST' })
    notice.value = 'Model discovery complete.'
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Model discovery failed.'
  }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">SETTINGS</p>
        <h1>Models</h1>
      </div>
      <button @click="refresh">Refresh</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="loading" class="muted">Loading providers…</p>
    <div class="settings-grid">
      <div class="settings-card">
        <h2>Providers</h2>
        <button
          v-for="provider in providers"
          :key="provider.id"
          class="provider-row"
          :class="{ selected: selectedId === provider.id }"
          @click="choose(provider.id)"
        >
          {{ provider.name }}
        </button>
        <form class="stacked-form" @submit.prevent="createProvider">
          <h3>Add custom provider</h3>
          <input
            v-model="customName"
            aria-label="Provider name"
            placeholder="Provider name"
            required
          />
          <button type="submit">Add provider</button>
        </form>
      </div>
      <div v-if="selected" class="settings-card">
        <h2>{{ selected.name }}</h2>
        <form class="stacked-form" @submit.prevent="saveProvider">
          <label
            >Base URL <input v-model="baseUrl" type="url" placeholder="https://api.example.com/v1"
          /></label>
          <label
            >API key
            <input
              v-model="apiKey"
              type="password"
              autocomplete="off"
              placeholder="Leave blank to keep the saved key"
          /></label>
          <button type="submit" class="primary" :disabled="saving">
            {{ saving ? 'Saving…' : 'Save provider' }}
          </button>
        </form>
        <div class="model-list-header">
          <h3>Models</h3>
          <button v-if="selected.support_model_discovery" @click="discover">Discover</button>
        </div>
        <div class="data-list">
          <div v-for="model in visibleModels" :key="model.id" class="data-row">
            <div class="data-row-main">
              <strong>{{ model.name || model.id }}</strong
              ><small>{{ model.id }}</small>
            </div>
            <span
              v-if="
                active.active_llm?.provider_id === selectedId &&
                active.active_llm.model === model.id
              "
              class="active-badge"
              >Active</span
            >
            <button v-else @click="useModel(model)">Use</button>
          </div>
          <p v-if="!visibleModels.length" class="muted">
            No models are available for this provider.
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
