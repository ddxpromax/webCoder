<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'

interface SkillSpec {
  name: string
  description: string
  version_text: string
  content: string
  source: string
  enabled: boolean
  preload: boolean
  channels: string[]
  tags: string[]
  last_updated: string
}

const skills = ref<SkillSpec[]>([])
const selectedName = ref('')
const draftName = ref('')
const draftContent = ref('')
const draftTags = ref('')
const draftPreload = ref(false)
const creating = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const filter = ref('')
const archive = ref<File | null>(null)
const importEnabled = ref(true)

const base = computed(() => `/agents/${encodeURIComponent(workbench.agentId)}/skills`)
const selected = computed(() => skills.value.find((skill) => skill.name === selectedName.value))
const filtered = computed(() =>
  skills.value.filter((skill) =>
    `${skill.name} ${skill.description} ${skill.tags.join(' ')}`
      .toLowerCase()
      .includes(filter.value.toLowerCase()),
  ),
)

function select(skill: SkillSpec) {
  creating.value = false
  selectedName.value = skill.name
  draftName.value = skill.name
  draftContent.value = skill.content
  draftTags.value = skill.tags.join(', ')
  draftPreload.value = skill.preload
  error.value = ''
  notice.value = ''
}

async function refresh(preferredName?: string) {
  loading.value = true
  error.value = ''
  try {
    const result = await apiRequest<SkillSpec[]>(`${base.value}/refresh`, {
      method: 'POST',
    }, workbench.agentId)
    skills.value = result
    const next = result.find((skill) => skill.name === preferredName)
      ?? result.find((skill) => skill.name === selectedName.value)
      ?? result[0]
    if (next && !creating.value) select(next)
    if (!next) selectedName.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load skills.'
  } finally {
    loading.value = false
  }
}

function startCreate() {
  creating.value = true
  selectedName.value = ''
  draftName.value = ''
  draftContent.value = '---\nname: new_skill\ndescription: Describe when to use this skill\n---\n\nWrite skill instructions here.\n'
  draftTags.value = ''
  draftPreload.value = false
  error.value = ''
  notice.value = ''
}

async function save() {
  if (!draftName.value.trim()) {
    error.value = 'Enter a skill name.'
    return
  }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const name = draftName.value.trim()
    if (creating.value) {
      await apiRequest(`${base.value}`, {
        method: 'POST',
        body: JSON.stringify({ name, content: draftContent.value, enable: false }),
      }, workbench.agentId)
      creating.value = false
    } else {
      await apiRequest(`${base.value}/save`, {
        method: 'POST',
        body: JSON.stringify({
          source_name: selectedName.value,
          name,
          content: draftContent.value,
          overwrite: false,
        }),
      }, workbench.agentId)
    }
    await apiRequest(`${base.value}/${encodeURIComponent(name)}/tags`, {
      method: 'PUT',
      body: JSON.stringify(draftTags.value.split(',').map((tag) => tag.trim()).filter(Boolean)),
    }, workbench.agentId)
    await apiRequest(`${base.value}/${encodeURIComponent(name)}/preload`, {
      method: 'PUT', body: JSON.stringify({ preload: draftPreload.value }),
    }, workbench.agentId)
    await refresh(name)
    notice.value = 'Skill saved.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save skill.'
  } finally {
    saving.value = false
  }
}

async function toggle(skill: SkillSpec) {
  error.value = ''
  notice.value = ''
  try {
    await apiRequest(
      `${base.value}/${encodeURIComponent(skill.name)}/${skill.enabled ? 'disable' : 'enable'}`,
      { method: 'POST' },
      workbench.agentId,
    )
    await refresh(skill.name)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update skill.'
  }
}

async function remove() {
  const skill = selected.value
  if (!skill) return
  error.value = ''
  try {
    await apiRequest(`${base.value}/${encodeURIComponent(skill.name)}`, {
      method: 'DELETE',
    }, workbench.agentId)
    selectedName.value = ''
    await refresh()
    notice.value = 'Skill deleted.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not delete skill.'
  }
}

function selectArchive(event: Event) {
  archive.value = (event.target as HTMLInputElement)
    .files?.[0] ?? null
}

async function upload() {
  if (!archive.value) {
    error.value = 'Choose a ZIP archive first.'
    return
  }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const body = new FormData()
    body.append('file', archive.value)
    body.append('enable', String(importEnabled.value))
    const result = await apiRequest<{ imported: string[] }>(
      `${base.value}/upload`, { method: 'POST', body }, workbench.agentId,
    )
    archive.value = null
    await refresh(result.imported[0])
    notice.value = `Imported ${result.imported.length} skill${result.imported.length === 1 ? '' : 's'}.`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not import skills.'
  } finally {
    saving.value = false
  }
}

watch(() => workbench.agentId, () => {
  selectedName.value = ''
  creating.value = false
  void refresh()
})
onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page skills-page">
    <header class="settings-heading">
      <div><p class="eyebrow">AGENT WORKSPACE</p><h1>Skills</h1></div>
      <button @click="startCreate">New skill</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <form class="skills-upload settings-card" @submit.prevent="upload">
      <label>Import skills from ZIP <input type="file" accept=".zip,application/zip" @change="selectArchive" /></label>
      <label class="skills-checkbox"><input v-model="importEnabled" type="checkbox" /> Enable imported skills</label>
      <button type="submit" :disabled="saving">Import</button>
    </form>
    <div class="skills-grid">
      <div class="settings-card skills-list">
        <input v-model="filter" aria-label="Find skills" placeholder="Find skills" />
        <p v-if="loading">Loading skills…</p>
        <p v-else-if="!filtered.length">No skills found.</p>
        <button
          v-for="skill in filtered" :key="skill.name"
          class="skills-item" :class="{ selected: selectedName === skill.name && !creating }"
          @click="select(skill)"
        >
          <span><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small></span>
          <em :class="{ active: skill.enabled }">{{ skill.enabled ? 'On' : 'Off' }}</em>
        </button>
      </div>
      <form v-if="creating || selected" class="settings-card skills-editor" @submit.prevent="save">
        <div class="skills-editor-heading">
          <h2>{{ creating ? 'New skill' : selected?.name }}</h2>
          <button v-if="selected" type="button" @click="toggle(selected)">
            {{ selected.enabled ? 'Disable' : 'Enable' }}
          </button>
        </div>
        <label>Directory name <input v-model="draftName" spellcheck="false" /></label>
        <label>SKILL.md <textarea v-model="draftContent" spellcheck="false" rows="18" /></label>
        <label>Tags <input v-model="draftTags" placeholder="Comma-separated" /></label>
        <label class="skills-checkbox"><input v-model="draftPreload" type="checkbox" /> Preload full content</label>
        <div class="skills-editor-actions">
          <button type="submit" class="primary" :disabled="saving">{{ saving ? 'Saving…' : 'Save skill' }}</button>
          <button v-if="selected && !selected.enabled" type="button" @click="remove">Delete</button>
        </div>
        <p v-if="selected?.enabled" class="skills-note">Disable this skill before deleting it.</p>
      </form>
      <div v-else class="settings-card skills-editor-empty">
        Select a skill or create a new one.
      </div>
    </div>
  </section>
</template>
