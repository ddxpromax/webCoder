<script setup lang="ts">
import { onMounted, ref, onBeforeUnmount } from 'vue'
import { streamExplanation } from '@/api/explanations'
import { getSystemInfo, type SystemInfo } from '@/api/system'

const systemLoading = ref(false)
const systemInfo = ref<SystemInfo | null>(null)
const systemError = ref('')

const code = ref('')
const language = ref('python')
const explanation = ref('')
const explaining = ref(false)
const explanationError = ref('')
const generationNotice = ref('')

let activeExplanationController: AbortController | null = null


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
    explanationError.value = 'Enter the code you want explained.'
    explanation.value = ''
    return
  }

  if (code.value.length > 20_000) {
    explanationError.value = 'code cannot exceed 20,000 characters'
    explanation.value = ''
    return
  }

  const controller = new AbortController()
  activeExplanationController = controller
  explaining.value = true
  explanation.value = ''
  explanationError.value = ''
  generationNotice.value = ''

  try {
    await streamExplanation(
      {
        code: code.value,
        language: language.value,
      },
      {
        signal: controller.signal,
        onToken: (text) => {
          explanation.value += text
        },
      },
    )
  } catch (error) {
    if (controller.signal.aborted) {
      generationNotice.value = 'Generation stopped.'
    } else if (error instanceof TypeError) {
      explanationError.value = 'Cannot connect to the backend service.'
    } else {
      explanationError.value = error instanceof Error ? error.message : 'Request failed. Please try again.'
    }
  } finally {
    explaining.value = false

    if (activeExplanationController === controller) {
      activeExplanationController = null
    }
  }
}

function stopGeneration() {
  activeExplanationController?.abort()
}

onMounted(loadSystemInfo)

onBeforeUnmount(() => {
  activeExplanationController?.abort()
})
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
          <p>The explanation streams in as it is generated. This prototype uses a mock response.</p>
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
        <label class="sr-only" for="code-input">Code to explain</label>
        <textarea
          id="code-input"
          v-model="code"
          :disabled="explaining"
          maxlength="20000"
          rows="14"
          spellcheck="false"
          placeholder="Paste code here..."
        />
        
        <div class="editor-footer">
          <span>{{ code.length.toLocaleString() }} / 20,000</span>
          <div class="editor-actions">
            <button
              v-if="explaining"
              type="button"
              @click="stopGeneration"
            >
              Stop
            </button>
            <button type="submit" :disabled="explaining">
              {{ explaining ? 'Generating...' : 'Explain code' }}
            </button>
          </div>
        </div>
      </form>

      <p v-if="explaining" role="status">Generating explanation...</p>
      <p v-else-if="generationNotice" role="status">{{ generationNotice }}</p>
      <p v-else-if="explanationError" class="error" role="alert">{{ explanationError }}</p>

      <section 
        v-if="explanation"
        class="result"
        aria-labelledby="result-title"
        aria-live="polite"
      >
        <div class="result-heading">
          <h2 id="result-title">Explanation</h2>
          <span class="mode-badge">mock</span>
        </div>
        <pre>{{ explanation }}</pre>
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

  .editor-actions {
    display: flex;
    gap: 8px;
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
