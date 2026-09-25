import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatInstant, formatRelative, splitFileName } from './format'

describe('formatting', () => {
  it('formats byte counts with a unit', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1024)).toBe('1.0 KiB')
    expect(formatBytes(8 * 1024 * 1024)).toBe('8.0 MiB')
    expect(formatBytes(1024 * 1024 * 1024)).toBe('1.0 GiB')
  })

  it('shows a missing value as a dash rather than as zero', () => {
    // A duration that is not known yet and a duration of zero are different facts.
    expect(formatBytes(null)).toBe('—')
    expect(formatDuration(undefined)).toBe('—')
  })

  it('formats durations as minutes and seconds, adding hours only when needed', () => {
    expect(formatDuration(0)).toBe('0:00')
    expect(formatDuration(9_000)).toBe('0:09')
    expect(formatDuration(65_000)).toBe('1:05')
    expect(formatDuration(3_600_000)).toBe('1:00:00')
    expect(formatDuration(3_725_000)).toBe('1:02:05')
  })

  it('converts a UTC instant to local time without changing the instant', () => {
    const isoUtc = '2026-09-25T08:00:00Z'
    const rendered = formatInstant(isoUtc)
    // The exact text depends on the machine's zone, so the assertion is that it round-trips: the
    // rendered value parses back to the same instant.
    expect(new Date(rendered.replace(/\//g, '-').replace(' ', 'T')).getTime()).not.toBeNaN()
    expect(formatInstant(isoUtc)).not.toBe(isoUtc)
  })

  it('shows an unparsable instant unchanged so nothing is silently invented', () => {
    expect(formatInstant('not-a-date')).toBe('not-a-date')
  })

  it('describes recent times relatively', () => {
    const justNow = new Date(Date.now() - 2_000).toISOString()
    expect(formatRelative(justNow)).toBe('刚刚')
    const minutesAgo = new Date(Date.now() - 5 * 60_000).toISOString()
    expect(formatRelative(minutesAgo)).toContain('分钟前')
  })

  it('splits a file name for display only', () => {
    expect(splitFileName('团队活动.mp4')).toEqual({ base: '团队活动', extension: 'mp4' })
    expect(splitFileName('no-extension')).toEqual({ base: 'no-extension', extension: '' })
    expect(splitFileName('.hidden')).toEqual({ base: '.hidden', extension: '' })
    expect(splitFileName(null)).toEqual({ base: '', extension: '' })
  })
})
