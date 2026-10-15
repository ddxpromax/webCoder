import { afterEach, describe, expect, it, vi } from 'vitest'

import { createConversation } from '@/api/conversation'
import { getHttpError } from '@/api/http'
import { streamExplanation } from '@/api/explanations'
import { getRecentMessages } from '@/api/conversation'

afterEach(() => {
    vi.unstubAllGlobals()
})

describe('conversation API', () => {
    it('creates a conversation and passes the abort signal', async () => {
        const signal = new AbortController().signal
        const conversation = {
            id: '00000000-0000-0000-0000-000000000001',
            title: 'New conversation',
            createdAt: '2026-10-07T00:00:00Z',
            updatedAt: '2026-10-07T00:00:00Z',
        }

        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            json: vi.fn().mockResolvedValue(conversation),
        })

        vi.stubGlobal('fetch', fetchMock)

        await expect(createConversation(signal)).resolves.toEqual(conversation)

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/conversations',
            expect.objectContaining({
                method: 'POST',
                signal,
            }),
        )
    })

    it('uses the conversation UUID in the streaming endpoint URL', async () => {
        const conversationId =
            '00000000-0000-0000-0000-000000000001'
        const request = {
            code: 'print(1)',
            language: 'python',
        }

        const fetchMock = vi.fn().mockResolvedValue({
            ok: false,
            status: 404,
            json: vi.fn().mockResolvedValue({
                detail: 'Conversation not found.',
            }),
        })

        vi.stubGlobal('fetch', fetchMock)

        await expect(
            streamExplanation(conversationId, request, {
                onToken: vi.fn(),
            }),
        ).rejects.toThrow('Conversation not found.')

        expect(fetchMock).toHaveBeenCalledWith(
            `/api/conversations/${conversationId}/messages/stream`,
            expect.objectContaining({
                method: 'POST',
                headers: expect.objectContaining({
                    Accept: 'text/event-stream',
                    'Content-Type': 'application/json',
                }),
                body: JSON.stringify(request),
            }),
        )
    })

    it('loads a recent message page using the composite cursor', async () => {
        const conversationId = '00000000-0000-0000-0000-000000000001'
        const cursor = {
            createdAt: '2026-10-07T12:30:00Z',
            id: 42,
        }
        const page = {
            conversationId,
            messages: [
                {
                    id: 42,
                    conversationId,
                    role: 'assistant',
                    content: 'The latest reply.',
                    language: 'python',
                    status: 'completed',
                    createdAt: '2026-10-07T12:30:00Z',
                },
            ],
            nextCursor: cursor,
        }

        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            json: vi.fn().mockResolvedValue(page),
        })

        vi.stubGlobal('fetch', fetchMock)

        await expect(getRecentMessages(conversationId, 50, cursor))
            .resolves.toEqual(page)

        const query = new URLSearchParams({
            limit: '50',
            beforeCreatedAt: cursor.createdAt,
            beforeId: String(cursor.id),
        })

        expect(fetchMock).toHaveBeenCalledWith(
            `/api/conversations/${conversationId}/messages?${query.toString()}`,
            expect.objectContaining({
                method: 'GET',
                headers: {
                    Accept: 'application/json',
                },
            }),
        )
    })
})

describe('HTTP error formatting', () => {
    it('includes the HTTP status when the response has no problem details', async () => {
        const error = await getHttpError({
            status: 503,
            json: vi.fn().mockRejectedValue(new Error('No JSON body')),
        } as unknown as Response)

        expect(error.message).toBe('Request failed (HTTP 503).')
    })
})
