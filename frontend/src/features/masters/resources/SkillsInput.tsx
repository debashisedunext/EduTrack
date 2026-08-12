import * as React from 'react'
import { X } from 'lucide-react'

import { Input } from '@/components/ui/input'
import { Chip } from '@/components/ui/chip'
import type { FieldAria } from './FormField'

/**
 * B-011 · S-08's "Skills/tags", as a chip input.
 *
 * Feature-local rather than shared, the same call `WeeklyOffPicker` made:
 * `components/ui/` is Stream C's path and additive-only, and this has one
 * caller.
 *
 * <h2>Committing a tag</h2>
 *
 * Enter and comma both commit, and so does blur. The last one matters more than
 * it looks: somebody types a final skill and clicks Save, and without a
 * blur-commit the tag they can still see in the box is not in the request. The
 * field appears to have silently dropped their input, and the only clue is that
 * the saved record is missing one skill.
 *
 * Backspace on an empty box removes the last chip, which is the behaviour every
 * tag input has and the one people try without being told.
 */
export interface SkillsInputProps {
  value: readonly string[]
  onChange: (skills: string[]) => void
  aria: FieldAria
  disabled?: boolean
  max?: number
}

export function SkillsInput({ value, onChange, aria, disabled, max = 30 }: SkillsInputProps) {
  const [draft, setDraft] = React.useState('')

  const commit = React.useCallback(
    (raw: string) => {
      const skill = raw.trim()
      setDraft('')
      if (skill === '' || value.length >= max) {
        return
      }
      // Case-insensitive, because "React" and "react" are one skill and a list
      // holding both looks like a data-entry mistake to everybody who reads it.
      if (value.some((existing) => existing.toLowerCase() === skill.toLowerCase())) {
        return
      }
      onChange([...value, skill])
    },
    [value, onChange, max],
  )

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') {
      // Enter inside a form submits it. A tag input that saves the whole
      // resource when you finish typing a skill is worse than one that does not
      // accept Enter at all.
      event.preventDefault()
      commit(draft)
      return
    }
    if (event.key === 'Backspace' && draft === '' && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label="Skills added">
          {value.map((skill) => (
            <li key={skill}>
              <Chip>
                {skill}
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => onChange(value.filter((s) => s !== skill))}
                  aria-label={`Remove ${skill}`}
                  className="ml-1 rounded-full p-0.5 hover:bg-subtle focus-visible:outline-none focus-visible:ring-2"
                >
                  <X className="h-3 w-3" />
                </button>
              </Chip>
            </li>
          ))}
        </ul>
      )}
      <Input
        {...aria}
        value={draft}
        disabled={disabled || value.length >= max}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={handleKeyDown}
        onBlur={() => commit(draft)}
        placeholder={value.length >= max ? `${max} is the maximum` : 'Type a skill and press Enter'}
      />
    </div>
  )
}
