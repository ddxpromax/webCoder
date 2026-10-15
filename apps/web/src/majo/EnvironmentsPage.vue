<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { apiRequest } from './api'

interface EnvVar {
  key: string
  value: string
}

interface Row extends EnvVar {
  id: number
  savedKey?: string
}

const rows = ref<Row[]>([])
const original = ref<EnvVar[]>([])
const selected = ref(new Set<number>())
const visible = ref(new Set<number>())
const errors = ref<Record<number, string>>({})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let nextId = 0

const dirty = computed(
  () =>
    JSON.stringify(rows.value.map(({ key, value }) => ({ key, value }))) !==
    JSON.stringify(original.value),
)
const allSelected = computed(
  () => rows.value.length > 0 && rows.value.every((row) => selected.value.has(row.id)),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await apiRequest<EnvVar[]>('/envs')
    original.value = data
    rows.value = data.map((item) => ({ ...item, id: ++nextId, savedKey: item.key }))
    selected.value = new Set()
    visible.value = new Set()
    errors.value = {}
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load variables.'
  } finally {
    loading.value = false
  }
}

function addAfter(index?: number) {
  const row: Row = { id: ++nextId, key: '', value: '' }
  if (index === undefined) rows.value.push(row)
  else rows.value.splice(index + 1, 0, row)
}

function toggleSelection(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleAll() {
  selected.value = allSelected.value
    ? new Set()
    : new Set(rows.value.map((row) => row.id))
}

function toggleVisible(id: number) {
  const next = new Set(visible.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  visible.value = next
}

async function remove(row: Row) {
  if (!row.savedKey) {
    rows.value = rows.value.filter((item) => item.id !== row.id)
    selected.value.delete(row.id)
    return
  }
  if (!window.confirm(`Delete environment variable "${row.savedKey}"?`)) return
  try {
    await apiRequest(`/envs/${encodeURIComponent(row.savedKey)}`, { method: 'DELETE' })
    notice.value = `Deleted ${row.savedKey}.`
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not delete variable.'
  }
}

async function removeSelected() {
  const selectedRows = rows.value.filter((row) => selected.value.has(row.id))
  if (selectedRows.length === 0) return
  if (!selectedRows.some((row) => row.savedKey)) {
    rows.value = rows.value.filter((row) => !selected.value.has(row.id))
    selected.value = new Set()
    return
  }
  if (!window.confirm(`Delete ${selectedRows.length} selected variables?`)) return
  try {
    await Promise.all(
      selectedRows
        .filter((row) => row.savedKey)
        .map((row) => apiRequest(`/envs/${encodeURIComponent(row.savedKey!)}`, { method: 'DELETE' })),
    )
    notice.value = 'Selected variables deleted.'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not delete variables.'
  }
}

async function save() {
  error.value = ''
  notice.value = ''
  const nextErrors: Record<number, string> = {}
  const keys = new Set<string>()
  for (const row of rows.value) {
    const key = row.key.trim()
    if (!key) nextErrors[row.id] = 'A name is required.'
    else if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      nextErrors[row.id] = 'Use letters, numbers, and underscores; start with a letter or underscore.'
    } else if (keys.has(key)) nextErrors[row.id] = 'Names must be unique.'
    keys.add(key)
  }
  errors.value = nextErrors
  if (Object.keys(nextErrors).length > 0) return
  saving.value = true
  try {
    const values = Object.fromEntries(rows.value.map((row) => [row.key.trim(), row.value]))
    await apiRequest('/envs', { method: 'PUT', body: JSON.stringify(values) })
    notice.value = 'Environment variables saved.'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save variables.'
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow"><RouterLink to="/settings">SETTINGS</RouterLink> / ENVIRONMENTS</p>
        <h1>Environment variables</h1>
        <p>Manage named values used by workspace integrations.</p>
      </div>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="settings-card environment-card">
      <p v-if="loading">Loading variables…</p>
      <template v-else>
        <div class="environment-toolbar">
          <label><input type="checkbox" :checked="allSelected" @change="toggleAll" /> Select all</label>
          <button type="button" @click="addAfter()">Add variable</button>
          <button v-if="selected.size" type="button" @click="removeSelected">Delete selected</button>
        </div>
        <p v-if="!rows.length" class="sidebar-empty">No environment variables yet.</p>
        <div v-for="(row, index) in rows" :key="row.id" class="environment-row">
          <input
            type="checkbox"
            :checked="selected.has(row.id)"
            :aria-label="`Select ${row.key || 'new variable'}`"
            @change="toggleSelection(row.id)"
          />
          <label>
            Name
            <input v-model="row.key" autocomplete="off" placeholder="VARIABLE_NAME" />
            <span v-if="errors[row.id]" class="inline-error">{{ errors[row.id] }}</span>
          </label>
          <label>
            Value
            <input
              v-model="row.value"
              :type="visible.has(row.id) ? 'text' : 'password'"
              autocomplete="off"
              placeholder="Value"
            />
          </label>
          <button type="button" @click="toggleVisible(row.id)">
            {{ visible.has(row.id) ? 'Hide' : 'Show' }}
          </button>
          <button type="button" :aria-label="`Insert after ${row.key}`" @click="addAfter(index)">+</button>
          <button type="button" :aria-label="`Delete ${row.key}`" @click="remove(row)">Delete</button>
        </div>
        <div class="environment-actions">
          <button type="button" class="primary" :disabled="!dirty || saving" @click="save">
            {{ saving ? 'Saving…' : 'Save variables' }}
          </button>
          <button v-if="dirty" type="button" @click="load">Discard changes</button>
        </div>
      </template>
    </div>
  </section>
</template>
