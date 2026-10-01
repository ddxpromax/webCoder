export interface SystemInfo {
    applicationName: string
}

export async function getSystemInfo(): Promise<SystemInfo> {
    const response = await fetch('/api/system/info', {
        headers: {
            Accept: 'application/json',
        },
        signal: AbortSignal.timeout(10_000),
    })

    if (!response.ok) {
        throw new Error(`backend response error(HTTP ${response.status})`)
    }

    const data: unknown = await response.json()

    if (
        typeof data !== 'object' || 
        data === null || 
        !('applicationName' in data) ||
        typeof data.applicationName !== 'string'
    ) {
        throw new Error('backend response data format error')
    }

    return {
        applicationName: data.applicationName,
    }
}
