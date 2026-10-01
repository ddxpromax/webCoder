export interface ExplainRequest {
    code: string
    language: string
}

export interface ExplainResponse {
    mode: 'mock'
    explanation: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value == 'object' && value !== null
}

export async function explainCode(
    input: ExplainRequest,
): Promise<ExplainResponse> {
    const response = await fetch('/api/explanations', {
        method: 'POST',
        headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(input),
        signal: AbortSignal.timeout(60_000),
    })

    if (!response.ok) {
        const body: unknown = await response.json().catch(() => null)
        const message = isRecord(body) && typeof body.detail === 'string'
            ? body.detail
            : isRecord(body) && typeof body.title === 'string'
                ? body.title
                : `request failed (HTTP ${response.status})`
            
        throw new Error(message)
    }

    const body: unknown = await response.json()

    if (
        !isRecord(body) ||
        body.mode !== 'mock' ||
        typeof body.explanation !== 'string'
    ) {
        throw new Error('response data format is not correct')
    }

    return {
        mode: 'mock',
        explanation: body.explanation,
    }
}