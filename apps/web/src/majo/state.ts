import { reactive } from 'vue'
import type { AgentInfo, ChatSpec } from './api'

export const workbench = reactive({
  agentId:
    (typeof window === 'undefined' ? null : window.localStorage?.getItem('majo_agent_id')) ||
    'default',
  agents: [] as AgentInfo[],
  chats: [] as ChatSpec[],
  error: '',
  loading: false,
})

/** Shared Coding-only composer bridge, matching Majo's editor-to-chat action. */
export const codingComposer = reactive({ text: '', revision: 0 })

export function appendCodingComposer(text: string) {
  codingComposer.text = codingComposer.text ? `${codingComposer.text}\n${text}` : text
  codingComposer.revision += 1
}

export function selectAgent(agentId: string) {
  workbench.agentId = agentId
  if (typeof window !== 'undefined') {
    window.localStorage?.setItem('majo_agent_id', agentId)
  }
}
