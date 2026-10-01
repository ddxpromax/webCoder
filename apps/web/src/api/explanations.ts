import { EventSourceParserStream } from 'eventsource-parser/stream'

export interface ExplainRequest {
    code: string
    language: string
}

export interface StreamExplanationOptions {
    onToken: (text: string) => void
    signal?: AbortSignal
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value == 'object' && value !== null
}

async function getHttpError(response: Response): Promise<Error> {
    const body: unknown = await response.json().catch(() => null)

    if (isRecord(body) && typeof body.detail === 'string') {
        return new Error(body.detail)
    }

    if (isRecord(body) && typeof body.title === 'string') {
        return new Error(body.title)
    }

    return new Error(`Request failed (HTTP ${response.status}).`)
}

export async function streamExplanation(
    input: ExplainRequest,
    options: StreamExplanationOptions,
): Promise<void> {
    const response = await fetch('/api/explanations/stream', {
        method: 'POST',
        headers: {
            Accept: 'text/event-stream',
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(input),
        signal: options.signal,
    })

    if (!response.ok) {
        throw await getHttpError(response)
    }

    if (!response.headers.get('content-type')?.includes('text/event-stream')) {
        throw new Error('The server did not return an event stream.')
    }

    if (!response.body) {
        throw new Error('The server returned an empty response.')
    }

    const events = response.body
        .pipeThrough(new TextDecoderStream())
        .pipeThrough(
            new EventSourceParserStream({
                maxBufferSize: 1024 * 1024,
                onError: 'terminate',
            }),
        )

    let completed = false

    for await (const event of events) {
        if (event.event === 'token') {
            let data: unknown

            try {
                data = JSON.parse(event.data)
            } catch {
                throw new Error('The server returned an invalid token event.')
            }

            if (!isRecord(data) || typeof data.text !== 'string') {
                throw new Error('The server returned an invalid token event.')
            }

            options.onToken(data.text)
            continue
        }

        if (event.event === 'error') {
            throw new Error(event.data || 'The AI service failed.')
        }

        if (event.event === 'done' && event.data === '[DONE]') {
            completed = true
        }
    }

    if (!completed) {
        throw new Error('The explanation stream ended before completion.')
    }
}
