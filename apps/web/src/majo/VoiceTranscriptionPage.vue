<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'

type ProviderType = 'disabled' | 'whisper_api' | 'local_whisper'
const audioMode = ref<'auto' | 'native'>('auto')
const providerType = ref<ProviderType>('disabled')
const providerId = ref('')
const providers = ref<Array<{ id: string; name: string; available: boolean }>>([])
const localWhisper = ref({ available: false, ffmpeg_installed: false, whisper_installed: false })
const saving = ref(false)
const error = ref('')
const notice = ref('')

async function refresh() {
  error.value = ''
  try {
    const [mode, type, available, local] = await Promise.all([
      apiRequest<{ audio_mode: 'auto' | 'native' }>('/workspace/audio-mode'),
      apiRequest<{ transcription_provider_type: ProviderType }>('/workspace/transcription-provider-type'),
      apiRequest<{ providers: Array<{ id: string; name: string; available: boolean }>; configured_provider_id: string }>('/workspace/transcription-providers'),
      apiRequest<typeof localWhisper.value>('/workspace/local-whisper-status'),
    ])
    audioMode.value = mode.audio_mode
    providerType.value = type.transcription_provider_type
    providers.value = available.providers
    providerId.value = available.configured_provider_id
    localWhisper.value = local
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load voice transcription settings.'
  }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await Promise.all([
      apiRequest('/workspace/audio-mode', { method: 'PUT', body: JSON.stringify({ audio_mode: audioMode.value }) }),
      apiRequest('/workspace/transcription-provider-type', { method: 'PUT', body: JSON.stringify({ transcription_provider_type: providerType.value }) }),
      ...(providerType.value === 'whisper_api'
        ? [apiRequest('/workspace/transcription-provider', { method: 'PUT', body: JSON.stringify({ provider_id: providerId.value }) })]
        : []),
    ])
    notice.value = 'Voice transcription settings saved.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save voice transcription settings.'
  } finally { saving.value = false }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading"><div><p class="eyebrow">SETTINGS</p><h1>Voice transcription</h1><p>Choose how voice input is captured and transcribed.</p></div><button @click="refresh">Reset</button></header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="settings-card narrow-card"><h2>Audio capture</h2><label class="skills-checkbox"><input v-model="audioMode" type="radio" value="auto" /> Automatically select supported voice capture</label><label class="skills-checkbox"><input v-model="audioMode" type="radio" value="native" /> Use browser-native speech recognition</label></div>
    <div v-if="audioMode !== 'native'" class="settings-card narrow-card"><h2>Transcription provider</h2><label>Provider type<select v-model="providerType"><option value="disabled">Disabled</option><option value="whisper_api">Whisper API</option><option value="local_whisper" :disabled="!localWhisper.available">Local Whisper</option></select></label><p v-if="providerType === 'local_whisper'" class="muted">{{ localWhisper.whisper_installed ? 'Local Whisper is available.' : 'Local Whisper is not installed.' }}</p><label v-if="providerType === 'whisper_api'">Provider<select v-model="providerId"><option value="">Select a configured provider</option><option v-for="provider in providers.filter((item) => item.available)" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label></div>
    <div class="settings-card narrow-card"><button class="primary" :disabled="saving" @click="save">{{ saving ? 'Saving…' : 'Save voice settings' }}</button></div>
  </section>
</template>
