function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null
}

export async function getHttpError(response: Response): Promise<Error> {
    const body: unknown = await response.json().catch(() => null)

    if (isRecord(body) && typeof body.detail === 'string') {
        return new Error(body.detail)
    }

    if (isRecord(body) && typeof body.title === 'string') {
        return new Error(body.title)
    }

    return new Error(`Request failed (HTTP ${response.status}).`)
}
