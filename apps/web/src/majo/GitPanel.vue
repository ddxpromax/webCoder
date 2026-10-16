<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  gitBranches,
  gitChangeStage,
  gitCheckout,
  gitCommit,
  gitCommitDiff,
  gitDiff,
  gitDiscard,
  gitLog,
  gitRevert,
  gitStatus,
  type GitChange,
  type GitCommit,
  type GitStatus,
} from './api'
import { workbench } from './state'

const status = ref<GitStatus | null>(null)
const branches = ref<Array<{ name: string; current: boolean }>>([])
const commits = ref<GitCommit[]>([])
const commitMessage = ref('')
const branchName = ref('')
const diffTitle = ref('')
const diff = ref('')
const error = ref('')
const busy = ref(false)

async function refresh() {
  busy.value = true
  error.value = ''
  try {
    const [nextStatus, nextBranches, nextLog] = await Promise.all([
      gitStatus(workbench.agentId), gitBranches(workbench.agentId), gitLog(workbench.agentId, 12),
    ])
    status.value = nextStatus
    branches.value = nextBranches.filter((branch) => !branch.remote)
    commits.value = nextLog
  } catch (cause) {
    status.value = null
    error.value = cause instanceof Error ? cause.message : 'Could not load source control.'
  } finally {
    busy.value = false
  }
}

async function stage(change: GitChange) {
  try { await gitChangeStage(workbench.agentId, [change.path], !change.staged); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not update staging.' }
}

async function showDiff(change: GitChange) {
  try {
    const result = await gitDiff(workbench.agentId, change.path, change.staged, change.status === '?')
    diffTitle.value = `${change.path}${change.staged ? ' (staged)' : ''}`
    diff.value = result.diff || 'No visible diff.'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load diff.' }
}

async function discard(change: GitChange) {
  if (!window.confirm(`Discard changes in ${change.path}?`)) return
  try { await gitDiscard(workbench.agentId, [change.path]); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not discard changes.' }
}

async function createBranch() {
  const branch = branchName.value.trim()
  if (!branch) return
  try { await gitCheckout(workbench.agentId, branch, true); branchName.value = ''; await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not create branch.' }
}

async function switchBranch(event: Event) {
  const branch = (event.target as HTMLSelectElement).value
  if (!branch || branch === status.value?.branch) return
  try { await gitCheckout(workbench.agentId, branch); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not switch branch.' }
}

async function commit() {
  const message = commitMessage.value.trim()
  if (!message) return
  try { await gitCommit(workbench.agentId, message); commitMessage.value = ''; await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not commit changes.' }
}

async function showCommitDiff(commit: GitCommit) {
  try { const result = await gitCommitDiff(workbench.agentId, commit.hash); diffTitle.value = `${commit.hash} · ${commit.message}`; diff.value = result.diff }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load commit diff.' }
}

async function revert(commit: GitCommit) {
  if (!window.confirm(`Revert ${commit.hash}: ${commit.message}?`)) return
  try { await gitRevert(workbench.agentId, commit.hash); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not revert commit.' }
}

watch(() => workbench.agentId, () => void refresh())
onMounted(() => void refresh())
</script>

<template>
  <section class="git-panel">
    <header class="git-heading">
      <strong>Source Control</strong>
      <button class="icon-button small" :disabled="busy" title="Refresh source control" @click="refresh">↻</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <template v-if="status">
      <div class="git-branch-row">
        <span>Branch</span>
        <select :value="status.branch" aria-label="Current branch" @change="switchBranch">
          <option v-for="branch in branches" :key="branch.name" :value="branch.name">{{ branch.name }}</option>
        </select>
      </div>
      <form class="git-new-branch" @submit.prevent="createBranch">
        <input v-model="branchName" aria-label="New branch name" placeholder="New branch" />
        <button type="submit">Create</button>
      </form>

      <div class="git-changes">
        <p class="eyebrow">CHANGES {{ status.changes.length ? `(${status.changes.length})` : '' }}</p>
        <p v-if="!status.changes.length" class="muted">Working tree clean.</p>
        <div v-for="change in status.changes" :key="`${change.path}-${change.staged}`" class="git-change">
          <button class="git-stage" :title="change.staged ? 'Unstage file' : 'Stage file'" @click="stage(change)">{{ change.staged ? '−' : '+' }}</button>
          <button class="git-file" :title="`Show diff for ${change.path}`" @click="showDiff(change)">
            <span :class="`git-status git-status-${change.status.replace('?', 'untracked')}`">{{ change.status }}</span>{{ change.path }}
          </button>
          <button v-if="!change.staged" class="git-discard" title="Discard changes" @click="discard(change)">↶</button>
        </div>
      </div>

      <form class="git-commit" @submit.prevent="commit">
        <input v-model="commitMessage" aria-label="Commit message" placeholder="Commit message" />
        <button class="primary" type="submit" :disabled="!status.changes.some((change) => change.staged)">Commit</button>
      </form>

      <details class="git-log">
        <summary>Recent commits</summary>
        <div v-for="item in commits" :key="item.hash" class="git-commit-row">
          <button @click="showCommitDiff(item)"><code>{{ item.hash }}</code> {{ item.message }}</button>
          <button title="Revert commit" @click="revert(item)">↶</button>
        </div>
      </details>
      <details v-if="diffTitle" class="git-diff" open>
        <summary>{{ diffTitle }}</summary>
        <pre>{{ diff }}</pre>
      </details>
    </template>
  </section>
</template>
