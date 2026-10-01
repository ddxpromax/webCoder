<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { explainCode, type ExplainResponse } from '@/api/explanations'
import { getSystemInfo, type SystemInfo } from '@/api/system'

const systemLoading = ref(false)
const systemInfo = ref<SystemInfo | null>(null)
const systemError = ref('')

const code = ref('')
const language = ref('python')
const explaining = ref(false)
const explanation = ref<ExplainResponse | null>(null)
const explanationError = ref('')

async function loadSystemInfo() {
  if (systemLoading.value) return

  systemLoading.value = true
  systemInfo.value = null
  systemError.value = ''

  try {
    systemInfo.value = await getSystemInfo()
  } catch (error) {
    if (error instanceof Error && error.name === 'TimeoutError') {
      systemError.value = 'connection timeout'
    } else if (error instanceof TypeError) {
      systemError.value = 'cannot connect to service, please check network and backend state'
    } else {
      systemError.value = error instanceof Error ? error.message : 'request failed, please try again'
    }
  } finally {
    systemLoading.value = false
  }
}

async function submitExplanation() {
  if (explaining.value) return

  if (!code.value.trim()) {
    explanationError.value = 'please input code needed explanation'
    explanation.value = null
    return
  }

  if (code.value.length > 20_000) {
    explanationError.value = 'code cannot exceed 20,000 characters'
    explanation.value = null
    return
  }

  explaining.value = true
  explanation.value = null
  explanationError.value = ''

  try {
    explanation.value = await explainCode({
      code: code.value,
      language: language.value,
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'TimeoutError') {
      explanationError.value = 'connection timeout'
    } else if (error instanceof TypeError) {
      explanationError.value = 'cannot connect to service, please check network and backend state'
    } else {
      explanationError.value = error instanceof Error ? error.message : 'request failed, please try again'
    }
  } finally {
    explaining.value = false
  }
}

onMounted(loadSystemInfo)
</script>

<template>
  <main class="page">
    <header class="page-header">
      <div>
        <h1>WebCoder</h1>
        <p>AI explanation workspace</p>
      </div>

      <section class="connection" aria-label="backend connection state">
        <span v-if="systemLoading" role="status">connecting backend...</span>
        <span v-else-if="systemError" class="error" role="alert">{{ systemError }}</span>
        <span v-else-if="systemInfo" class="connected">connected: {{ systemInfo.applicationName }}</span>

        <button type="button" :disabled="systemLoading" @click="loadSystemInfo">test again</button>
      </section>
    </header>

    <section class="workspace" aria-labelledby="editor-title">
      <div class="section-heading">
        <div>
          <h2 id="editor-title">explain a piece of code</h2>
          <p>backend will respond a explanation after commit; using current mock response</p>
        </div>

        <label class="language-picker">
          <span>language</span>
          <select v-model="language" :disabled="explaining">
            <option value="python">Python</option>
            <option value="javascript">JavaScript</option>
            <option value="typescript">TypeScript</option>
            <option value="java">Java</option>
          </select>
        </label>
      </div>

      <form id="explanation-form" @submit.prevent="submitExplanation">
        <label class="sr-only" for="code-input">code needed explanation</label>
        <textarea
          id="code-input"
          v-model="code"
          :disabled="explaining"
          maxlength="20000"
          rows="14"
          spellcheck="false"
          placeholder="place a piece of code here to explain..."
        />
        
        <div class="editor-footer">
          <span>{{ code.length.toLocaleString() }} / 20,000</span>
          <button type="submit" :disabled="explaining">{{ explaining ? 'explaining' : 'explain the code' }}</button>
        </div>
      </form>

      <p v-if="explaining" role="status">waiting for explanation...</p>
      <p v-else-if="explanationError" class="error" role="alert">{{ explanationError }}</p>

      <section 
        v-if="explanation"
        class="result"
        aria-labelledby="result-title"
        aria-live="polite"
      >
        <div class="result-heading">
          <h2 id="result-title">explanation</h2>
          <span class="mode-badge">{{ explanation.mode }}</span>
        </div>
        <pre>{{ explanation.explanation }}</pre>
      </section>
    </section>
  </main>
</template>

<style scoped>
  .page {
    max-width: 960px;
    margin: 48px auto;
    padding: 0 24px;
    color: #202124;
    font-family: system-ui, sans-serif;
  }

  .page-header,
  .section-heading,
  .connection,
  .editor-footer,
  .result-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .page-header {
    margin-bottom: 32px;
  }

  h1,
  h2,
  p {
    margin-top: 0;
  }

  .page-header p,
  .section-heading p{
    margin-bottom: 0;
    color: #60656f;
  }

  .workspace {
    padding: 24px;
    border: 1px solid #d9dce2;
    border-radius: 12px;
  }

  .section-heading {
    margin-bottom: 20px;
  }

  .language-picker {
    display: grid;
    gap: 6px;
  }

  select,
  textarea,
  button {
    font: inherit;
  }

  select,
  textarea {
    border: 1px solid #c8cbd2;
    background: white;
  }

  textarea {
    box-sizing: border-box;
    width: 100%;
    padding: 16px;
    resize: vertical;
    background: #17191e;
    color: #f1f3f5;
    font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    line-height: 1.6;
  }

  .editor-footer {
    margin-top: 12px;
    color: #60656f;
    font-size: 14px;
  }

  button {
    padding: 9px 16px;
    border: 0;
    border-radius: 8px;
    background: #315efb;
    color: white;
    cursor: pointer;
  }

  button:disabled {
    cursor: wait;
    opacity: 0.65;
  }

  .connection button {
    padding: 7px 12px;
    background: #eef1f6;
    color: #202124;
  }

  .connected {
    color: #18794e;
  }

  .error {
    color: #b42318;
  }

  .result {
    margin-top: 24px;
    padding-top: 20px;
    border-top: 1px solid #d9dce2;
  }

  .result h2 {
    margin-bottom: 0;
  }

  .mode-badge {
    padding: 4px 8px;
    border-radius: 999px;
    background: #eef1f6;
    color: #505866;
    font-size: 12px;
  }

  .result pre {
    overflow-x: auto;
    padding: 16px;
    border-radius: 8px;
    background: #f5f6f8;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .sr-only {
    position:absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  @media (max-width: 640px) {
    .page {
      margin: 24px auto;
      padding: 0 16px;
    }

    .page-header,
    .section-heading {
      align-items: flex-start;
      flex-direction: column;
    }

    .workspace {
      padding: 16px;
    }
  }
</style>
