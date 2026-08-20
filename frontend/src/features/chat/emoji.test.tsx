import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'

import { EmojiPicker } from './EmojiPicker'
import { ALL_EMOJI, EMOJI_GROUPS, insertEmoji } from './emoji'

describe('insertEmoji', () => {
  it('inserts at the caret rather than appending', () => {
    // The whole point. Appending looks right in every manual test — because a
    // test types the message first and picks the emoji second — and is wrong
    // the first time somebody goes back to add one mid-sentence.
    expect(insertEmoji('deployed to prod', 8, 8, '🚀')).toEqual({
      body: 'deployed🚀 to prod',
      caret: 10,
    })
  })

  it('replaces a selection', () => {
    expect(insertEmoji('this is bad', 8, 11, '👎').body).toBe('this is 👎')
  })

  it('adds no space at the end of the message', () => {
    expect(insertEmoji('shipped', 7, 7, '🎉')).toEqual({ body: 'shipped🎉', caret: 9 })
  })

  it('does not accumulate spaces when several are picked in a row', () => {
    const first = insertEmoji('nice', 4, 4, '👍')
    const second = insertEmoji(first.body, first.caret, first.caret, '🎉')
    expect(second.body).toBe('nice👍🎉')
  })

  it('does not glue an emoji onto the word it was inserted before', () => {
    expect(insertEmoji('ship it', 0, 0, '🚀').body).toBe('🚀 ship it')
  })

  it('survives a caret past the end of the body', () => {
    // React can re-render between the click and this call. A stale caret would
    // otherwise index past the string and put `undefined` inside it.
    expect(insertEmoji('hi', 99, 99, '👍').body).toBe('hi👍')
    expect(insertEmoji('hi', Number.NaN, Number.NaN, '👍').body).toBe('hi👍')
    expect(insertEmoji('hi', -3, -3, '👍').body).toBe('hi👍')
  })

  it('places the caret after everything it inserted', () => {
    const { body, caret } = insertEmoji('ship it', 0, 0, '🚀')
    // Slicing at the returned caret must land the user immediately before the
    // rest of their sentence, not inside the emoji's surrogate pair.
    expect(body.slice(caret)).toBe('ship it')
  })
})

describe('the palette', () => {
  it('offers no duplicates — a repeated cell is a wasted one', () => {
    expect(new Set(ALL_EMOJI).size).toBe(ALL_EMOJI.length)
  })

  it('names every group, because the labels are read out', () => {
    for (const group of EMOJI_GROUPS) {
      expect(group.name.trim()).not.toBe('')
      expect(group.emoji.length).toBeGreaterThan(0)
    }
  })
})

describe('EmojiPicker', () => {
  it('tells a screen reader the panel exists and whether it is open', () => {
    render(<EmojiPicker onPick={() => {}} />)
    const trigger = screen.getByRole('button', { name: 'Insert emoji' })

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('dialog', { name: 'Emoji' })).toBeInTheDocument()
  })

  it('labels every cell, since a bare glyph is announced differently on every platform', () => {
    render(<EmojiPicker onPick={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Insert emoji' }))

    const cells = screen
      .getAllByRole('button')
      .filter((button) => button.getAttribute('aria-label')?.includes('emoji '))
    expect(cells).toHaveLength(ALL_EMOJI.length)
  })

  it('hands the emoji back and closes', () => {
    const onPick = vi.fn()
    render(<EmojiPicker onPick={onPick} />)
    fireEvent.click(screen.getByRole('button', { name: 'Insert emoji' }))
    fireEvent.click(screen.getByRole('button', { name: /Reactions emoji 1:/ }))

    expect(onPick).toHaveBeenCalledWith('👍')
    expect(screen.queryByRole('dialog', { name: 'Emoji' })).not.toBeInTheDocument()
  })

  it('closes on Escape and returns focus to the trigger', () => {
    // A picker that traps focus in a grid the keyboard cannot leave is worse
    // than no picker.
    render(<EmojiPicker onPick={() => {}} />)
    const trigger = screen.getByRole('button', { name: 'Insert emoji' })
    fireEvent.click(trigger)

    fireEvent.keyDown(screen.getByRole('dialog', { name: 'Emoji' }), { key: 'Escape' })

    expect(screen.queryByRole('dialog', { name: 'Emoji' })).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('cannot be opened when there is no thread to write to', () => {
    render(<EmojiPicker onPick={() => {}} disabled />)
    expect(screen.getByRole('button', { name: 'Insert emoji' })).toBeDisabled()
  })
})
