import { getHttpError } from '@/api/http'

export type MessageRole = 'user' | 'assistant'

export type MessageStatus =
    | 'pending'
    | 'completed'
    | 'failed'
    | 'cancelled'

export interface ConversationResponse {
    id: string
    title: string
    createdAt: string
    updatedAt: string
}

export interface ConversationMessage {
    id: number
    conversationId: string
    role: MessageRole
    content: string
    language: string | null
    status: MessageStatus
    createdAt: string
}

export interface MessageCursor {
    createdAt: string
    id: number
}

export interface MessagePageResponse {
    conversationId: string
    messages: ConversationMessage[]
    nextCursor: MessageCursor | null
}

export async function createConversation(
    signal?:AbortSignal,
): Promise<ConversationResponse> {
    const response = await fetch('/api/conversations', {
        method: 'POST',
        headers: {
            Accept: 'application/json',
        },
        signal,
    })

    if (!response.ok) {
        throw await getHttpError(response)
    }

    return response.json() as Promise<ConversationResponse>
}

export async function getRecentMessages(
    conversationId: string,
    limit = 4,
    before?: MessageCursor,
): Promise<MessagePageResponse> {
    const query = new URLSearchParams({
        limit: String(limit),
    })

    if (before) {
        query.set('beforeCreatedAt', before.createdAt)
        query.set('beforeId', String(before.id))
    }

    const response = await fetch(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?${query.toString()}`,
        {
            method: 'GET',
            headers: {
                Accept: 'application/json',
            },
        })

    if (!response.ok) {
        throw await getHttpError(response)
    }

    return response.json() as Promise<MessagePageResponse>
}
