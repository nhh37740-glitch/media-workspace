/** Formatting helpers for values the API returns in canonical form. */

/** Byte count as a short human-readable string. */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) {
    return '—'
  }
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let value = Number(bytes)
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

/** Duration in milliseconds as mm:ss, or h:mm:ss past an hour. */
export function formatDuration(millis) {
  if (millis === null || millis === undefined) {
    return '—'
  }
  const total = Math.max(0, Math.round(millis / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

/**
 * An instant the API sent as UTC ISO-8601, shown in the browser's own time zone.
 *
 * The API always sends UTC; converting here is the only place a local time appears, which keeps the
 * stored and transmitted value unambiguous.
 */
export function formatInstant(isoString) {
  if (!isoString) {
    return '—'
  }
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) {
    return isoString
  }
  return date.toLocaleString('zh-CN', {
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

/** A relative description such as "3 分钟前", used for last-updated fields. */
export function formatRelative(isoString) {
  if (!isoString) {
    return '—'
  }
  const then = new Date(isoString).getTime()
  if (Number.isNaN(then)) {
    return isoString
  }
  const seconds = Math.round((Date.now() - then) / 1000)
  if (seconds < 5) return '刚刚'
  if (seconds < 60) return `${seconds} 秒前`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.round(hours / 24)} 天前`
}

/** Splits a file name into its base name and extension, for display only. */
export function splitFileName(name) {
  if (!name) {
    return { base: '', extension: '' }
  }
  const index = name.lastIndexOf('.')
  if (index <= 0) {
    return { base: name, extension: '' }
  }
  return { base: name.slice(0, index), extension: name.slice(index + 1) }
}
